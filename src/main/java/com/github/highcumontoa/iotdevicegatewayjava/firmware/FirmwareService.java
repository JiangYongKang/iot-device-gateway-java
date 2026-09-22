package com.github.highcumontoa.iotdevicegatewayjava.firmware;

import com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.concurrency.DeviceLockProvider;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.BatchUpgradeResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.FirmwareRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.UpgradeRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.UpgradeStatus;
import com.github.highcumontoa.iotdevicegatewayjava.registry.DeviceRegistryService;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 分批固件升级：
 *  - 兼容判定：型号匹配、未撤回(yanked)、当前版本落在 [fromMinInclusive, fromMaxExclusive)
 *    才允许下发，否则 FIRMWARE_INCOMPATIBLE，且不允许同版本“升级”。
 *  - 幂等：同一 (deviceId, targetVersion) 进行中 -> FIRMWARE_DUPLICATE_REQUEST 并返回原单；
 *    同 idempotencyKey 直接返回首次创建的单。
 *  - 单飞：同一设备任意时刻至多一个进行中升级，否则 FIRMWARE_ALREADY_IN_PROGRESS。
 *  - 失败/失联：回滚到 fromVersion；成功才更新设备版本。全程保留可追溯记录。
 *  - 分批：逐设备独立加锁判定，单台异常不影响其他设备，且每台原子生效。
 */
@Service
public class FirmwareService {

    private static final Logger log = LoggerFactory.getLogger(FirmwareService.class);

    private static final Set<UpgradeStatus> IN_PROGRESS = EnumSet.of(
            UpgradeStatus.PENDING, UpgradeStatus.DOWNLOADING, UpgradeStatus.INSTALLING);

    private final Map<String, FirmwareRecord> firmware = new ConcurrentHashMap<>();
    private final Map<String, Deque<UpgradeRecord>> history = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    private final DeviceRegistryService registry;
    private final DeviceLockProvider locks;
    private final SessionManager sessions;
    private final Clock clock;

    public FirmwareService(DeviceRegistryService registry, DeviceLockProvider locks,
                           SessionManager sessions, Clock clock) {
        this.registry = registry;
        this.locks = locks;
        this.sessions = sessions;
        this.clock = clock;
    }

    public FirmwareRecord publish(FirmwareRecord record) {
        if (record == null || record.version() == null || record.compatibleModel() == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "固件记录缺少 version/compatibleModel");
        }
        try {
            VersionCompare.compare(record.fromMinInclusive(), record.fromMinInclusive());
            VersionCompare.compare(record.fromMaxExclusive(), record.fromMaxExclusive());
        } catch (IllegalArgumentException e) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "兼容版本区间非法: " + e.getMessage());
        }
        firmware.put(record.version(), record);
        log.info("固件发布 version={} model={} range=[{},{}) yanked={}",
                record.version(), record.compatibleModel(),
                record.fromMinInclusive(), record.fromMaxExclusive(), record.yanked());
        return record;
    }

    public UpgradeRecord startUpgrade(String deviceId, String targetVersion,
                                      String idempotencyKey) {
        registry.requireActivated(deviceId);
        FirmwareRecord target = requireFirmware(targetVersion);

        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            // 幂等键：返回首次创建的升级单
            if (idempotencyKey != null && idempotency.containsKey(idempotencyKey)) {
                String upgradeId = idempotency.get(idempotencyKey);
                log.info("升级幂等命中 deviceId={} key={} upgradeId={}",
                        deviceId, idempotencyKey, upgradeId);
                return findRecord(history.getOrDefault(deviceId, new ArrayDeque<>()), upgradeId);
            }

            Deque<UpgradeRecord> records = history.computeIfAbsent(
                    deviceId, k -> new ArrayDeque<>());
            UpgradeRecord active = findActive(records);
            if (active != null) {
                if (active.targetVersion().equals(targetVersion)) {
                    log.info("重复升级请求被幂等拒绝 deviceId={} version={} upgradeId={}",
                            deviceId, targetVersion, active.upgradeId());
                    throw new GatewayException(ErrorCode.FIRMWARE_DUPLICATE_REQUEST,
                            "同版本升级已在进行 " + active.upgradeId());
                }
                throw new GatewayException(ErrorCode.FIRMWARE_ALREADY_IN_PROGRESS,
                        "设备已有进行中升级 " + active.upgradeId());
            }

            String fromVersion = registry.get(deviceId).firmwareVersion();
            String incompatible = checkCompatibility(deviceId, target, fromVersion);
            if (incompatible != null) {
                log.info("升级兼容判定拒绝 deviceId={} from={} target={} reason={}",
                        deviceId, fromVersion, targetVersion, incompatible);
                throw new GatewayException(ErrorCode.FIRMWARE_INCOMPATIBLE, incompatible);
            }

            String upgradeId = "upg-" + idSeq.incrementAndGet();
            UpgradeRecord record = new UpgradeRecord(upgradeId, deviceId, targetVersion,
                    fromVersion, UpgradeStatus.PENDING, "升级已创建",
                    Instant.now(clock), null);
            records.addLast(record);
            trimHistory(records);
            if (idempotencyKey != null) {
                idempotency.put(idempotencyKey, upgradeId);
            }
            log.info("升级创建 deviceId={} upgradeId={} from={} target={}",
                    deviceId, upgradeId, fromVersion, targetVersion);
            return record;
        } finally {
            lock.unlock();
        }
    }

    /** 设备侧回报进度/结果；失败触发回滚，失联由 markOffline 触发。 */
    public UpgradeRecord reportProgress(String deviceId, String sessionId, String upgradeId,
                                        String status, String detail) {
        // 允许在升级流程中会话被接管后的新会话继续回报；旧会话回报按 SESSION_STALE 拒绝。
        sessions.requireValid(deviceId, sessionId);
        UpgradeStatus next;
        try {
            next = UpgradeStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "未知升级状态: " + status);
        }
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            Deque<UpgradeRecord> records = history.computeIfAbsent(
                    deviceId, k -> new ArrayDeque<>());
            UpgradeRecord current = findRecord(records, upgradeId);
            if (current == null) {
                throw new GatewayException(ErrorCode.FIRMWARE_NO_ACTIVE_UPGRADE,
                        "升级单不存在 " + upgradeId);
            }
            if (!IN_PROGRESS.contains(current.status())) {
                log.info("终态升级回报忽略 deviceId={} upgradeId={} status={}",
                        deviceId, upgradeId, current.status());
                return current;
            }
            Instant finishedAt = IN_PROGRESS.contains(next) ? null : Instant.now(clock);
            UpgradeRecord updated = new UpgradeRecord(current.upgradeId(), deviceId,
                    current.targetVersion(), current.fromVersion(), next,
                    detail == null ? current.detail() : detail, current.startedAt(), finishedAt);

            if (next == UpgradeStatus.SUCCEEDED) {
                registry.updateFirmwareVersion(deviceId, current.targetVersion());
                log.info("升级成功并切换版本 deviceId={} upgradeId={} version={}",
                        deviceId, upgradeId, current.targetVersion());
            } else if (next == UpgradeStatus.FAILED) {
                updated = doRollback(records, updated, detail);
            }
            replaceRecord(records, updated);
            return updated;
        } finally {
            lock.unlock();
        }
    }

    /** 设备在升级进行中断线/失联：按策略回滚并保留记录。 */
    public void markOffline(String deviceId) {
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            Deque<UpgradeRecord> records = history.get(deviceId);
            if (records == null) {
                return;
            }
            UpgradeRecord active = findActive(records);
            if (active != null) {
                log.warn("升级中设备失联，执行回滚 deviceId={} upgradeId={}",
                        deviceId, active.upgradeId());
                replaceRecord(records, doRollback(records, active, "设备失联，按策略回滚"));
            }
        } finally {
            lock.unlock();
        }
    }

    private UpgradeRecord doRollback(Deque<UpgradeRecord> records, UpgradeRecord failed,
                                     String detail) {
        // 本地模拟回滚总是可恢复：设备版本未被切换（成功才切换），记录 ROLLED_BACK。
        UpgradeStatus status = UpgradeStatus.ROLLED_BACK;
        Instant now = Instant.now(clock);
        UpgradeRecord rolled = new UpgradeRecord(failed.upgradeId(), failed.deviceId(),
                failed.targetVersion(), failed.fromVersion(), status,
                "已回滚至 " + failed.fromVersion() + "；原始失败=" + detail,
                failed.startedAt(), now);
        log.info("升级回滚完成 deviceId={} upgradeId={} restoredVersion={}",
                failed.deviceId(), failed.upgradeId(), failed.fromVersion());
        return rolled;
    }

    private String checkCompatibility(String deviceId, FirmwareRecord target,
                                      String currentVersion) {
        if (target.yanked()) {
            return "目标版本已被撤回，禁止下发: " + target.version();
        }
        var device = registry.get(deviceId);
        if (!target.compatibleModel().equals(device.model())) {
            return "型号不兼容: 设备=" + device.model()
                    + " 固件要求=" + target.compatibleModel();
        }
        if (currentVersion == null) {
            return "设备当前版本未知，无法判定兼容性";
        }
        if (VersionCompare.compare(currentVersion, target.version()) == 0) {
            return "设备已处于目标版本，禁止重复升级: " + currentVersion;
        }
        if (VersionCompare.compare(currentVersion, target.fromMinInclusive()) < 0) {
            return "当前版本过低 " + currentVersion + " < 最低可升版本 "
                    + target.fromMinInclusive();
        }
        if (VersionCompare.compare(currentVersion, target.fromMaxExclusive()) >= 0) {
            return "当前版本不在允许升级区间: " + currentVersion
                    + " >= " + target.fromMaxExclusive();
        }
        return null;
    }

    public BatchUpgradeResult startBatch(List<String> deviceIds, String targetVersion,
                                         String idempotencyKeyPrefix) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "设备列表不能为空");
        }
        Map<String, String> accepted = new LinkedHashMap<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        List<UpgradeRecord> records = new ArrayList<>();
        for (String deviceId : deviceIds) {
            try {
                String key = idempotencyKeyPrefix == null ? null
                        : idempotencyKeyPrefix + ":" + deviceId;
                UpgradeRecord rec = startUpgrade(deviceId, targetVersion, key);
                accepted.put(deviceId, rec.upgradeId());
                records.add(rec);
            } catch (GatewayException e) {
                // 单台失败被分类捕获，不拖垮整批
                rejected.put(deviceId, e.code().name() + ":" + e.getMessage());
                log.warn("分批升级单台拒绝 deviceId={} code={}", deviceId, e.code());
            }
        }
        log.info("分批升级完成 target={} accepted={} rejected={}",
                targetVersion, accepted.size(), rejected.size());
        return new BatchUpgradeResult(targetVersion, Map.copyOf(accepted),
                Map.copyOf(rejected), List.copyOf(records));
    }

    public List<UpgradeRecord> history(String deviceId) {
        Deque<UpgradeRecord> records = history.get(deviceId);
        if (records == null) {
            return List.of();
        }
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            return List.copyOf(new ArrayList<>(records));
        } finally {
            lock.unlock();
        }
    }

    private FirmwareRecord requireFirmware(String version) {
        FirmwareRecord record = firmware.get(version);
        if (record == null) {
            throw new GatewayException(ErrorCode.FIRMWARE_INCOMPATIBLE,
                    "固件版本不存在: " + version);
        }
        return record;
    }

    private UpgradeRecord findActive(Deque<UpgradeRecord> records) {
        UpgradeRecord active = null;
        for (UpgradeRecord r : records) {
            if (IN_PROGRESS.contains(r.status())) {
                active = r;
            }
        }
        return active;
    }

    private UpgradeRecord findRecord(Deque<UpgradeRecord> records, String upgradeId) {
        for (UpgradeRecord r : records) {
            if (r.upgradeId().equals(upgradeId)) {
                return r;
            }
        }
        return null;
    }

    private void replaceRecord(Deque<UpgradeRecord> records, UpgradeRecord updated) {
        Deque<UpgradeRecord> copy = new ArrayDeque<>();
        for (UpgradeRecord r : records) {
            copy.addLast(r.upgradeId().equals(updated.upgradeId()) ? updated : r);
        }
        records.clear();
        records.addAll(copy);
    }

    private void trimHistory(Deque<UpgradeRecord> records) {
        while (records.size() > 50) {
            records.removeFirst();
        }
    }
}
