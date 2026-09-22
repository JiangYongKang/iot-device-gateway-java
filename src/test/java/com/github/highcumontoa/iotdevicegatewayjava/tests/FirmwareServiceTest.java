package com.github.highcumontoa.iotdevicegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.SettableClock;
import com.github.highcumontoa.iotdevicegatewayjava.domain.FirmwareInfo;
import com.github.highcumontoa.iotdevicegatewayjava.domain.UpgradeRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.UpgradeStatus;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 固件升级：兼容判定、禁止下发、幂等、并发单进行中、失败回滚、失联超时回滚、分批独立判定。
 */
class FirmwareServiceTest {

    private static final Logger log = LoggerFactory.getLogger(FirmwareServiceTest.class);

    private SettableClock clock;
    private GatewayProperties props;
    private DeviceRegistry registry;
    private FirmwareService firmware;

    @BeforeEach
    void setUp() {
        clock = new SettableClock(10_000L);
        props = new GatewayProperties();
        props.getFirmware().setUpgradeTimeoutMillis(60_000L);
        props.getFirmware().setRollbackOnFailure(true);
        CapacityMetrics metrics = new CapacityMetrics();
        registry = new DeviceRegistry(clock, props, metrics);
        SessionManager sessions = new SessionManager(registry, clock, props, metrics);
        firmware = new FirmwareService(registry, sessions, clock, props);
        // 目录：v2 仅允许从 v1 升级；v3-blocked 被禁止
        firmware.addFirmware(new FirmwareInfo("1.0", "sensor-1", null, false));
        firmware.addFirmware(new FirmwareInfo("2.0", "sensor-1", Set.of("1.0"), false));
        firmware.addFirmware(new FirmwareInfo("2.1", "sensor-1", Set.of("1.0"), false));
        firmware.addFirmware(new FirmwareInfo("3.0", "sensor-1", Set.of("2.0"), true));
    }

    private String readyDevice(String id, String currentVersion) {
        registry.register(id, "sensor-1");
        registry.activate(id);
        if (currentVersion != null) {
            registry.updateFirmwareVersion(id, currentVersion);
        }
        return id;
    }

    @Test
    void compatibilityAndBlockedAreRejectedWithDistinctCodes() {
        String d = readyDevice("dev", "1.0");

        // 越级升级不兼容：1.0 -> 3.0（且 3.0 被 blocked）—— blocked 优先判定
        GatewayException blocked = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade(d, "3.0", null));
        assertEquals(ErrorCode.FIRMWARE_VERSION_NOT_ALLOWED, blocked.errorCode());

        // 1.0 -> 2.1 允许（compatibleFrom 包含 1.0）
        UpgradeRecord ok = firmware.startUpgrade(d, "2.1", null);
        assertEquals(UpgradeStatus.IN_PROGRESS, ok.status());
        firmware.completeUpgrade(ok.upgradeId(), true, null);
        assertEquals("2.1", registry.get(d).firmwareVersion());

        // 2.1 -> 2.0：compatibleFrom 仅 1.0，不兼容
        GatewayException incompatible = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade(d, "2.0", null));
        assertEquals(ErrorCode.FIRMWARE_INCOMPATIBLE, incompatible.errorCode());
        log.info("禁止下发 code={} 不兼容 code={}", blocked.errorCode(), incompatible.errorCode());

        // 未知固件
        String d2 = readyDevice("dev2", "1.0");
        GatewayException notFound = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade(d2, "9.9", null));
        assertEquals(ErrorCode.FIRMWARE_NOT_FOUND, notFound.errorCode());

        // 已是目标版本
        String d3 = readyDevice("dev3", "2.0");
        // 需要把 2.0 放到进行中前先允许：直接校验同版本
        GatewayException same = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade(d3, "2.0", null));
        assertEquals(ErrorCode.VALIDATION_FAILED, same.errorCode());
    }

    @Test
    void idempotentAndSingleInProgressUnderConcurrency() throws Exception {
        String d = readyDevice("dev", "1.0");
        UpgradeRecord first = firmware.startUpgrade(d, "2.0", "idem-key-1");

        // 同幂等键重复触发：返回同一条
        UpgradeRecord again = firmware.startUpgrade(d, "2.0", "idem-key-1");
        assertEquals(first.upgradeId(), again.upgradeId());
        log.info("幂等触发返回同一升级: {}", first.upgradeId());

        // 不同请求在进行中必须被拒
        GatewayException busy = assertThrows(GatewayException.class,
                () -> firmware.startUpgrade(d, "2.1", "other-key"));
        assertEquals(ErrorCode.FIRMWARE_UPGRADE_IN_PROGRESS, busy.errorCode());

        // 并发重复触发（不同幂等键）仍然只有一个进行中
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int i2 = i;
            pool.submit(() -> {
                try {
                    start.await();
                    firmware.startUpgrade(d, "2.1", "race-" + i2);
                } catch (GatewayException ex) {
                    if (ex.errorCode() == ErrorCode.FIRMWARE_UPGRADE_IN_PROGRESS) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(threads, rejected.get());
        assertEquals(1, firmware.listUpgrades(d).size());
        log.info("并发触发进行中拒绝数={} 进行中升级数=1", rejected.get());
    }

    @Test
    void failedUpgradeRollsBackAndKeepsTrace() {
        String d = readyDevice("dev", "1.0");
        UpgradeRecord up = firmware.startUpgrade(d, "2.0", null);
        UpgradeRecord failed = firmware.completeUpgrade(up.upgradeId(), false, "刷写校验失败");
        assertEquals(UpgradeStatus.FAILED, failed.status());
        assertEquals("1.0", failed.rolledBackToVersion());
        assertEquals("1.0", registry.get(d).firmwareVersion(), "失败后档案必须回到旧版本");
        assertTrue(failed.reason().contains("回滚"));
        // 失败后允许重新发起
        UpgradeRecord retry = firmware.startUpgrade(d, "2.0", null);
        assertNotEquals(up.upgradeId(), retry.upgradeId());
        log.info("失败回滚 status={} rollbackTo={} 历史记录保留", failed.status(), failed.rolledBackToVersion());
    }

    @Test
    void lostDeviceTimeoutRollsBack() {
        String d = readyDevice("dev", "1.0");
        UpgradeRecord up = firmware.startUpgrade(d, "2.0", null);
        clock.advanceMillis(60_001L);
        List<UpgradeRecord> timedOut = firmware.sweepTimeouts();
        assertEquals(1, timedOut.size());
        assertEquals(UpgradeStatus.TIMED_OUT, timedOut.get(0).status());
        assertEquals("1.0", registry.get(d).firmwareVersion());
        // 迟到的设备成功回报不得再改变结论（已终结）
        UpgradeRecord late = firmware.completeUpgrade(up.upgradeId(), true, "late");
        assertEquals(UpgradeStatus.TIMED_OUT, late.status());
        assertEquals("1.0", registry.get(d).firmwareVersion(), "迟到回报不得覆盖回滚结论");
        assertEquals("1.0", late.rolledBackToVersion());
        log.info("失联超时 status=TIMED_OUT rollback=1.0 迟到回报不翻案");
    }

    @Test
    void batchUpgradeIsPerDeviceIndependent() {
        String ok = readyDevice("d1", "1.0");
        String incompatible = readyDevice("d2", "2.0"); // 2.0 -> 2.1 不兼容
        String unregistered = "ghost";
        List<FirmwareService.UpgradeResult> results =
                firmware.startBatch(List.of(ok, incompatible, unregistered), "2.1", "batch-1");
        assertEquals(3, results.size());
        assertEquals(1, results.stream().filter(FirmwareService.UpgradeResult::success).count());
        assertEquals(ErrorCode.FIRMWARE_INCOMPATIBLE,
                results.stream().filter(r -> r.deviceId().equals("d2")).findFirst().orElseThrow()
                        .failure().errorCode());
        assertEquals(ErrorCode.DEVICE_NOT_FOUND,
                results.stream().filter(r -> r.deviceId().equals("ghost")).findFirst().orElseThrow()
                        .failure().errorCode());
        // 重复批量触发幂等：d1 返回同一升级
        List<FirmwareService.UpgradeResult> repeat =
                firmware.startBatch(List.of(ok), "2.1", "batch-1");
        assertEquals(results.get(0).record().upgradeId(), repeat.get(0).record().upgradeId());
        log.info("分批升级: 成功=1 d2=INCOMPATIBLE ghost=NOT_FOUND 重复触发幂等");
    }
}
