package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandService;
import com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.registry.DeviceRegistryService;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatorService;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 容量与配额：临时调小配置上限，验证超限被明确拒绝且无部分写入/残留。
 */
@SpringBootTest
class CapacityQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(CapacityQuotaTest.class);

    @Autowired CommandService commands;
    @Autowired TelemetryService telemetry;
    @Autowired DeviceRegistryService registry;
    @Autowired SessionManager sessions;
    @Autowired SimulatorService simulator;
    @Autowired GatewayProperties properties;

    @Test
    void commandQueueCapacityRejectsWithoutPartialWrite() {
        simulator.installModelSchema();
        simulator.provisionDevice("dev-cap-cmd", "1.0.0", true);
        int saved = properties.getMaxCommandsPerDevice();
        properties.setMaxCommandsPerDevice(3);
        try {
            for (int i = 0; i < 3; i++) {
                commands.enqueue("dev-cap-cmd", "p-" + i, 300L);
            }
            GatewayException ex = assertThrows(GatewayException.class,
                    () -> commands.enqueue("dev-cap-cmd", "overflow", 300L));
            log.info("[判定依据] 指令队列超限 code={} pending={}",
                    ex.code(), commands.pendingCount("dev-cap-cmd"));
            assertEquals(ErrorCode.COMMAND_QUEUE_FULL, ex.code());
            assertEquals(3, commands.pendingCount("dev-cap-cmd"), "超限请求不得产生残留指令");
        } finally {
            properties.setMaxCommandsPerDevice(saved);
        }
    }

    @Test
    void telemetryCapacityRejectsWithoutPartialWrite() {
        simulator.installModelSchema();
        simulator.provisionDevice("dev-cap-tel", "1.0.0", true);
        sessions.connect("dev-cap-tel", "sess-cap");
        int saved = properties.getMaxTelemetryPerDevice();
        properties.setMaxTelemetryPerDevice(3);
        try {
            Instant t = Instant.ofEpochMilli(7_000_000);
            for (int i = 1; i <= 3; i++) {
                telemetry.ingest("dev-cap-tel", "sess-cap",
                        new com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryPoint(
                                "dev-cap-tel", i, t.plusMillis(i), Map.of("temperature", 21.0)));
            }
            GatewayException ex = assertThrows(GatewayException.class,
                    () -> telemetry.ingest("dev-cap-tel", "sess-cap",
                            new com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryPoint(
                                    "dev-cap-tel", 4, t.plusMillis(4),
                                    Map.of("temperature", 21.0))));
            log.info("[判定依据] 遥测历史超限 code={} size={}",
                    ex.code(), telemetry.history("dev-cap-tel").size());
            assertEquals(ErrorCode.CAPACITY_EXCEEDED, ex.code());
            assertEquals(3, telemetry.history("dev-cap-tel").size());
        } finally {
            properties.setMaxTelemetryPerDevice(saved);
        }
    }

    @Test
    void deviceQuotaRejectsBeyondMaxDevices() {
        int saved = properties.getMaxDevices();
        int existingCount = Integer.MAX_VALUE;
        // 把容量压到“当前已登记数量”，下一次登记必被拒
        long registered = 0;
        try {
            registry.get("dev-over-quota-new");
        } catch (GatewayException expected) {
            // 尚不存在
        }
        // 通过登记一个探针设备确定容量边界：直接把上限设为 0
        properties.setMaxDevices(0);
        try {
            GatewayException ex = assertThrows(GatewayException.class,
                    () -> registry.register("dev-over-quota-new",
                            SimulatorService.MODEL, "HW", "1.0.0"));
            log.info("[判定依据] 设备配额超限 code={} msg={}", ex.code(), ex.getMessage());
            assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.code());
            assertThrows(GatewayException.class,
                    () -> registry.get("dev-over-quota-new"));
        } finally {
            properties.setMaxDevices(saved);
        }
    }
}
