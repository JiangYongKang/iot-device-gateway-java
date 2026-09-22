package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryPoint;
import com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryRejectReason;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatedDevice;
import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatorService;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TelemetryConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsistencyTest.class);

    @Autowired TelemetryService telemetry;
    @Autowired SessionManager sessions;
    @Autowired SimulatorService simulator;

    private SimulatedDevice device;

    @BeforeEach
    void setUp() {
        simulator.installModelSchema();
        simulator.provisionDevice("dev-tel", "1.0.0", true);
        simulator.provisionDevice("dev-tel-race", "1.0.0", true);
        device = simulator.device("dev-tel");
        device.connect(simulator.newSessionId());
    }

    private Map<String, Object> temp(double t) {
        return Map.of("temperature", t);
    }

    @Test
    void duplicateAndOutOfOrderAreRejectedAndNotCountedTwice() {
        Instant t0 = Instant.ofEpochMilli(1_000_000);
        device.sendTelemetryRaw(1, t0, temp(25.0));
        device.sendTelemetryRaw(2, t0.plusMillis(100), temp(26.0));

        // 完全重复（同 seq）
        GatewayException dup = assertThrows(GatewayException.class,
                () -> device.sendTelemetryRaw(1, t0, temp(25.0)));
        log.info("[判定依据] 重复上报 code={}", dup.code());
        assertEquals(ErrorCode.TELEMETRY_DUPLICATE, dup.code());

        // seq 更大但时间戳更早（乱序/时钟回拨）
        GatewayException stale = assertThrows(GatewayException.class,
                () -> device.sendTelemetryRaw(3, t0, temp(24.0)));
        log.info("[判定依据] 时钟回拨 code={} ts={} < lastTs={}",
                stale.code(), t0, t0.plusMillis(100));
        assertEquals(ErrorCode.TELEMETRY_STALE, stale.code());

        assertEquals(2, telemetry.history("dev-tel").size(),
                "重复与乱序均不得写入、不得重复计数");
    }

    @Test
    void invalidRecordsAreRejectedWithDistinctReasons() {
        Instant t = Instant.ofEpochMilli(2_000_000);

        // 缺失必填
        GatewayException missing = assertThrows(GatewayException.class,
                () -> telemetry.ingest("dev-tel", device.sessionId(),
                        new TelemetryPoint("dev-tel", 10, t, Map.of("humidity", 50.0))));
        assertEquals(ErrorCode.TELEMETRY_VALIDATION_FAILED, missing.code());
        log.info("[判定依据] 缺字段 message={}", missing.getMessage());

        // 类型不匹配
        GatewayException type = assertThrows(GatewayException.class,
                () -> telemetry.ingest("dev-tel", device.sessionId(),
                        new TelemetryPoint("dev-tel", 11, t, Map.of("temperature", "hot"))));
        log.info("[判定依据] 类型错误 message={}", type.getMessage());

        // 超出范围
        GatewayException range = assertThrows(GatewayException.class,
                () -> telemetry.ingest("dev-tel", device.sessionId(),
                        new TelemetryPoint("dev-tel", 12, t, temp(999.0))));
        log.info("[判定依据] 超范围 message={}", range.getMessage());

        // 未定义字段
        GatewayException unknown = assertThrows(GatewayException.class,
                () -> telemetry.ingest("dev-tel", device.sessionId(),
                        new TelemetryPoint("dev-tel", 13, t,
                                Map.of("temperature", 25.0, "ghost", 1))));
        log.info("[判定依据] 未知字段 message={}", unknown.getMessage());

        assertTrue(telemetry.rejections("dev-tel").size() >= 3,
                "拒绝原因应被留存可追溯");
        assertEquals(0, telemetry.history("dev-tel").size(),
                "任何非法记录都不得静默写入");
        var reasons = telemetry.rejections("dev-tel").stream()
                .map(r -> r.reason()).distinct().toList();
        log.info("[判定依据] 已区分拒绝原因集合={}", reasons);
        assertTrue(reasons.contains(TelemetryRejectReason.MISSING_FIELD));
        assertTrue(reasons.contains(TelemetryRejectReason.TYPE_MISMATCH));
        assertTrue(reasons.contains(TelemetryRejectReason.OUT_OF_RANGE));
    }

    @Test
    void concurrentIngestsNeverDuplicateOrRegressSeq() throws Exception {
        SimulatedDevice raceDevice = simulator.device("dev-tel-race");
        raceDevice.connect(simulator.newSessionId());
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        // 全部使用同一时间戳：专门压测“同刻并发、唯一 seq”的去重正确性；
        // 时间戳乱序/回拨语义已在 duplicateAndOutOfOrderAreRejectedAndNotCountedTwice 覆盖。
        Instant ts = Instant.ofEpochMilli(5_000_000);
        for (int i = 1; i <= threads; i++) {
            long seq = i;
            pool.submit(() -> {
                try {
                    start.await();
                    telemetry.ingest("dev-tel-race", raceDevice.sessionId(),
                            new TelemetryPoint("dev-tel-race", seq, ts, temp(20.0 + seq)));
                    accepted.incrementAndGet();
                } catch (GatewayException e) {
                    if (e.code() == ErrorCode.TELEMETRY_DUPLICATE) {
                        duplicates.incrementAndGet();
                    }
                    log.warn("并发遥测拒绝 seq={} code={}", seq, e.code());
                } catch (Exception e) {
                    log.error("并发遥测异常 seq={}", seq, e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("[判定依据] 并发遥测 accepted={} duplicates={} historySize={}",
                accepted.get(), duplicates.get(), telemetry.history("dev-tel-race").size());
        // 唯一 seq 1..20 并发提交，全部应成功（无重复拒绝）且历史恰好 20 条
        assertEquals(threads, accepted.get());
        assertEquals(0, duplicates.get());
        assertEquals(threads, telemetry.history("dev-tel-race").size());
        long maxSeq = telemetry.history("dev-tel-race").stream()
                .mapToLong(TelemetryPoint::seq).max().orElse(0);
        assertEquals(threads, maxSeq, "已接受最大序号不回退");
        long distinct = telemetry.history("dev-tel-race").stream()
                .map(TelemetryPoint::seq).distinct().count();
        assertEquals(threads, distinct, "不存在重复序号");
    }
}
