package com.github.highcumontoa.iotdevicegatewayjava.session;

import java.util.List;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandQueueService;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.SessionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 接入门面：把"档案校验 + 会话建立 + 离线指令刷新"收敛到唯一入口。
 *
 * <p>确定性保证：只有成为当前生效会话的那次接入会触发指令刷新；
 * 重复接入（同 sessionId）也安全——FIFO 已在首次上线时被取空，不会重复投递；
 * 接管（新 sessionId）时旧会话指令已经投递过的不会再次进入缓冲。</p>
 */
@Service
public class DeviceGateway {

    private static final Logger log = LoggerFactory.getLogger(DeviceGateway.class);

    private final DeviceRegistry registry;
    private final SessionManager sessions;
    private final CommandQueueService commands;

    public DeviceGateway(DeviceRegistry registry, SessionManager sessions, CommandQueueService commands) {
        this.registry = registry;
        this.sessions = sessions;
        this.commands = commands;
    }

    /**
     * 设备接入：未登记/未激活由 SessionManager 抛出可区分错误；
     * 成功后（含首次接入与接管）按 FIFO + TTL 策略刷新离线指令。
     */
    public SessionInfo connect(String deviceId, String sessionId) {
        SessionInfo info = sessions.connect(deviceId, sessionId);
        List<CommandRecord> delivered = commands.flushOnConnect(deviceId, info.sessionId());
        log.info("connect resolved device={} session={} takeover={} previous={} flushed={}",
                deviceId, info.sessionId(), info.takeover(), info.previousSessionId(), delivered.size());
        return info;
    }

    /** 断开：仅当前生效会话可断开，旧会话请求为空操作。 */
    public void disconnect(String deviceId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException.of(
                    com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode.VALIDATION_FAILED,
                    "断开连接必须提供 sessionId");
        }
        sessions.disconnect(deviceId, sessionId);
    }

    /** 下发指令：在线直接投递，离线缓冲，满则可区分拒绝。 */
    public CommandRecord sendCommand(String deviceId, String type, String payload, Long ttlMillis) {
        return commands.enqueue(deviceId, type, payload, ttlMillis);
    }

    /** 显式扫描并标记过期指令（过期不静默丢弃）。 */
    public List<CommandRecord> sweepExpired(String deviceId) {
        registry.get(deviceId);
        return commands.expireDue(deviceId);
    }
}
