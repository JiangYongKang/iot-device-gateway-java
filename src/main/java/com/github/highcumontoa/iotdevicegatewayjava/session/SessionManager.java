package com.github.highcumontoa.iotdevicegatewayjava.session;

import com.github.highcumontoa.iotdevicegatewayjava.concurrency.DeviceLockProvider;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConnectResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConnectionState;
import com.github.highcumontoa.iotdevicegatewayjava.model.DeviceSession;
import com.github.highcumontoa.iotdevicegatewayjava.registry.DeviceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * 会话生命周期：
 * 同一设备任意时刻至多一个生效会话。同一 sessionId 重入幂等返回；
 * 不同 sessionId 接入构成接管：generation 单调递增，旧会话立即失效。
 * 旧会话的任何后续操作在 requireValid 中被判定 SESSION_STALE，
 * 从根上保证不会出现两个会话同时生效或指令重复投递。
 */
@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<String, DeviceSession> active = new ConcurrentHashMap<>();
    private final Map<String, ConnectionState> states = new ConcurrentHashMap<>();
    private final AtomicLong generationSeq = new AtomicLong(0);

    private final DeviceRegistryService registry;
    private final DeviceLockProvider locks;
    private final Clock clock;

    /** 会话上/下线回调（如上线触发缓冲指令投递），在设备锁内执行，避免交叉竞态。 */
    private volatile BiConsumer<String, String> afterConnect;
    private volatile BiConsumer<String, String> afterDisconnect;

    public SessionManager(DeviceRegistryService registry, DeviceLockProvider locks, Clock clock) {
        this.registry = registry;
        this.locks = locks;
        this.clock = clock;
    }

    public void setSessionHooks(BiConsumer<String, String> afterConnect,
                                BiConsumer<String, String> afterDisconnect) {
        this.afterConnect = afterConnect;
        this.afterDisconnect = afterDisconnect;
    }

    public ConnectResult connect(String deviceId, String sessionId) {
        registry.requireActivated(deviceId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "sessionId 不能为空");
        }
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            DeviceSession existing = active.get(deviceId);
            if (existing != null && existing.sessionId().equals(sessionId)) {
                log.info("接入幂等重入 deviceId={} sessionId={} generation={}",
                        deviceId, sessionId, existing.generation());
                return new ConnectResult(existing, true, false);
            }
            boolean takenOver = existing != null;
            long generation = generationSeq.incrementAndGet();
            DeviceSession session = new DeviceSession(
                    deviceId, sessionId, generation, Instant.now(clock));
            active.put(deviceId, session);
            states.put(deviceId, ConnectionState.ONLINE);
            log.info("会话{} deviceId={} oldSession={} newSession={} generation={}",
                    takenOver ? "接管" : "建立", deviceId,
                    existing == null ? null : existing.sessionId(), sessionId, generation);
            if (afterConnect != null) {
                afterConnect.accept(deviceId, sessionId);
            }
            return new ConnectResult(session, false, takenOver);
        } finally {
            lock.unlock();
        }
    }

    public void disconnect(String deviceId, String sessionId) {
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            DeviceSession existing = active.get(deviceId);
            if (existing == null) {
                return;
            }
            if (!existing.sessionId().equals(sessionId)) {
                log.info("旧会话断开被忽略（已被接管）deviceId={} staleSession={} current={}",
                        deviceId, sessionId, existing.sessionId());
                return;
            }
            active.remove(deviceId);
            states.put(deviceId, ConnectionState.OFFLINE);
            log.info("会话断开 deviceId={} sessionId={}", deviceId, sessionId);
            if (afterDisconnect != null) {
                afterDisconnect.accept(deviceId, sessionId);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 仅校验会话仍然是当前唯一生效会话；失效即 SESSION_STALE。 */
    public DeviceSession requireValid(String deviceId, String sessionId) {
        DeviceSession session = active.get(deviceId);
        if (session == null) {
            throw new GatewayException(ErrorCode.SESSION_STALE,
                    "设备当前不在线: " + deviceId);
        }
        if (!session.sessionId().equals(sessionId)) {
            log.info("拒绝旧会话操作 deviceId={} staleSession={} current={}",
                    deviceId, sessionId, session.sessionId());
            throw new GatewayException(ErrorCode.SESSION_STALE,
                    "会话已被接管或失效: " + deviceId);
        }
        return session;
    }

    public DeviceSession current(String deviceId) {
        return active.get(deviceId);
    }

    public ConnectionState state(String deviceId) {
        return states.getOrDefault(deviceId, ConnectionState.OFFLINE);
    }

    /** 内部包内使用：在设备锁内取当前会话（避免与接管交叉）。 */
    DeviceSession currentUnderLock(String deviceId) {
        return active.get(deviceId);
    }
}
