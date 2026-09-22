package com.github.highcumontoa.iotdevicegatewayjava.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.Clock;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.SessionInfo;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 指令缓冲与时效处理。
 *
 * <p>不变量：</p>
 * <ul>
 *   <li>每条指令全生命周期只有一份记录，所有状态流转在该设备的分条锁内完成；</li>
 *   <li>在线指令投递前以当前生效会话身份做 CAS 校验，旧会话/失联期间绝不重复投递；</li>
 *   <li>过期与失败显式落状态（EXPIRED/FAILED + 原因），绝不静默丢弃；</li>
 *   <li>队列满时拒绝入队（COMMAND_QUEUE_FULL），不留部分状态。</li>
 * </ul>
 */
@Service
public class CommandQueueService {

    private static final Logger log = LoggerFactory.getLogger(CommandQueueService.class);

    /** 设备 -> 待投递（QUEUED）指令 FIFO。终态记录保留在 {@link #allRecords} 中可追溯。 */
    private final Map<String, Deque<CommandRecord>> pendingByDevice = new ConcurrentHashMap<>();
    /** 指令 ID -> 当前记录（含终态），putIfAbsent 保证 ID 唯一、状态可查。 */
    private final Map<String, CommandRecord> allRecords = new ConcurrentHashMap<>();

    private final DeviceRegistry registry;
    private final SessionManager sessions;
    private final Clock clock;
    private final GatewayProperties properties;
    private final CapacityMetrics metrics;

    public CommandQueueService(DeviceRegistry registry, SessionManager sessions, Clock clock,
                               GatewayProperties properties, CapacityMetrics metrics) {
        this.registry = registry;
        this.sessions = sessions;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 下发指令。设备在线时直接对当前生效会话投递（DELIVERED）；
     * 离线时进入该设备 FIFO 缓冲。设备不存在/未激活为可区分拒绝。
     */
    public CommandRecord enqueue(String deviceId, String type, String payload, Long ttlMillis) {
        if (type == null || type.isBlank()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "指令类型不能为空");
        }
        Device device = registry.get(deviceId);
        if (device.status() != DeviceStatus.ACTIVE) {
            throw GatewayException.of(ErrorCode.DEVICE_NOT_ACTIVATED,
                    (device.status() == DeviceStatus.REGISTERED ? "设备未激活" : "设备已停用") + ": " + deviceId);
        }
        long now = clock.nowMillis();
        long ttl = ttlMillis != null ? ttlMillis : properties.getCommand().getDefaultTtlMillis();
        if (ttl <= 0) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "指令 TTL 必须为正数");
        }
        String commandId = UUID.randomUUID().toString();
        CommandRecord record = CommandRecord.queued(commandId, deviceId, type, payload, now, now + ttl);

        ReentrantLock lock = registry.lockFor(deviceId);
        lock.lock();
        try {
            // 登记记录（ID 唯一）
            if (allRecords.putIfAbsent(commandId, record) != null) {
                throw GatewayException.of(ErrorCode.INTERNAL_ERROR, "指令 ID 冲突");
            }
            SessionInfo session = sessions.getActiveSession(deviceId);
            if (session != null) {
                // 在线：直接投递到当前会话，DELIVERED 记录会话身份，用于 ack 校验
                CommandRecord delivered = record.withStatus(CommandStatus.DELIVERED, now,
                        session.sessionId(), null);
                allRecords.put(commandId, delivered);
                log.info("command delivered online device={} command={} type={} session={}",
                        deviceId, commandId, type, session.sessionId());
                return delivered;
            }
            // 离线：容量受控缓冲，超限显式拒绝并清理登记，避免残留
            Deque<CommandRecord> queue = pendingByDevice.computeIfAbsent(deviceId, k -> new ArrayDeque<>());
            if (queue.size() >= properties.getCommand().getQueueCapacityPerDevice()) {
                allRecords.remove(commandId);
                metrics.incrementCommandQueueRejections();
                throw GatewayException.of(ErrorCode.COMMAND_QUEUE_FULL,
                        "设备离线指令队列已满: " + deviceId + " 容量="
                                + properties.getCommand().getQueueCapacityPerDevice());
            }
            queue.addLast(record);
            log.info("command queued offline device={} command={} type={} expireAt={} depth={}",
                    deviceId, commandId, type, record.expireAt(), queue.size());
            return record;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 设备（重新）上线后刷新缓冲：FIFO 逐条处理。
     * 已过期 -> EXPIRED（计指标，保留原因）；未过期 -> 对本次会话 DELIVERED。
     * 仅在传入会话仍是当前生效会话时执行，防止旧会话重放导致重复投递。
     *
     * @return 本次实际进入 DELIVERED 的指令（过期项不在其中，可通过查询看到 EXPIRED）
     */
    public List<CommandRecord> flushOnConnect(String deviceId, String sessionId) {
        ReentrantLock lock = registry.lockFor(deviceId);
        lock.lock();
        try {
            SessionInfo current = sessions.getActiveSession(deviceId);
            if (current == null || !current.sessionId().equals(sessionId)) {
                // 会话已失效（被接管或断开）：不投递任何指令
                return Collections.emptyList();
            }
            Deque<CommandRecord> queue = pendingByDevice.get(deviceId);
            if (queue == null || queue.isEmpty()) {
                return Collections.emptyList();
            }
            long now = clock.nowMillis();
            List<CommandRecord> delivered = new ArrayList<>();
            CommandRecord record;
            while ((record = queue.pollFirst()) != null) {
                if (record.expireAt() <= now) {
                    CommandRecord expired = record.withStatus(CommandStatus.EXPIRED, now, null,
                            "超过投递有效期 expireAt=" + record.expireAt());
                    allRecords.put(record.commandId(), expired);
                    metrics.incrementCommandsExpired();
                    log.info("command expired on connect device={} command={} expireAt={} now={}",
                            deviceId, record.commandId(), record.expireAt(), now);
                } else {
                    CommandRecord out = record.withStatus(CommandStatus.DELIVERED, now, sessionId, null);
                    allRecords.put(record.commandId(), out);
                    delivered.add(out);
                    log.info("command flushed on connect device={} command={} session={}",
                            deviceId, record.commandId(), sessionId);
                }
            }
            return delivered;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 设备对指令结果回执。只有投递目标会话（当前生效会话）可回执；
     * 旧会话/重复回执得到确定性拒绝，成功或失败均显式落状态。
     */
    public CommandRecord acknowledge(String commandId, String sessionId, boolean success, String reason) {
        CommandRecord current = get(commandId);
        ReentrantLock lock = registry.lockFor(current.deviceId());
        lock.lock();
        try {
            current = allRecords.get(commandId);
            if (current.status() == CommandStatus.ACKED || current.status() == CommandStatus.FAILED) {
                // 重复回执幂等返回当前终态，不改变结论
                return current;
            }
            if (current.status() != CommandStatus.DELIVERED) {
                throw GatewayException.of(ErrorCode.VALIDATION_FAILED,
                        "指令当前状态不允许回执: " + current.status());
            }
            if (!current.sessionId().equals(sessionId)) {
                throw GatewayException.of(ErrorCode.SESSION_CONFLICT,
                        "回执会话与投递会话不一致，拒绝旧会话操作 command=" + commandId);
            }
            CommandStatus target = success ? CommandStatus.ACKED : CommandStatus.FAILED;
            CommandRecord updated = current.withStatus(target, clock.nowMillis(), sessionId,
                    success ? null : ("设备执行失败: " + reason));
            allRecords.put(commandId, updated);
            log.info("command ack device={} command={} success={} reason={}",
                    current.deviceId(), commandId, success, reason);
            return updated;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 离线期间扫描过期指令（不依赖上线动作也能判定时效）。
     * 终态为 EXPIRED 并保留原因；未过期保持 QUEUED。
     */
    public List<CommandRecord> expireDue(String deviceId) {
        ReentrantLock lock = registry.lockFor(deviceId);
        lock.lock();
        try {
            Deque<CommandRecord> queue = pendingByDevice.get(deviceId);
            if (queue == null || queue.isEmpty()) {
                return Collections.emptyList();
            }
            long now = clock.nowMillis();
            // QUEUED 队列按过期时间有序（FIFO 入队且统一 TTL 不保证不同 TTL 也有序，故全量判定）
            List<CommandRecord> expiredAll = new ArrayList<>();
            List<CommandRecord> stillPending = new ArrayList<>();
            for (CommandRecord record : queue) {
                if (record.expireAt() <= now) {
                    CommandRecord expired = record.withStatus(CommandStatus.EXPIRED, now, null,
                            "离线期间超过投递有效期 expireAt=" + record.expireAt());
                    allRecords.put(record.commandId(), expired);
                    metrics.incrementCommandsExpired();
                    expiredAll.add(expired);
                    log.info("command expired while offline device={} command={}", deviceId, record.commandId());
                } else {
                    stillPending.add(record);
                }
            }
            queue.clear();
            // 未过期项保持原有 FIFO 顺序回到队列
            for (CommandRecord record : stillPending) {
                queue.addLast(record);
            }
            return expiredAll;
        } finally {
            lock.unlock();
        }
    }

    public CommandRecord get(String commandId) {
        CommandRecord record = allRecords.get(commandId);
        if (record == null) {
            throw GatewayException.of(ErrorCode.COMMAND_NOT_FOUND, "指令不存在: " + commandId);
        }
        return record;
    }

    /** 查询设备全部指令（含终态），按创建时间排序，结论可解释。 */
    public List<CommandRecord> listForDevice(String deviceId) {
        registry.get(deviceId); // 存在性校验
        List<CommandRecord> out = new ArrayList<>();
        for (CommandRecord record : allRecords.values()) {
            if (record.deviceId().equals(deviceId)) {
                out.add(record);
            }
        }
        out.sort(Comparator.comparingLong(CommandRecord::createdAt));
        return out;
    }

    /** 当前仍在缓冲的待投递指令数（QUEUED）。 */
    public int queueDepth(String deviceId) {
        Deque<CommandRecord> queue = pendingByDevice.get(deviceId);
        return queue == null ? 0 : queue.size();
    }

    /** 仅供门面/测试使用：某设备当前记录快照（有序）。 */
    public Map<String, CommandRecord> recordsSnapshot(String deviceId) {
        Map<String, CommandRecord> out = new LinkedHashMap<>();
        for (CommandRecord record : listForDevice(deviceId)) {
            out.put(record.commandId(), record);
        }
        return out;
    }
}
