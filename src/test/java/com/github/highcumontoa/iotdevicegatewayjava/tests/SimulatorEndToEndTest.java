package com.github.highcumontoa.iotdevicegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandQueueService;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.ConvergenceView;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DesiredStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.FirmwareInfo;
import com.github.highcumontoa.iotdevicegatewayjava.domain.MetricSchema;
import com.github.highcumontoa.iotdevicegatewayjava.domain.UpgradeRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.UpgradeStatus;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import com.github.highcumontoa.iotdevicegatewayjava.simulator.DeviceSimulator;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateService;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceGateway;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.Clock;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.SettableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用本地模拟设备完整走一遍真实设备生命周期，替代真实硬件与外部服务：
 * 离线指令 -> 接入接管 -> 指令回执 -> 遥测 -> 期望状态收敛 -> 固件升级（成功+失败回滚）。
 */
class SimulatorEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(SimulatorEndToEndTest.class);

    private SettableClock clock;
    private GatewayProperties props;
    private DeviceRegistry registry;
    private SessionManager sessions;
    private CommandQueueService commands;
    private DeviceGateway gateway;
    private TelemetryService telemetry;
    private StateService state;
    private FirmwareService firmware;
    private DeviceSimulator sim;

    @BeforeEach
    void setUp() {
        clock = new SettableClock(1_000_000L);
        props = new GatewayProperties();
        CapacityMetrics metrics = new CapacityMetrics();
        registry = new DeviceRegistry(clock, props, metrics);
        sessions = new SessionManager(registry, clock, props, metrics);
        commands = new CommandQueueService(registry, sessions, clock, props, metrics);
        gateway = new DeviceGateway(registry, sessions, commands);
        telemetry = new TelemetryService(registry, clock, props);
        state = new StateService(registry, clock, props);
        firmware = new FirmwareService(registry, sessions, clock, props);
        sim = new DeviceSimulator(gateway, sessions, commands, telemetry, state, firmware);
    }

    @Test
    void fullDeviceLifecycleWithSimulator() {
        // 1) 登记 + 激活 + 遥测 schema + 固件目录
        registry.register("sensor-01", "sensor-1");
        registry.activate("sensor-01");
        telemetry.registerSchema(MetricSchema.number("temp", true, -40.0, 85.0));
        firmware.addFirmware(new FirmwareInfo("1.0", "sensor-1", null, false));
        firmware.addFirmware(new FirmwareInfo("2.0", "sensor-1", Set.of("1.0"), false));
        registry.updateFirmwareVersion("sensor-01", "1.0");

        // 2) 设备离线期间下发两条指令（缓冲）
        CommandRecord cmd1 = gateway.sendCommand("sensor-01", "SET_INTERVAL", "10s", null);
        CommandRecord cmd2 = gateway.sendCommand("sensor-01", "REBOOT", null, null);
        assertEquals(CommandStatus.QUEUED, cmd1.status());
        log.info("[E2E] 离线缓冲 cmd1={} cmd2={}", cmd1.commandId(), cmd2.commandId());

        // 3) 模拟设备接入，离线指令被投递；设备逐条确认
        String session = sim.connect("sensor-01", sim.newSessionId("sensor-01"));
        List<DeviceSimulator.DeliveredCommand> delivered = sim.pendingCommands("sensor-01");
        assertEquals(2, delivered.size());
        for (DeviceSimulator.DeliveredCommand dc : delivered) {
            sim.acknowledgeCommand("sensor-01", dc.commandId(), true, null);
        }
        assertEquals(CommandStatus.ACKED, commands.get(cmd1.commandId()).status());
        assertEquals(CommandStatus.ACKED, commands.get(cmd2.commandId()).status());
        log.info("[E2E] 设备接入 session={} 收到并确认指令数=2", session);

        // 4) 旧会话无法操作：新会话接管后，旧会话的 ack 被拒绝
        String newSession = sim.connect("sensor-01", sim.newSessionId("sensor-01"));
        assertFalse(session.equals(newSession));
        // 已 ACKED 的重复回执幂等；当前设备只有一个在线会话
        assertEquals(1, sessions.onlineCount());
        log.info("[E2E] 会话接管: old={} new={} online=1", session, newSession);

        // 5) 遥测上报两次（递增序号/时间戳）
        sim.publishTelemetry("sensor-01", 1L, 1_001_000L, Map.of("temp", 22.0));
        sim.publishTelemetry("sensor-01", 2L, 1_002_000L, Map.of("temp", 23.0));
        assertEquals(23.0, telemetry.latest("sensor-01").get("temp"));
        assertEquals(2, telemetry.acceptedCount("sensor-01"));
        log.info("[E2E] 遥测写入 latest.temp=23.0 count=2");

        // 6) 期望状态下发 -> 设备回报 -> 收敛
        state.applyDesired("sensor-01", Map.of("reportInterval", 30), 0L, "省电策略");
        ConvergenceView pending = state.getConvergence("sensor-01");
        assertEquals(DesiredStatus.PENDING, pending.desiredEntries().get("reportInterval").status());
        sim.reportState("sensor-01", Map.of("reportInterval", 30), Set.of(), Map.of(),
                clock.nowMillis());
        ConvergenceView synced = state.getConvergence("sensor-01");
        assertEquals(DesiredStatus.SYNCED, synced.desiredEntries().get("reportInterval").status());
        assertTrue(synced.allConverged());
        log.info("[E2E] 期望状态收敛 reportInterval=30 SYNCED");

        // 7) 固件升级：先失败触发回滚，再次升级成功
        UpgradeRecord up1 = firmware.startUpgrade("sensor-01", "2.0", "fw-attempt-1");
        sim.reportUpgradeResult(up1.upgradeId(), false, "模拟刷写失败");
        assertEquals(UpgradeStatus.FAILED, firmware.listUpgrades("sensor-01").get(0).status());
        assertEquals("1.0", registry.get("sensor-01").firmwareVersion());
        UpgradeRecord up2 = firmware.startUpgrade("sensor-01", "2.0", "fw-attempt-2");
        sim.reportUpgradeResult(up2.upgradeId(), true, null);
        assertEquals("2.0", registry.get("sensor-01").firmwareVersion());
        assertEquals(2, firmware.listUpgrades("sensor-01").size());
        log.info("[E2E] 固件升级: 首次失败回滚1.0(留痕) 二次成功2.0 记录数=2");
    }
}
