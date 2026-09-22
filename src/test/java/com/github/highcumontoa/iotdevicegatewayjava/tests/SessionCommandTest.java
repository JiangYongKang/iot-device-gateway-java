package com.github.highcumontoa.iotdevicegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.command.CommandQueueService;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.SettableClock;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.SessionInfo;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceGateway;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设备接入生命周期、会话接管确定性、离线指令缓冲/过期/恰好一次投递测试。
 * 日志打印关键输入与判定依据。
 */
class SessionCommandTest {

    private static final Logger log = LoggerFactory.getLogger(SessionCommandTest.class);

    private SettableClock clock;
    private GatewayProperties props;
    private DeviceRegistry registry;
    private SessionManager sessions;
    private CommandQueueService commands;
    private DeviceGateway gateway;
    private CapacityMetrics metrics;

    @BeforeEach
    void setUp() {
        clock = new SettableClock(1_000_000L);
        props = new GatewayProperties();
        props.getCommand().setQueueCapacityPerDevice(2);
        props.getCommand().setDefaultTtlMillis(10_000L);
        metrics = new CapacityMetrics();
        registry = new DeviceRegistry(clock, props, metrics);
        sessions = new SessionManager(registry, clock, props, metrics);
        commands = new CommandQueueService(registry, sessions, clock, props, metrics);
        gateway = new DeviceGateway(registry, sessions, commands);
    }

    private Device registerActive(String id) {
        registry.register(id, "sensor-1");
        return registry.activate(id);
    }

    @Test
    void rejectsUnregisteredAndInactiveWithDistinctReasons() {
        GatewayException notFound = assertThrows(GatewayException.class,
                () -> gateway.connect("ghost", "s1"));
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, notFound.errorCode());
        log.info("判定依据 未登记拒绝: code={} msg={}", notFound.errorCode(), notFound.getMessage());

        registry.register("dev-new", "sensor-1");
        GatewayException notActivated = assertThrows(GatewayException.class,
                () -> gateway.connect("dev-new", "s1"));
        assertEquals(ErrorCode.DEVICE_NOT_ACTIVATED, notActivated.errorCode());
        log.info("判定依据 未激活拒绝: code={} msg={}", notActivated.errorCode(), notActivated.getMessage());

        registry.activate("dev-new");
        registry.disable("dev-new");
        GatewayException disabled = assertThrows(GatewayException.class,
                () -> gateway.connect("dev-new", "s2"));
        assertEquals(ErrorCode.DEVICE_NOT_ACTIVATED, disabled.errorCode());
        assertEquals(DeviceStatus.DISABLED, registry.get("dev-new").status());
    }

    @Test
    void duplicateConnectIsIdempotentAndTakeoverIsDeterministic() {
        registerActive("dev");
        SessionInfo first = gateway.connect("dev", "session-A");
        SessionInfo again = gateway.connect("dev", "session-A");
        assertEquals(first.sessionId(), again.sessionId());
        assertFalse(again.takeover());
        assertEquals(1, sessions.onlineCount());
        log.info("同会话重复接入保持单一在线会话: online={}", sessions.onlineCount());

        SessionInfo second = gateway.connect("dev", "session-B");
        assertTrue(second.takeover());
        assertEquals("session-A", second.previousSessionId());
        assertEquals(1, sessions.onlineCount());
        log.info("会话接管确定: current={} previous={}", second.sessionId(), second.previousSessionId());

        // 旧会话断开不得踢掉新会话
        assertFalse(sessions.disconnect("dev", "session-A"));
        assertTrue(sessions.isOnline("dev"));
        assertTrue(sessions.disconnect("dev", "session-B"));
        assertFalse(sessions.isOnline("dev"));
    }

    @Test
    void offlineCommandsBufferFlushAndExpireWithExplicitStates() {
        props.getCommand().setQueueCapacityPerDevice(3);
        registerActive("dev");
        // 离线缓冲两条（TTL=10s）
        CommandRecord c1 = gateway.sendCommand("dev", "REBOOT", "p1", null);
        clock.advanceMillis(1L);
        CommandRecord c2 = gateway.sendCommand("dev", "CONFIG", "p2", null);
        assertEquals(CommandStatus.QUEUED, c1.status());
        assertEquals(2, commands.queueDepth("dev"));
        log.info("离线缓冲: c1={} c2={} depth=2", c1.commandId(), c2.commandId());

        // c3 使用更短 TTL=3s
        clock.advanceMillis(4_999L);
        CommandRecord c3 = gateway.sendCommand("dev", "PING", "p3", 3_000L);
        clock.advanceMillis(1L); // 总 5001ms：c1/c2 未过期，c3 未过期
        assertEquals(3, commands.queueDepth("dev"));
        clock.advanceMillis(1L); // 总 5002ms > 5000+3000-... c3 expireAt=5001+3000=8001，尚未过期
        assertEquals(0, gateway.sweepExpired("dev").size());
        clock.advanceMillis(3_000L); // 总 8002ms：c3 过期；c1 expireAt=10000，c2=10001 仍有效
        List<CommandRecord> expired = gateway.sweepExpired("dev");
        assertEquals(1, expired.size());
        assertEquals(c3.commandId(), expired.get(0).commandId());
        assertEquals(CommandStatus.EXPIRED, commands.get(c3.commandId()).status());
        assertTrue(commands.get(c3.commandId()).reason().contains("有效期"));
        log.info("过期判定: expiredCount={} reason={}", expired.size(), expired.get(0).reason());

        // 上线：c1/c2 恰好投递一次，c3 保持 EXPIRED 不投递
        SessionInfo session = gateway.connect("dev", "s-online");
        List<CommandRecord> delivered = commands.flushOnConnect("dev", session.sessionId());
        assertEquals(0, delivered.size(), "connect 时门面已刷新一次，再次 flush 不得重复投递");
        assertEquals(CommandStatus.DELIVERED, commands.get(c1.commandId()).status());
        assertEquals(CommandStatus.DELIVERED, commands.get(c2.commandId()).status());
        assertEquals(CommandStatus.EXPIRED, commands.get(c3.commandId()).status());
        log.info("上线投递: c1={} c2={} c3=EXPIRED 不重复投递",
                commands.get(c1.commandId()).status(), commands.get(c2.commandId()).status());
    }

    @Test
    void expiredOnConnectAreMarkedNotDroppedAndDeliveryIsOnce() {
        registerActive("dev");
        CommandRecord c1 = gateway.sendCommand("dev", "REBOOT", null, 1_000L);
        clock.advanceMillis(2_000L);
        SessionInfo session = gateway.connect("dev", "s1");
        assertEquals(CommandStatus.EXPIRED, commands.get(c1.commandId()).status());
        assertEquals(0, commands.flushOnConnect("dev", session.sessionId()).size());
        assertEquals(1, metrics.commandsExpired());
        log.info("上线时过期显式标记: status={} expiredMetric={}",
                commands.get(c1.commandId()).status(), metrics.commandsExpired());

        // 未过期指令上线投递一次，重复 flush 不重复投递
        Device dev2reg = registerActive("dev2");
        CommandRecord c2 = gateway.sendCommand("dev2", "OPEN", null, 10_000L);
        SessionInfo s2 = gateway.connect("dev2", "s2");
        assertEquals(CommandStatus.DELIVERED, commands.get(c2.commandId()).status());
        assertEquals(0, commands.flushOnConnect("dev2", s2.sessionId()).size());

        // 旧会话 ack 被拒，当前会话 ack 成功
        GatewayException oldAck = assertThrows(GatewayException.class,
                () -> commands.acknowledge(c2.commandId(), "stale-session", true, null));
        assertEquals(ErrorCode.SESSION_CONFLICT, oldAck.errorCode());
        CommandRecord acked = commands.acknowledge(c2.commandId(), "s2", true, null);
        assertEquals(CommandStatus.ACKED, acked.status());
        // 重复 ack 幂等
        assertEquals(CommandStatus.ACKED,
                commands.acknowledge(c2.commandId(), "s2", false, "again").status());
        log.info("旧会话拒绝码={} 当前会话ack状态={}", oldAck.errorCode(), acked.status());
    }

    @Test
    void queueOverflowRejectedWithoutResidualState() {
        registerActive("dev");
        gateway.sendCommand("dev", "A", null, null);
        gateway.sendCommand("dev", "B", null, null);
        GatewayException full = assertThrows(GatewayException.class,
                () -> gateway.sendCommand("dev", "C", null, null));
        assertEquals(ErrorCode.COMMAND_QUEUE_FULL, full.errorCode());
        assertEquals(2, commands.queueDepth("dev"));
        assertEquals(2, commands.listForDevice("dev").size());
        assertEquals(1, metrics.commandQueueRejections());
        log.info("队列超限拒绝: code={} depth={} records={} 无残留",
                full.errorCode(), commands.queueDepth("dev"), commands.listForDevice("dev").size());
    }
}
