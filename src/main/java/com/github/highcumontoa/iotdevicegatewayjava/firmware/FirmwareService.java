package com.github.highcumontoa.iotdevicegatewayjava.firmware;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.github.highcumontoa.iotdevicegatewayjava.core.Clock;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.FirmwareInfo;
import com.github.highcumontoa.iotdevicegatewayjava.domain.UpgradeRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.UpgradeStatus;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 固件目录与升级编排。
 *
 * <p>关键不变量：</p>
 * <ul>
 *   <li>下发前做"存在 -> 型号匹配 -> 版本不同 -> 是否允许 -> 兼容性"链式判定，拒绝码稳定可区分；</li>
 *   <li>idempotencyKey 幂等：重复触发返回同一升级记录，不产生第二条进行中升级；</li>
 *   <li>同一设备同时最多一条 IN_PROGRESS（设备分条锁 + 状态校验）；</li>
 *   <li>失败或失联按策略回滚，全部结论追加为不可变升级记录。</li>
 * </ul>
 */
@Service
public class FirmwareService {

    private static final Logger log = LoggerFactory.getLogger(FirmwareService.class);

    /** 型号 -> 版本 -> 固件信息 */
    private final Map<String, Map<String, FirmwareInfo>> catalog = new ConcurrentHashMap<>();
    /** 设备 -> 升级记录（按开始时间有序） */
    private final Map<String, List<UpgradeRecord>> upgradesByDevice = new ConcurrentHashMap<>();
    /** 设备 -> 进行中升级记录 */
    private final Map<String, UpgradeRecord> inProgress = new ConcurrentHashMap<>();
    /** 幂等键 -> 升级 ID（全局） */
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    /** 升级 ID -> 设备（便于按 ID 完成） */
    private final Map<String, String> deviceByUpgrade = new ConcurrentHashMap<>();

    private final DeviceRegistry registry;
    private final SessionManager sessions;
    private final Clock clock;
    private final GatewayProperties properties;

    public FirmwareService(DeviceRegistry registry, SessionManager sessions, Clock clock,
                           GatewayProperties properties) {
        this.registry = registry;
        this.sessions = sessions;
        this.clock = clock;
        this.properties = properties;
    }

    public FirmwareInfo addFirmware(FirmwareInfo info) {
        if (info == null || info.version() == null || info.model() == null) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "固件型号与版本不能为空");
        }
        catalog.computeIfAbsent(info.model(), k -> new ConcurrentHashMap<>()).put(info.version(), info);
        log.info("firmware catalog upsert model={} version={} blocked={} compatibleFrom={}",
                info.model(), info.version(), info.blocked(), info.compatibleFrom());
        return info;
    }

    public FirmwareInfo getFirmware(String model, String version) {
        Map<String, FirmwareInfo> versions = catalog.get(model);
        FirmwareInfo info = versions == null ? null : versions.get(version);
        if (info == null) {
            throw GatewayException.of(ErrorCode.FIRMWARE_NOT_FOUND,
                    "固件不存在 model=" + model + " version=" + version);
        }
        return info;
    }

    public List<FirmwareInfo> listFirmware(String model) {
        Map<String, FirmwareInfo> versions = catalog.get(model);
        if (versions == null) {
            return List.of();
        }
        List<FirmwareInfo> out = new ArrayList<>(versions.values());
        out.sort(Comparator.comparing(FirmwareInfo::version));
        return out;
    }

    /**
     * 启动单设备升级。所有判定与状态写入在设备分条锁内一次完成，
     * 保证并发触发下"同设备同时只有一个进行中升级"。
     */
    public UpgradeRecord startUpgrade(String deviceId, String targetVersion, String idempotencyKey) {
        Device device = registry.get(deviceId);
        if (device.status() != DeviceStatus.ACTIVE) {
            throw GatewayException.of(ErrorCode.DEVICE_NOT_ACTIVATED,
                    "设备未激活/已停用: " + deviceId);
        }
        if (targetVersion == null || targetVersion.isBlank()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "目标固件版本不能为空");
        }

        ReentrantLock lock = registry.lockFor(deviceId);
        lock.lock();
        try {
            // 幂等：相同幂等键直接返回已创建的升级（即便其目标版本不同，也以首次请求为准）
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                String existingId = idempotencyIndex.get(idempotencyKey);
                if (existingId != null) {
                    UpgradeRecord existing = requireRecord(existingId);
                    log.info("upgrade idempotent hit device={} upgrade={} status={}",
                            deviceId, existingId, existing.status());
                    return existing;
                }
            }

            UpgradeRecord ongoing = inProgress.get(deviceId);
            if (ongoing != null) {
                throw GatewayException.of(ErrorCode.FIRMWARE_UPGRADE_IN_PROGRESS,
                        "设备存在进行中的升级: " + ongoing.upgradeId()
                                + " target=" + ongoing.toVersion());
            }

            // 链式兼容判定（错误码稳定可区分）
            FirmwareInfo target = resolveTarget(device.model(), targetVersion);
            String current = device.firmwareVersion();
            if (targetVersion.equals(current)) {
                throw GatewayException.of(ErrorCode.VALIDATION_FAILED,
                        "设备已处于目标版本，无需升级: " + targetVersion);
            }
            if (target.blocked()) {
                throw GatewayException.of(ErrorCode.FIRMWARE_VERSION_NOT_ALLOWED,
                        "目标版本已被禁止下发: " + targetVersion);
            }
            if (current != null && !target.allowsUpgradeFrom(current)) {
                throw GatewayException.of(ErrorCode.FIRMWARE_INCOMPATIBLE,
                        "不允许从当前版本升级 model=" + device.model()
                                + " from=" + current + " to=" + targetVersion);
            }

            String upgradeId = "up-" + java.util.UUID.randomUUID();
            UpgradeRecord record = UpgradeRecord.inProgress(upgradeId, deviceId, current,
                    targetVersion, clock.nowMillis());
            appendRecord(deviceId, record);
            inProgress.put(deviceId, record);
            deviceByUpgrade.put(upgradeId, deviceId);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyIndex.put(idempotencyKey, upgradeId);
            }
            log.info("upgrade started device={} upgrade={} from={} to={} online={}",
                    deviceId, upgradeId, current, targetVersion, sessions.isOnline(deviceId));
            return record;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 分批升级：逐设备独立判定，单个设备的拒绝不影响其他设备，
     * 每个设备至多产生一条进行中升级；返回每台设备的确定结果（成功记录或错误）。
     */
    public List<UpgradeResult> startBatch(List<String> deviceIds, String targetVersion, String idempotencyKey) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "分批升级设备列表不能为空");
        }
        List<UpgradeResult> results = new ArrayList<>();
        for (String deviceId : deviceIds) {
            try {
                // 每设备幂等键追加设备后缀，保证批量重复触发仍然逐设备幂等
                String perDeviceKey = idempotencyKey == null ? null
                        : idempotencyKey + ":" + deviceId;
                UpgradeRecord record = startUpgrade(deviceId, targetVersion, perDeviceKey);
                results.add(new UpgradeResult(deviceId, record, null));
            } catch (GatewayException ex) {
                log.warn("batch upgrade rejected device={} code={} msg={}",
                        deviceId, ex.errorCode(), ex.getMessage());
                results.add(new UpgradeResult(deviceId, null, new Failure(ex.errorCode(), ex.getMessage())));
            }
        }
        return results;
    }

    /**
     * 设备回报升级结果。成功 -> 更新档案版本并 SUCCESS；
     * 失败 -> FAILED 并按策略回滚档案版本，记录 ROLLED_BACK 结论。
     */
    public UpgradeRecord completeUpgrade(String upgradeId, boolean success, String reason) {
        String deviceId = deviceByUpgrade.get(upgradeId);
        if (deviceId == null) {
            throw GatewayException.of(ErrorCode.FIRMWARE_NOT_FOUND, "升级记录不存在: " + upgradeId);
        }
        ReentrantLock lock = registry.lockFor(deviceId);
        lock.lock();
        try {
            UpgradeRecord ongoing = inProgress.get(deviceId);
            if (ongoing == null || !ongoing.upgradeId().equals(upgradeId)) {
                // 可能已被超时扫描终结：返回既有终态（幂等）
                UpgradeRecord terminal = requireRecord(upgradeId);
                if (terminal.status() != UpgradeStatus.IN_PROGRESS) {
                    return terminal;
                }
                throw GatewayException.of(ErrorCode.FIRMWARE_UPGRADE_IN_PROGRESS,
                        "升级状态不一致: " + upgradeId);
            }
            long now = clock.nowMillis();
            if (success) {
                registry.updateFirmwareVersion(deviceId, ongoing.toVersion());
                UpgradeRecord done = ongoing.finish(UpgradeStatus.SUCCESS, now, reason, null);
                replaceRecord(deviceId, ongoing, done);
                inProgress.remove(deviceId, ongoing);
                log.info("upgrade success device={} upgrade={} version={}",
                        deviceId, upgradeId, ongoing.toVersion());
                return done;
            }
            return failAndMaybeRollbackLocked(ongoing, UpgradeStatus.FAILED,
                    "设备回报升级失败: " + reason, now);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 扫描进行中超时（设备失联）的升级：按策略回滚到升级前版本并标记 TIMED_OUT。
     * 由管理端/测试显式触发（不依赖后台线程，结论确定可复现）。
     */
    public List<UpgradeRecord> sweepTimeouts() {
        List<UpgradeRecord> timedOut = new ArrayList<>();
        long now = clock.nowMillis();
        long deadline = now - properties.getFirmware().getUpgradeTimeoutMillis();
        // 复制避免遍历中修改；每设备各自加锁
        for (Map.Entry<String, UpgradeRecord> entry : new ArrayList<>(inProgress.entrySet())) {
            String deviceId = entry.getKey();
            UpgradeRecord ongoing = entry.getValue();
            ReentrantLock lock = registry.lockFor(deviceId);
            lock.lock();
            try {
                UpgradeRecord current = inProgress.get(deviceId);
                if (current == null || !current.upgradeId().equals(ongoing.upgradeId())) {
                    continue;
                }
                if (current.startedAt() <= deadline) {
                    UpgradeRecord finished = failAndMaybeRollbackLocked(current, UpgradeStatus.TIMED_OUT,
                            "升级超时，设备失联 startedAt=" + current.startedAt() + " now=" + now, now);
                    timedOut.add(finished);
                }
            } finally {
                lock.unlock();
            }
        }
        return timedOut;
    }

    public List<UpgradeRecord> listUpgrades(String deviceId) {
        registry.get(deviceId);
        List<UpgradeRecord> out = upgradesByDevice.getOrDefault(deviceId, List.of());
        synchronized (out) {
            return List.copyOf(out);
        }
    }

    // ---- 内部方法（调用方已持有设备锁） ----

    private UpgradeRecord failAndMaybeRollbackLocked(UpgradeRecord ongoing, UpgradeStatus failureStatus,
                                                     String reason, long now) {
        String rollbackTo = null;
        UpgradeRecord failed = ongoing.finish(failureStatus, now, reason, null);
        if (properties.getFirmware().isRollbackOnFailure() && ongoing.fromVersion() != null) {
            registry.updateFirmwareVersion(ongoing.deviceId(), ongoing.fromVersion());
            rollbackTo = ongoing.fromVersion();
            failed = ongoing.finish(failureStatus, now, reason + "；已按策略回滚到 " + rollbackTo, rollbackTo);
        }
        replaceRecord(ongoing.deviceId(), ongoing, failed);
        inProgress.remove(ongoing.deviceId(), ongoing);
        log.warn("upgrade finished device={} upgrade={} status={} rollbackTo={}",
                ongoing.deviceId(), ongoing.upgradeId(), failureStatus, rollbackTo);
        return failed;
    }

    private FirmwareInfo resolveTarget(String model, String targetVersion) {
        Map<String, FirmwareInfo> versions = catalog.get(model);
        FirmwareInfo target = versions == null ? null : versions.get(targetVersion);
        if (target == null) {
            throw GatewayException.of(ErrorCode.FIRMWARE_NOT_FOUND,
                    "目标固件不存在 model=" + model + " version=" + targetVersion);
        }
        return target;
    }

    private void appendRecord(String deviceId, UpgradeRecord record) {
        List<UpgradeRecord> list =
                upgradesByDevice.computeIfAbsent(deviceId, k -> new ArrayList<>());
        synchronized (list) {
            list.add(record);
        }
    }

    private void replaceRecord(String deviceId, UpgradeRecord old, UpgradeRecord updated) {
        List<UpgradeRecord> list = upgradesByDevice.get(deviceId);
        if (list != null) {
            synchronized (list) {
                int idx = list.indexOf(old);
                if (idx >= 0) {
                    list.set(idx, updated);
                } else {
                    list.add(updated);
                }
            }
        }
    }

    private UpgradeRecord requireRecord(String upgradeId) {
        String deviceId = deviceByUpgrade.get(upgradeId);
        if (deviceId == null) {
            throw GatewayException.of(ErrorCode.FIRMWARE_NOT_FOUND, "升级记录不存在: " + upgradeId);
        }
        List<UpgradeRecord> list = upgradesByDevice.get(deviceId);
        if (list != null) {
            synchronized (list) {
                for (UpgradeRecord record : list) {
                    if (record.upgradeId().equals(upgradeId)) {
                        return record;
                    }
                }
            }
        }
        throw GatewayException.of(ErrorCode.FIRMWARE_NOT_FOUND, "升级记录不存在: " + upgradeId);
    }

    /** 分批升级中单台设备的确定结果。 */
    public record UpgradeResult(String deviceId, UpgradeRecord record, Failure failure) {
        public boolean success() {
            return record != null;
        }
    }

    /** 单设备失败的稳定分类。 */
    public record Failure(ErrorCode errorCode, String message) {
    }
}
