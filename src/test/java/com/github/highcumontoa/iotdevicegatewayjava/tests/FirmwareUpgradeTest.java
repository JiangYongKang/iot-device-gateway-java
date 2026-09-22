package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import com.github.highcumontoa.iotdevicegatewayjava.model.BatchUpgradeResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.UpgradeRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.UpgradeStatus;
import com.github.highcumontoa.iotdevicegatewayjava.registry.DeviceRegistryService;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatedDevice;
import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class FirmwareUpgradeTest {

    private static final Logger log = LoggerFactory.getLogger(FirmwareUpgradeTest.class);

    @Autowired FirmwareService firmware;
    @Autowired SimulatorService simulator;
    @Autowired DeviceRegistryService registry;
    @Autowired SessionManager sessions;

    @BeforeEach
    void setUp() {
        simulator.installModelSchema();
        simulator.provisionDevice("dev-fw-ok", "1.0.0", true);
        simulator.provisionDevice("dev-fw-old", "0.9.0", true);
        simulator.provisionDevice("dev-fw-same", "2.0.0", true);
        simulator.provisionDevice("dev-fw-fail", "1.0.0", true);
        simulator.provisionDevice("dev-fw-off", "1.0.0", true);
        simulator.provisionDevice("dev-fw-b1", "1.0.0", true);
        simulator.provisionDevice("dev-fw-b2", "0.5.0", true);
        // 允许从 1.x 升到 2.0.0，区间 [1.0.0, 2.0.0)
        simulator.publishFirmware("2.0.0", "1.0.0", "2.0.0");
    }

    @Test
    void incompatibleVersionsAreRejectedWithDistinctReasons() {
        GatewayException tooOld = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade("dev-fw-old", "2.0.0", null));
        log.info("[判定依据] 版本过低 code={} msg={}", tooOld.code(), tooOld.getMessage());
        assertEquals(ErrorCode.FIRMWARE_INCOMPATIBLE, tooOld.code());

        GatewayException same = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade("dev-fw-same", "2.0.0", null));
        log.info("[判定依据] 同版本 code={} msg={}", same.code(), same.getMessage());
        assertEquals(ErrorCode.FIRMWARE_INCOMPATIBLE, same.code());

        GatewayException missing = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade("dev-fw-ok", "9.9.9", null));
        log.info("[判定依据] 固件不存在 code={}", missing.code());
        assertEquals(ErrorCode.FIRMWARE_INCOMPATIBLE, missing.code());
    }

    @Test
    void upgradeIsIdempotentAndSingleFlightAndSuccessSwitchesVersion() {
        String deviceId = "dev-fw-ok";
        SimulatedDevice device = simulator.device(deviceId);
        device.connect(simulator.newSessionId());

        UpgradeRecord first = firmware.startUpgrade(deviceId, "2.0.0", "key-1");
        UpgradeRecord again = firmware.startUpgrade(deviceId, "2.0.0", "key-1");
        log.info("[判定依据] 幂等键命中 first={} again={}",
                first.upgradeId(), again.upgradeId());
        assertEquals(first.upgradeId(), again.upgradeId());

        // 无幂等键的重复触发 -> 明确的重复请求
        GatewayException duplicate = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade(deviceId, "2.0.0", null));
        assertEquals(ErrorCode.FIRMWARE_DUPLICATE_REQUEST, duplicate.code());

        // 不同目标 -> 单飞冲突
        simulator.publishFirmware("1.5.0", "1.0.0", "2.0.0");
        GatewayException busy = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade(deviceId, "1.5.0", null));
        log.info("[判定依据] 单飞冲突 code={}", busy.code());
        assertEquals(ErrorCode.FIRMWARE_ALREADY_IN_PROGRESS, busy.code());

        device.reportUpgrade(first.upgradeId(), "DOWNLOADING", "下载中");
        device.reportUpgrade(first.upgradeId(), "INSTALLING", "安装中");
        UpgradeRecord done = device.reportUpgrade(first.upgradeId(), "SUCCEEDED", "完成");
        log.info("[判定依据] 升级成功 status={} 当前版本={}",
                done.status(), registry.get(deviceId).firmwareVersion());
        assertEquals(UpgradeStatus.SUCCEEDED, done.status());
        assertEquals("2.0.0", registry.get(deviceId).firmwareVersion());
    }

    @Test
    void failureAndOfflineTriggerRollbackWithTrace() {
        // 失败回报 -> ROLLED_BACK，版本保持 1.0.0
        SimulatedDevice failDevice = simulator.device("dev-fw-fail");
        failDevice.connect(simulator.newSessionId());
        UpgradeRecord upg = firmware.startUpgrade("dev-fw-fail", "2.0.0", null);
        UpgradeRecord failed = failDevice.reportUpgrade(upg.upgradeId(), "FAILED", "校验和错误");
        log.info("[判定依据] 升级失败最终 status={} detail={} version={}",
                failed.status(), failed.detail(), registry.get("dev-fw-fail").firmwareVersion());
        assertEquals(UpgradeStatus.ROLLED_BACK, failed.status());
        assertEquals("1.0.0", registry.get("dev-fw-fail").firmwareVersion());
        assertTrue(failed.detail().contains("校验和错误"));

        // 升级中断线 -> 失联回滚
        SimulatedDevice offDevice = simulator.device("dev-fw-off");
        offDevice.connect(simulator.newSessionId());
        UpgradeRecord upg2 = firmware.startUpgrade("dev-fw-off", "2.0.0", null);
        offDevice.disconnect();
        List<UpgradeRecord> history = firmware.history("dev-fw-off");
        UpgradeRecord last = history.get(history.size() - 1);
        log.info("[判定依据] 失联后升级单 status={} detail={}", last.status(), last.detail());
        assertEquals(upg2.upgradeId(), last.upgradeId());
        assertEquals(UpgradeStatus.ROLLED_BACK, last.status());
        assertEquals("OFFLINE", sessions.state("dev-fw-off").name());
    }

    @Test
    void batchUpgradeIsolatesPerDeviceRejection() {
        BatchUpgradeResult result = firmware.startBatch(
                List.of("dev-fw-b1", "dev-fw-b2", "ghost-device"),
                "2.0.0", "batch-1");
        log.info("[判定依据] 分批结果 accepted={} rejected={}",
                result.accepted().keySet(), result.rejected().keySet());
        assertEquals(1, result.accepted().size(), "仅兼容设备接受");
        assertTrue(result.accepted().containsKey("dev-fw-b1"));
        assertEquals(2, result.rejected().size(), "不兼容与未登记分别标记，不拖垮整批");
        assertTrue(result.rejected().get("dev-fw-b2").contains("FIRMWARE_INCOMPATIBLE"));
        assertTrue(result.rejected().get("ghost-device").contains("DEVICE_NOT_FOUND"));

        // 分批幂等：重复批次，已接受设备返回原单
        BatchUpgradeResult retry = firmware.startBatch(
                List.of("dev-fw-b1"), "2.0.0", "batch-1");
        assertEquals(1, retry.accepted().size());
    }
}
