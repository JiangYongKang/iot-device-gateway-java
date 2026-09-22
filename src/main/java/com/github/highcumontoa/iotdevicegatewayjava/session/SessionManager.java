package com.github.highcumontoa.iotdevicegatewayjava.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.Clock;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.SessionInfo;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import org.springframework.stereotype.Service;

/**
 * 会话管理。
 *
 * <p>不变量：同一设备任意时刻只有一个生效会话。会话身份用 sessionId 引用相等判定，
 * 并发接入时只有一个能成为当前会话，其余得到确定的 SESSION_CONFLICT/接管结论。
 * 全局在线数使用原子计数与同步块保证容量超限时不留残留计数。</p>
 */
@Service
public class SessionManager {

    /** 当前在线会话（设备 ID -> 生效会话 ID），Map 上的原子操作与设备锁共同保证确定性。 */
    private final Map<String, SessionInfo> active = new ConcurrentHashMap<>();
    private final AtomicInteger onlineCount = new AtomicInteger();

    private final DeviceRegistry registry;
    private final Clock clock;
    private final GatewayProperties properties;
    private final CapacityMetrics metrics;

    public SessionManager(DeviceRegistry registry, Clock clock, GatewayProperties properties,
                          CapacityMetrics metrics) {
        this.registry = registry;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 设备接入。
     *
     * <p>拒绝原因可区分：未登记 DEVICE_NOT_FOUND；未激活/已停用 DEVICE_NOT_ACTIVATED；
     * 在线总数超限 CAPACITY_EXCEEDED。
     * 已在线时：相同 sessionId 重连幂等返回当前会话；不同 sessionId 确定性接管旧会话。</p>
     */
    public SessionInfo connect(String deviceId, String requestedSessionId) {
        String sessionId = (requestedSessionId == null || requestedSessionId.isBlank())
                ? newSessionId() : requestedSessionId;
        Device device = registry.get(deviceId); // 未登记 -> DEVICE_NOT_FOUND
        if (device.status() != DeviceStatus.ACTIVE) {
            // REGISTERED（未激活）与 DISABLED（停用）统一以"无权接入"拒绝，但信息可区分
            String reason = device.status() == DeviceStatus.REGISTERED ? "设备未激活" : "设备已被停用";
            throw GatewayException.of(ErrorCode.DEVICE_NOT_ACTIVATED,
                    reason + ": " + deviceId);
        }

        ReentrantLock lock = registry.lockFor(deviceId);
        lock.lock();
        try {
            SessionInfo current = active.get(deviceId);
            if (current != null) {
                if (current.sessionId().equals(sessionId)) {
                    // 同会话重复接入：幂等返回，绝不产生第二个会话，也不重复投递
                    return current;
                }
                // 确定性接管：以新引用原子替换，旧会话即刻失效（旧线程之后操作会被身份校验拒绝）
                SessionInfo takeover = new SessionInfo(deviceId, sessionId, clock.nowMillis(),
                        current.sessionId(), true);
                active.put(deviceId, takeover); // 在线计数不变：替换而非新增
                return takeover;
            }

            // 容量检查与入表必须原子，否则两个并发接入可能同时越限
            if (onlineCount.get() >= properties.getSession().getMaxConnections()) {
                metrics.incrementCapacityRejections();
                throw GatewayException.of(ErrorCode.CAPACITY_EXCEEDED,
                        "在线会话数达到上限: " + properties.getSession().getMaxConnections());
            }
            SessionInfo fresh = new SessionInfo(deviceId, sessionId, clock.nowMillis(), null, false);
            active.put(deviceId, fresh);
            onlineCount.incrementAndGet();
            return fresh;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 断开会话。只有当前生效会话可以断开；旧会话（已被接管）的断开请求为空操作，
     * 保证接管方不会被旧会话误踢下线。
     *
     * @return 是否真的摘除了当前会话
     */
    public boolean disconnect(String deviceId, String sessionId) {
        ReentrantLock lock = registry.lockFor(deviceId);
        lock.lock();
        try {
            SessionInfo current = active.get(deviceId);
            if (current == null || !current.sessionId().equals(sessionId)) {
                return false; // 离线或已被接管：确定地不产生副作用
            }
            active.remove(deviceId);
            onlineCount.decrementAndGet();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 当前生效会话；离线返回 null。 */
    public SessionInfo getActiveSession(String deviceId) {
        return active.get(deviceId);
    }

    public boolean isOnline(String deviceId) {
        return active.containsKey(deviceId);
    }

    public int onlineCount() {
        return onlineCount.get();
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
