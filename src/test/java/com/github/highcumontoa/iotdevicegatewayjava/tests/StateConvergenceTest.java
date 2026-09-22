package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConvergenceStatus;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConvergenceView;
import com.github.highcumontoa.iotdevicegatewayjava.model.StateVersion;
import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatedDevice;
import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatorService;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
class StateConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(StateConvergenceTest.class);

    @Autowired StateService state;
    @Autowired SimulatorService simulator;

    private SimulatedDevice device;

    @BeforeEach
    void setUp() {
        simulator.installModelSchema();
        simulator.provisionDevice("dev-state", "1.0.0", true);
        simulator.provisionDevice("dev-state-cas", "1.0.0", true);
        device = simulator.device("dev-state");
        device.connect(simulator.newSessionId());
    }

    @Test
    void desiredValidatesAtomicallyAndConvergesOrRejects() {
        // 整批非法（只读字段 + 超范围）：不得半更新
        GatewayException rejected = assertThrows(GatewayException.class,
                () -> state.updateDesired("dev-state", 0,
                        Map.of("targetTemp", 999.0, "serialNo", "X")));
        log.info("[判定依据] 期望整批拒绝 code={} msg={}", rejected.code(), rejected.getMessage());
        assertEquals(ErrorCode.DESIRED_REJECTED, rejected.code());
        assertEquals(0, state.snapshot("dev-state").desiredVersion(), "非法期望不得产生版本推进");
        assertTrue(state.snapshot("dev-state").desired().isEmpty());

        // 合法期望 -> 版本 1
        StateVersion v1 = state.updateDesired("dev-state", 0, Map.of("targetTemp", 22.5));
        assertEquals(1, v1.desiredVersion());
        ConvergenceView pending = state.convergence("dev-state");
        log.info("[判定依据] 下发后未回报 overall={} reason={}",
                pending.overall(), pending.fieldReasons().get("targetTemp"));
        assertEquals(ConvergenceStatus.PENDING, pending.overall());

        // 设备回报一致 -> MATCHED
        device.reportState(Map.of("targetTemp", 22.5));
        ConvergenceView matched = state.convergence("dev-state");
        log.info("[判定依据] 回报一致后 overall={} reason={}",
                matched.overall(), matched.fieldReasons().get("targetTemp"));
        assertEquals(ConvergenceStatus.MATCHED, matched.overall());

        // 新期望，设备回报无法满足 -> REJECTED 且可解释
        state.updateDesired("dev-state", 1, Map.of("targetTemp", 50.0));
        device.reportStateRejected(Map.of("targetTemp", "硬件无法达到 50.0"));
        ConvergenceView rej = state.convergence("dev-state");
        log.info("[判定依据] 设备拒绝后 overall={} reason={}",
                rej.overall(), rej.fieldReasons().get("targetTemp"));
        assertEquals(ConvergenceStatus.REJECTED, rej.overall());
        assertTrue(rej.fieldReasons().get("targetTemp").contains("硬件无法达到"));
    }

    @Test
    void concurrentDesiredUpdatesDoNotLoseUpdate() throws Exception {
        String deviceId = "dev-state-cas";
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final double temp = 20.0 + i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 每个线程都以 0 为期望版本强写：只允许一个成功，其余明确冲突，
                    // 不存在两个都成功（丢失更新）的情况。
                    state.updateDesired(deviceId, 0, Map.of("targetTemp", temp));
                    success.incrementAndGet();
                } catch (GatewayException e) {
                    if (e.code() == ErrorCode.STATE_VERSION_CONFLICT) {
                        conflicts.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("[判定依据] 并发同版本下发 success={} conflicts={} finalVersion={}",
                success.get(), conflicts.get(), state.snapshot(deviceId).desiredVersion());
        assertEquals(1, success.get(), "恰好一个更新成功");
        assertEquals(threads - 1, conflicts.get(), "其余全部版本冲突");
        assertEquals(1, state.snapshot(deviceId).desiredVersion(), "版本只推进一次，无丢失更新");

        // 冲突方读取最新版本重试 -> 成功推进到版本 2，最终值收敛为重试值
        long current = state.snapshot(deviceId).desiredVersion();
        StateVersion retry = state.updateDesired(deviceId, current, Map.of("targetTemp", 30.0));
        assertEquals(2, retry.desiredVersion());
        assertEquals(30.0, state.snapshot(deviceId).desired().get("targetTemp"));
    }
}
