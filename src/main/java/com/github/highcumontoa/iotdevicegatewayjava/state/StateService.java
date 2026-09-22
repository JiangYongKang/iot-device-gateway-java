package com.github.highcumontoa.iotdevicegatewayjava.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.github.highcumontoa.iotdevicegatewayjava.core.Clock;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.StripedLocks;
import com.github.highcumontoa.iotdevicegatewayjava.domain.ConvergenceView;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DesiredEntry;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DesiredRevision;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DesiredStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.ReportedState;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 期望状态下发与收敛服务。
 *
 * <p>并发正确性：</p>
 * <ul>
 *   <li>每设备一把分条锁，下发与回报互斥，配合版本号(expectedRevision)做乐观控制；
 *       版本不匹配拒绝(STATE_VERSION_CONFLICT)，杜绝丢失更新；</li>
 *   <li>下发在本地临时集合中完成全部条目构建后一次性提交，异常路径不产生半更新；</li>
 *   <li>收敛以"当前版本期望 vs 最新回报"为权威对照，历史版本结论保留可追溯。</li>
 * </ul>
 */
@Service
public class StateService {

    private static final Logger log = LoggerFactory.getLogger(StateService.class);

    private final Map<String, DeviceStateStore> stores = new ConcurrentHashMap<>();
    private final StripedLocks deviceLocks = new StripedLocks(256);

    private final DeviceRegistry registry;
    private final Clock clock;
    private final GatewayProperties properties;

    public StateService(DeviceRegistry registry, Clock clock, GatewayProperties properties) {
        this.registry = registry;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * 版本化下发期望。
     *
     * @param expectedRevision 调用方基于的当前版本；0 表示必须在无历史期望时创建。
     *                         与当前版本不一致 -> STATE_VERSION_CONFLICT。
     */
    public DesiredRevision applyDesired(String deviceId, Map<String, Object> desired,
                                        long expectedRevision, String note) {
        registry.get(deviceId);
        if (desired == null || desired.isEmpty()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "期望状态不能为空");
        }
        for (String key : desired.keySet()) {
            if (key == null || key.isBlank()) {
                throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "期望键名不能为空");
            }
        }

        ReentrantLock lock = deviceLocks.forKey(deviceId);
        lock.lock();
        try {
            DeviceStateStore store = stores.computeIfAbsent(deviceId, k -> new DeviceStateStore());
            if (store.currentRevision != expectedRevision) {
                throw GatewayException.of(ErrorCode.STATE_VERSION_CONFLICT,
                        "期望版本冲突 device=" + deviceId + " expected=" + expectedRevision
                                + " actual=" + store.currentRevision);
            }

            long now = clock.nowMillis();
            long newRevision = expectedRevision + 1;

            // 先在临时结构中构建新的期望条目，任何异常都不触碰既有状态（零半更新）
            Map<String, DesiredEntry> newEntries = new LinkedHashMap<>(store.currentEntries);
            for (Map.Entry<String, Object> e : desired.entrySet()) {
                // 旧版本同键条目标记 SUPERSEDED（保留在历史中，当前视图替换）
                DesiredEntry old = newEntries.get(e.getKey());
                if (old != null && old.status() == DesiredStatus.PENDING) {
                    newEntries.put(e.getKey(),
                            old.withConvergence(DesiredStatus.SUPERSEDED, now,
                                    "被修订版本 " + newRevision + " 取代"));
                }
                DesiredEntry entry = new DesiredEntry(e.getKey(), e.getValue(), newRevision, now,
                        DesiredStatus.PENDING, null, null);
                newEntries.put(e.getKey(), entry);
            }

            DesiredRevision revision = new DesiredRevision(newRevision,
                    Collections.unmodifiableMap(new LinkedHashMap<>(desired)), now, note);

            // 一次性提交
            store.revisions.add(revision);
            while (store.revisions.size() > properties.getState().getRetainedRevisionsPerDevice()) {
                store.revisions.remove(0);
            }
            store.currentEntries = newEntries;
            store.currentRevision = newRevision;

            // 若已有回报，立即基于当前回报做一次收敛判定（期望与回报交叉时仍确定）
            convergeWithReportedLocked(store, now);

            log.info("desired applied device={} revision={} keys={} note={}",
                    deviceId, newRevision, desired.keySet(), note);
            return revision;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 设备回报状态。回报值与当前期望逐条对照：
     * 相等 -> SYNCED；设备声明无法满足的键 -> REJECTED（带设备原因）；其余保持 PENDING。
     * 全部键 SYNCED 时 allConverged=true。
     */
    public ConvergenceView reportState(ReportedState reported) {
        if (reported == null || reported.deviceId() == null) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "回报缺少设备标识");
        }
        if (reported.reported() == null) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "回报状态不能为空");
        }
        registry.get(reported.deviceId());

        ReentrantLock lock = deviceLocks.forKey(reported.deviceId());
        lock.lock();
        try {
            DeviceStateStore store = stores.computeIfAbsent(reported.deviceId(), k -> new DeviceStateStore());
            long now = reported.reportedAt() > 0 ? reported.reportedAt() : clock.nowMillis();

            // 先构建新回报快照，再提交（异常不留半状态）
            Map<String, Object> newReported = new LinkedHashMap<>(reported.reported());
            Map<String, String> rejectReasons = new LinkedHashMap<>();
            if (reported.rejectionReasons() != null) {
                rejectReasons.putAll(reported.rejectionReasons());
            }

            store.reported = newReported;
            if (reported.rejectedKeys() != null) {
                store.rejectedKeys = new java.util.HashSet<>(reported.rejectedKeys());
            } else {
                store.rejectedKeys = new java.util.HashSet<>();
            }
            store.rejectionReasons = rejectReasons;

            convergeWithReportedLocked(store, now);
            log.info("state reported device={} keys={} rejected={}",
                    reported.deviceId(), newReported.keySet(), store.rejectedKeys);
            return buildViewLocked(reported.deviceId(), store);
        } finally {
            lock.unlock();
        }
    }

    public ConvergenceView getConvergence(String deviceId) {
        registry.get(deviceId);
        ReentrantLock lock = deviceLocks.forKey(deviceId);
        lock.lock();
        try {
            DeviceStateStore store = stores.get(deviceId);
            if (store == null) {
                return new ConvergenceView(deviceId, 0L, Map.of(), Map.of(), true);
            }
            return buildViewLocked(deviceId, store);
        } finally {
            lock.unlock();
        }
    }

    public List<DesiredRevision> listRevisions(String deviceId) {
        registry.get(deviceId);
        ReentrantLock lock = deviceLocks.forKey(deviceId);
        lock.lock();
        try {
            DeviceStateStore store = stores.get(deviceId);
            if (store == null) {
                return List.of();
            }
            return List.copyOf(store.revisions);
        } finally {
            lock.unlock();
        }
    }

    /** 锁内：根据当前期望与最新回报更新每个键的收敛结论（不改写历史修订）。 */
    private void convergeWithReportedLocked(DeviceStateStore store, long now) {
        if (store.reported == null) {
            return;
        }
        for (Map.Entry<String, DesiredEntry> e : store.currentEntries.entrySet()) {
            String key = e.getKey();
            DesiredEntry entry = e.getValue();
            if (entry.status() == DesiredStatus.SUPERSEDED) {
                continue; // 历史结论保留
            }
            if (store.rejectedKeys.contains(key)) {
                String reason = store.rejectionReasons.getOrDefault(key, "设备回报无法满足该期望");
                store.currentEntries.put(key,
                        entry.withConvergence(DesiredStatus.REJECTED, now, reason));
            } else if (store.reported.containsKey(key)
                    && Objects.equals(store.reported.get(key), entry.value())) {
                store.currentEntries.put(key,
                        entry.withConvergence(DesiredStatus.SYNCED, now, null));
            } else if (entry.status() == DesiredStatus.SYNCED) {
                // 设备回报值偏离已收敛期望：回到 PENDING 等待再次收敛（结论变化可解释）
                store.currentEntries.put(key,
                        entry.withConvergence(DesiredStatus.PENDING, null,
                                "设备回报值与期望不再一致，重新等待收敛"));
            }
        }
    }

    private ConvergenceView buildViewLocked(String deviceId, DeviceStateStore store) {
        Map<String, DesiredEntry> entries = new LinkedHashMap<>(store.currentEntries);
        boolean allConverged = true;
        for (DesiredEntry entry : entries.values()) {
            if (entry.status() != DesiredStatus.SYNCED && entry.status() != DesiredStatus.SUPERSEDED) {
                allConverged = false;
            }
        }
        if (entries.isEmpty()) {
            allConverged = true;
        }
        return new ConvergenceView(deviceId, store.currentRevision,
                Collections.unmodifiableMap(entries),
                store.reported == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(store.reported)),
                allConverged);
    }

    /** 每设备的状态存储（所有访问均在该设备分条锁保护下）。 */
    private static final class DeviceStateStore {
        private long currentRevision;
        private Map<String, DesiredEntry> currentEntries = new LinkedHashMap<>();
        private final List<DesiredRevision> revisions = new ArrayList<>();
        private Map<String, Object> reported;
        private java.util.Set<String> rejectedKeys = new java.util.HashSet<>();
        private Map<String, String> rejectionReasons = new LinkedHashMap<>();
    }
}
