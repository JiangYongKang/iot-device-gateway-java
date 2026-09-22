package com.github.highcumontoa.iotdevicegatewayjava;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandService;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import com.github.highcumontoa.iotdevicegatewayjava.sim.InProcessDeliverySink;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 装配跨服务钩子：
 *  - 会话上线 -> 触发离线缓冲指令投递；
 *  - 会话下线 -> 触发升级失联回滚判定。
 * 钩子均在会话管理器的设备锁内被调用，保证接管/投递/回滚不会交叉。
 */
@Component
public class GatewayOrchestrator {

    private final SessionManager sessions;
    private final CommandService commands;
    private final FirmwareService firmware;
    private final InProcessDeliverySink sink;

    public GatewayOrchestrator(SessionManager sessions, CommandService commands,
                               FirmwareService firmware, InProcessDeliverySink sink) {
        this.sessions = sessions;
        this.commands = commands;
        this.firmware = firmware;
        this.sink = sink;
    }

    @PostConstruct
    void wire() {
        commands.setDeliverySink(sink);
        sessions.setSessionHooks(
                (deviceId, sessionId) -> commands.onSessionConnected(deviceId, sessionId),
                (deviceId, sessionId) -> firmware.markOffline(deviceId)
        );
    }
}
