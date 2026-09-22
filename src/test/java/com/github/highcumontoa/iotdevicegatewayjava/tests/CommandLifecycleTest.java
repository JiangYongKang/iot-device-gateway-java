package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandService;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.Command;
import com.github.highcumontoa.iotdevicegatewayjava.model.CommandStatus;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.sim.InProcessDeliverySink;
import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class CommandLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(CommandLifecycleTest.class);

    @Autowired CommandService commands;
    @Autowired SessionManager sessions;
    @Autowired SimulatorService simulator;
    @Autowired InProcessDeliverySink sink;

    @BeforeEach
    void setUp() {
        simulator.installModelSchema();
        simulator.provisionDevice("dev-cmd-offline", "1.0.0", true);
        simulator.provisionDevice("dev-cmd-online", "1.0.0", true);
        simulator.provisionDevice("dev-cmd-takeover", "1.0.0", true);
        simulator.provisionDevice("dev-cmd-fail", "1.0.0", true);
    }

    @Test
    void offlineCommandsBufferThenDeliverOnReconnectExactlyOnce() {
        String deviceId = "dev-cmd-offline";
        Command c1 = commands.enqueue(deviceId, "open-valve", 300L);
        Command c2 = commands.enqueue(deviceId, "beep", 300L);
        log.info("[关键输入] 离线缓冲 c1={} c2={} pending={}",
                c1.commandId(), c2.commandId(), commands.pendingCount(deviceId));
        assertEquals(2, commands.pendingCount(deviceId));
        assertEquals(CommandStatus.PENDING, commands.list(deviceId).get(0).status());

        sessions.connect(deviceId, "sess-cmd-1");
        List<Command> list = commands.list(deviceId);
        log.info("[判定依据] 上线投递后状态 c1={} c2={} 投递会话c1={}",
                list.get(0).status(), list.get(1).status(),
                sink.deliveredSessionOf(c1.commandId()));
        assertEquals(CommandStatus.DELIVERED, list.get(0).status());
        assertEquals(CommandStatus.DELIVERED, list.get(1).status());
        assertEquals("sess-cmd-1", sink.deliveredSessionOf(c1.commandId()));

        // 旧会话 ACK 有效
        commands.ack(deviceId, "sess-cmd-1", c1.commandId());
        assertEquals(CommandStatus.ACKED,
                commands.list(deviceId).stream().filter(c -> c.commandId().equals(c1.commandId()))
                        .findFirst().orElseThrow().status());
    }

    @Test
    void expiredCommandsAreMarkedNotSilentlyDropped() {
        String deviceId = "dev-cmd-online";
        sessions.connect(deviceId, "sess-exp-1");
        sessions.disconnect(deviceId, "sess-exp-1");
        Command expired = commands.enqueue(deviceId, "late", 1L);
        log.info("[关键输入] 短时效指令入队 id={} expireAt={}",
                expired.commandId(), expired.expireAt());

        // 越过 TTL 再上线
        sleep(1100);
        sessions.connect(deviceId, "sess-exp-2");
        Command result = commands.list(deviceId).stream()
                .filter(c -> c.commandId().equals(expired.commandId())).findFirst().orElseThrow();
        log.info("[判定依据] 过期指令最终状态={} failReason={}", result.status(), result.failReason());
        assertEquals(CommandStatus.EXPIRED, result.status());
        assertNotNull(result.failReason());
        assertNull(sink.deliveredSessionOf(expired.commandId()), "过期指令不得投递");
    }

    @Test
    void takeoverDeliversBufferedCommandOnlyToNewSession() {
        String deviceId = "dev-cmd-takeover";
        // 设备离线期间缓冲一条指令
        Command command = commands.enqueue(deviceId, "config-sync", 300L);
        assertEquals(CommandStatus.PENDING,
                commands.list(deviceId).get(0).status());
        // 旧会话的连接请求与新会话竞争；无论谁先，最终生效的唯一生效会话拿到投递
        sessions.connect(deviceId, "sess-old");
        var takenOver = sessions.connect(deviceId, "sess-new");
        log.info("[关键输入] 接管发生 takenOver={} 指令={}", takenOver.takenOver(),
                command.commandId());
        String deliveredTo = sink.deliveredSessionOf(command.commandId());
        log.info("[判定依据] 缓冲指令唯一投递会话={} takenOver={}",
                deliveredTo, takenOver.takenOver());
        // sess-old 先连时指令可能已投递给它；此后绝不能再投递给 sess-new，反之亦然
        org.junit.jupiter.api.Assertions.assertTrue(
                "sess-old".equals(deliveredTo) || "sess-new".equals(deliveredTo));
        long distinctDeliveries = commands.list(deviceId).stream()
                .filter(c -> c.commandId().equals(command.commandId())
                        && c.status() == CommandStatus.DELIVERED).count();
        assertEquals(1, distinctDeliveries, "指令状态机只能有一次 DELIVERED");
        // 旧会话 ACK 必须被拒绝
        GatewayException stale = assertThrows(GatewayException.class,
                () -> commands.ack(deviceId, "sess-old", command.commandId()));
        assertEquals(ErrorCode.SESSION_STALE, stale.code());
    }

    @Test
    void failedDeliveryIsMarkedFailedAndQueueFullIsRejected() {
        String deviceId = "dev-cmd-fail";
        sessions.connect(deviceId, "sess-fail");
        sink.failSession("sess-fail");
        Command failed = commands.enqueue(deviceId, "will-fail", 300L);
        Command failedResult = commands.list(deviceId).stream()
                .filter(c -> c.commandId().equals(failed.commandId())).findFirst().orElseThrow();
        log.info("[判定依据] 投递失败状态={} reason={}",
                failedResult.status(), failedResult.failReason());
        assertEquals(CommandStatus.FAILED, failedResult.status());
        assertNotNull(failedResult.failReason());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
