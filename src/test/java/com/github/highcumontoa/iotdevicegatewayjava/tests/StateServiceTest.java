package com.github.highcumontoa.iotdevicegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.SettableClock;
import com.github.highcumontoa.iotdevicegatewayjava.domain.ConvergenceView;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DesiredEntry;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DesiredRevision;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DesiredStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.ReportedState;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 期望状态：版本冲突拒绝、并发下发不丢更新、设备回报收敛/拒绝、交叉执行一致性。
 */
class StateServiceTest {

    private static final Logger log = LoggerFactory.getLogger(StateServiceTest.class);

    private SettableClock clock;
    private StateService state;
    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new SettableClock(1_000L);
        GatewayProperties props = new GatewayProperties();
        CapacityMetrics metrics = new CapacityMetrics();
        registry = new DeviceRegistry(clock, props, metrics);
        state = new StateService(registry, clock, props);
        registry.register("dev", "sensor-1");
        registry.activate("dev");
    }

    private ReportedState report(Map<String, Object> values) {
        return new ReportedState("dev", values, Set.of(), Map.of(), clock.nowMillis());
    }

    @Test
    void versionConflictRejectsAndNoUpdateIsLost() throws Exception {
        DesiredRevision r1 = state.applyDesired("dev", Map.of("power", "ON"), 0L, "init");
        assertEquals(1L, r1.revision());

        // 基于旧版本(0)的并发下发必须被拒绝
        GatewayException conflict = assertThrows(GatewayException.class,
                () -> state.applyDesired("dev", Map.of("power", "OFF"), 0L, "stale"));
        assertEquals(ErrorCode.STATE_VERSION_CONFLICT, conflict.errorCode());
        log.info("版本冲突 code={} msg={}", conflict.errorCode(), conflict.getMessage());

        // 并发：N 个线程都基于相同旧版本，只允许恰好一个成功，其余冲突，且最终版本严格连续
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Set<Long> createdRevisions = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    DesiredRevision r = state.applyDesired("dev",
                            Map.of("k" + idx, idx), 1L, "concurrent-" + idx);
                    success.incrementAndGet();
                    createdRevisions.add(r.revision());
                } catch (GatewayException ex) {
                    if (ex.errorCode() == ErrorCode.STATE_VERSION_CONFLICT) {
                        conflicts.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, success.get(), "同一基础版本只允许一次下发成功");
        assertEquals(threads - 1, conflicts.get());
        // 因为其余 15 个都失败了，最终版本只前进 1，且没有丢失更新（每个失败调用都没写任何键）
        ConvergenceView view = state.getConvergence("dev");
        assertEquals(2L, view.currentRevision());
        assertEquals(1, createdRevisions.size());
        assertTrue(createdRevisions.contains(2L));
        // 首个成功键存在；其余竞争失败键绝不能残留（半更新检查）
        boolean exactlyOneNewKey = view.desiredEntries().keySet().stream()
                .filter(k -> k.startsWith("k")).count() == 1;
        assertTrue(exactlyOneNewKey, "竞争失败的下发不得残留任何键");
        log.info("并发下发 success=1 conflicts={} finalRevision=2 竞争失败无残留", conflicts.get());
    }

    @Test
    void sequentialRevisionsConvergeAndSupersede() {
        state.applyDesired("dev", Map.of("power", "ON"), 0L, "v1");
        ConvergenceView afterReport = state.reportState(report(Map.of("power", "ON")));
        DesiredEntry power = afterReport.desiredEntries().get("power");
        assertEquals(DesiredStatus.SYNCED, power.status());
        assertTrue(afterReport.allConverged());

        // 新版本期望覆盖旧键：旧条目 SUPERSEDED，新条目 PENDING
        DesiredRevision r2 = state.applyDesired("dev", Map.of("power", "OFF"), 1L, "v2");
        ConvergenceView pending = state.getConvergence("dev");
        assertEquals(2L, r2.revision());
        assertEquals(DesiredStatus.PENDING, pending.desiredEntries().get("power").status());
        assertFalse(pending.allConverged());

        state.reportState(report(Map.of("power", "OFF")));
        ConvergenceView synced = state.getConvergence("dev");
        assertEquals(DesiredStatus.SYNCED, synced.desiredEntries().get("power").status());
        assertTrue(synced.allConverged());
        assertEquals(2, state.listRevisions("dev").size());
        log.info("版本化收敛: v1 SYNCED->SUPERSEDED, v2 PENDING->SYNCED 历史版本数=2");
    }

    @Test
    void unsatisfiableDesiredExplicitlyRejectedWithReason() {
        state.applyDesired("dev", Map.of("mode", "turbo"), 0L, "ask");
        ReportedState rejection = new ReportedState("dev",
                Map.of("mode", "eco"), Set.of("mode"),
                Map.of("mode", "硬件不支持 turbo 模式"), clock.nowMillis());
        ConvergenceView view = state.reportState(rejection);
        DesiredEntry entry = view.desiredEntries().get("mode");
        assertEquals(DesiredStatus.REJECTED, entry.status());
        assertTrue(entry.reason().contains("turbo"));
        assertFalse(view.allConverged());
        log.info("无法满足的期望: status={} reason={}", entry.status(), entry.reason());
    }

    @Test
    void concurrentDesiredAndReportNeverHalfUpdates() throws Exception {
        // 下发与回报在不同键上交叉：最终要么 PENDING（设备尚未回报该值）要么 SYNCED，绝不出现脏状态
        state.applyDesired("dev", Map.of("a", 1), 0L, "init");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        for (int round = 0; round < 20; round++) {
            final long base = round + 1L;
            pool.submit(() -> {
                try {
                    start.await();
                    state.applyDesired("dev", Map.of("a", (int) base), base - 1, "r" + base);
                } catch (GatewayException | InterruptedException ignored) {
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    state.reportState(report(Map.of("a", (int) base)));
                } catch (GatewayException | InterruptedException ignored) {
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        ConvergenceView view = state.getConvergence("dev");
        DesiredEntry a = view.desiredEntries().get("a");
        assertTrue(a.status() == DesiredStatus.PENDING || a.status() == DesiredStatus.SYNCED,
                "只允许确定的收敛状态，实际=" + a.status());
        log.info("下发/回报交叉结束: revision={} a.status={} value={} reported={}",
                view.currentRevision(), a.status(), a.value(), view.reported().get("a"));
    }
}
