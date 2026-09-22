package com.github.highcumontoa.iotdevicegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.SettableClock;
import com.github.highcumontoa.iotdevicegatewayjava.domain.MetricSchema;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 遥测并发与边界：并发递增写入不重不漏；同帧重复（相同序列号）始终只计一次。
 */
class TelemetryConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConcurrencyTest.class);

    private SettableClock clock;
    private TelemetryService telemetry;
    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new SettableClock(1_000L);
        GatewayProperties props = new GatewayProperties();
        CapacityMetrics metrics = new CapacityMetrics();
        registry = new DeviceRegistry(clock, props, metrics);
        telemetry = new TelemetryService(registry, clock, props);
        registry.register("dev", "sensor-1");
        registry.activate("dev");
        telemetry.registerSchema(MetricSchema.number("temp", true, -40.0, 85.0));
    }

    @Test
    void concurrentIngestWithUniqueSequencesAcceptsExactlyOnceEach() throws Exception {
        int writers = 8;
        int per = 100;
        // 全局单调序号在多线程下并发提交：取号顺序可能不同于持锁顺序（真实乱序到达）
        java.util.concurrent.atomic.AtomicLong order = new java.util.concurrent.atomic.AtomicLong(0L);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Map<ErrorCode, Integer> rejectionCodes = new ConcurrentHashMap<>();
        Map<Long, Boolean> acceptedSequences = new ConcurrentHashMap<>();
        for (int w = 0; w < writers; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < per; i++) {
                        long seq = order.incrementAndGet();
                        try {
                            telemetry.ingest(new com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryPoint(
                                    "dev", seq, 10_000L + seq,
                                    Map.of("temp", 20.0 + (seq % 50))));
                            accepted.incrementAndGet();
                            acceptedSequences.put(seq, Boolean.TRUE);
                        } catch (GatewayException ex) {
                            // 乱序/回拨是允许的确定结论；关键是任何序列号绝不被接受两次
                            rejectionCodes.merge(ex.errorCode(), 1, Integer::sum);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        // 不重不漏：接受 + 被确定拒绝 = 总提交
        int rejectedTotal = rejectionCodes.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(writers * per, accepted.get() + rejectedTotal);
        // 被拒绝只允许是乱序类结论（无系统错误、无重复计数）
        rejectionCodes.keySet().forEach(code -> assertTrue(
                code == ErrorCode.TELEMETRY_STALE_TIMESTAMP || code == ErrorCode.TELEMETRY_CLOCK_ROLLBACK,
                "并发提交只应出现乱序类拒绝，实际=" + code));
        assertEquals(accepted.get(), acceptedSequences.size());
        assertEquals(accepted.get(), telemetry.acceptedCount("dev"));
        log.info("并发遥测 accepted={} rejected={} codes={} 序列号无重复计数 总数守恒",
                accepted.get(), rejectedTotal, rejectionCodes);
    }

    @Test
    void sameFrameReplayedIsDuplicate() {
        telemetry.ingest(new com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryPoint(
                "dev", 7L, 5_000L, Map.of("temp", 20.0)));
        // 完全重复（含相同时间戳）
        GatewayException dup1 = org.junit.jupiter.api.Assertions.assertThrows(GatewayException.class,
                () -> telemetry.ingest(new com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryPoint(
                        "dev", 7L, 5_000L, Map.of("temp", 20.5))));
        assertEquals(ErrorCode.TELEMETRY_DUPLICATE, dup1.errorCode());
        // 相同序列号但时间戳更新：仍是重复帧（序列号是设备侧幂等键）
        GatewayException dup2 = org.junit.jupiter.api.Assertions.assertThrows(GatewayException.class,
                () -> telemetry.ingest(new com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryPoint(
                        "dev", 7L, 6_000L, Map.of("temp", 20.9))));
        assertEquals(ErrorCode.TELEMETRY_DUPLICATE, dup2.errorCode());
        assertEquals(1, telemetry.acceptedCount("dev"));
        assertEquals(20.0, telemetry.latest("dev").get("temp"), "重复帧不得改变最新值");
        log.info("相同序列号（时间戳相同或更新）均判定 DUPLICATE，计数=1 latest.temp=20.0");
    }
}
