package com.github.highcumontoa.iotdevicegatewayjava.command;

import com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.concurrency.DeviceLockProvider;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.Command;
import com.github.highcumontoa.iotdevicegatewayjava.model.CommandStatus;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 指令缓冲与时效处理：
 *  - 设备离线：进入每设备有序缓冲；超出容量明确拒绝 COMMAND_QUEUE_FULL，不留半截。
 *  - 设备在线：立即投递；投递失败标记 FAILED（原因保留），不静默丢弃。
 *  - 重新上线：在设备锁内扫描缓冲，未过期且会话仍有效才投递，按 FIFO 投递；
 *    超过 expireAt 的标记 EXPIRED。任何一条失败只影响自身，不阻断其余投递。
 *  - 所有状态迁移整体替换 record，并以 commandId 幂等，杜绝重复投递。
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final Map<String, LinkedHashMap<String, Command>> queues = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    private final SessionManager sessions;
    private final DeviceLockProvider locks;
    private final GatewayProperties properties;
    private final Clock clock;
    private volatile CommandDeliverySink sink;

    public CommandService(SessionManager sessions, DeviceLockProvider locks,
                          GatewayProperties properties, Clock clock) {
        this.sessions = sessions;
        this.locks = locks;
        this.properties = properties;
        this.clock = clock;
    }

    public void setDeliverySink(CommandDeliverySink sink) {
        this.sink = sink;
    }

    public Command enqueue(String deviceId, String payload, Long ttlSeconds) {
        if (payload == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "指令 payload 不能为空");
        }
        long ttl = ttlSeconds == null ? properties.getCommandTtlSeconds() : ttlSeconds;
        if (ttl <= 0) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "ttlSeconds 必须为正数");
        }
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            LinkedHashMap<String, Command> queue =
                    queues.computeIfAbsent(deviceId, k -> new LinkedHashMap<>());
            Instant now = Instant.now(clock);
            String commandId = "cmd-" + idSeq.incrementAndGet();
            Command command = new Command(commandId, deviceId, payload, now,
                    now.plusSeconds(ttl), CommandStatus.PENDING, null);
            // 容量按“待处理（PENDING）”指令计；过期/失败等终态记录保留可查但不占缓冲配额。
            sweepExpired(queue, now);
            long pending = queue.values().stream()
                    .filter(c -> c.status() == CommandStatus.PENDING).count();
            if (pending >= properties.getMaxCommandsPerDevice()) {
                log.warn("指令缓冲已满 deviceId={} size={} max={} commandId={}",
                        deviceId, queue.size(), properties.getMaxCommandsPerDevice(), commandId);
                throw new GatewayException(ErrorCode.COMMAND_QUEUE_FULL,
                        "设备 " + deviceId + " 指令队列已满");
            }
            queue.put(commandId, command);
            log.info("指令缓冲 deviceId={} commandId={} expireAt={} queueSize={}",
                    deviceId, commandId, command.expireAt(), queue.size());
            // 在线则立即尝试投递；仍在锁内，与接管/断开不交叉。
            var current = sessions.current(deviceId);
            if (current != null) {
                attemptDelivery(queue, command, current.sessionId(), now);
            }
            pruneTerminalHistory(queue);
            return queue.get(commandId);
        } finally {
            lock.unlock();
        }
    }

    /** 会话（重新）上线时调用：按 FIFO 投递所有未过期 PENDING 指令。 */
    public void onSessionConnected(String deviceId, String sessionId) {
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            LinkedHashMap<String, Command> queue = queues.get(deviceId);
            if (queue == null || queue.isEmpty()) {
                return;
            }
            Instant now = Instant.now(clock);
            List<Command> pending = new ArrayList<>(queue.values());
            for (Command command : pending) {
                // 逐条重新读取当前会话：若中途被接管则停止，剩余指令留给新会话决策。
                var current = sessions.current(deviceId);
                if (current == null || !current.sessionId().equals(sessionId)) {
                    log.info("上线投递因会话变化中止 deviceId={} commandId={}",
                            deviceId, command.commandId());
                    break;
                }
                if (command.status() != CommandStatus.PENDING) {
                    continue;
                }
                if (now.isAfter(command.expireAt())) {
                    replace(queue, command, CommandStatus.EXPIRED, "超过有效期 " + command.expireAt());
                    log.info("缓冲指令过期丢弃标记 deviceId={} commandId={} expireAt={}",
                            deviceId, command.commandId(), command.expireAt());
                    continue;
                }
                attemptDelivery(queue, command, sessionId, now);
            }
        } finally {
            lock.unlock();
        }
    }

    private void attemptDelivery(LinkedHashMap<String, Command> queue, Command command,
                                 String sessionId, Instant now) {
        if (now.isAfter(command.expireAt())) {
            replace(queue, command, CommandStatus.EXPIRED, "超过有效期 " + command.expireAt());
            return;
        }
        if (sink == null) {
            replace(queue, command, CommandStatus.FAILED, "无可用投递通道");
            return;
        }
        try {
            sink.deliver(command.deviceId(), sessionId, command.commandId(), command.payload());
            replace(queue, command, CommandStatus.DELIVERED, null);
            log.info("指令已投递 deviceId={} commandId={} sessionId={}",
                    command.deviceId(), command.commandId(), sessionId);
        } catch (Exception e) {
            String reason = "投递失败: " + e.getClass().getSimpleName();
            replace(queue, command, CommandStatus.FAILED, reason);
            log.warn("指令投递失败并标记 deviceId={} commandId={} reason={}",
                    command.deviceId(), command.commandId(), reason);
        }
    }

    public Command ack(String deviceId, String sessionId, String commandId) {
        sessions.requireValid(deviceId, sessionId);
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            LinkedHashMap<String, Command> queue = queues.get(deviceId);
            Command command = queue == null ? null : queue.get(commandId);
            if (command == null) {
                throw new GatewayException(ErrorCode.INVALID_REQUEST,
                        "指令不存在: " + commandId);
            }
            if (command.status() == CommandStatus.DELIVERED || command.status() == CommandStatus.PENDING) {
                replace(queue, command, CommandStatus.ACKED, null);
                log.info("指令确认 deviceId={} commandId={}", deviceId, commandId);
            } else {
                log.info("指令确认忽略（非可确认状态）deviceId={} commandId={} status={}",
                        deviceId, commandId, command.status());
            }
            return queue.get(commandId);
        } finally {
            lock.unlock();
        }
    }

    public List<Command> list(String deviceId) {
        LinkedHashMap<String, Command> queue = queues.get(deviceId);
        if (queue == null) {
            return List.of();
        }
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            return List.copyOf(queue.values());
        } finally {
            lock.unlock();
        }
    }

    private void sweepExpired(LinkedHashMap<String, Command> queue, Instant now) {
        // 过期指令只标记不删除：过期必须显式可查，禁止静默丢弃。
        for (Command c : new ArrayList<>(queue.values())) {
            if (c.status() == CommandStatus.PENDING && now.isAfter(c.expireAt())) {
                replace(queue, c, CommandStatus.EXPIRED, "超过有效期(入队扫描)");
            }
        }
    }

    private void replace(LinkedHashMap<String, Command> queue, Command old,
                         CommandStatus status, String reason) {
        queue.put(old.commandId(), new Command(old.commandId(), old.deviceId(), old.payload(),
                old.createdAt(), old.expireAt(), status, reason));
    }

    /** 仅保留最近若干条终态记录供追溯，PENDING 永不裁剪，避免内存无限增长。 */
    private void pruneTerminalHistory(LinkedHashMap<String, Command> queue) {
        int retainTerminal = Math.max(16, properties.getMaxCommandsPerDevice());
        List<Command> all = new ArrayList<>(queue.values());
        int terminalSeen = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            Command c = all.get(i);
            if (c.status() != CommandStatus.PENDING) {
                terminalSeen++;
                if (terminalSeen > retainTerminal) {
                    queue.remove(c.commandId());
                }
            }
        }
    }

    /** 便于测试观察的队列深度。 */
    public int pendingCount(String deviceId) {
        LinkedHashMap<String, Command> queue = queues.get(deviceId);
        if (queue == null) {
            return 0;
        }
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            return (int) queue.values().stream()
                    .filter(c -> c.status() == CommandStatus.PENDING).count();
        } finally {
            lock.unlock();
        }
    }

    /** 过期判定辅助：外部（如定时清理）可直接调用。 */
    public void expireNow(String deviceId) {
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            LinkedHashMap<String, Command> queue = queues.get(deviceId);
            if (queue != null) {
                sweepExpired(queue, Instant.now(clock));
            }
        } finally {
            lock.unlock();
        }
    }

    public Duration ttl() {
        return Duration.ofSeconds(properties.getCommandTtlSeconds());
    }
}
