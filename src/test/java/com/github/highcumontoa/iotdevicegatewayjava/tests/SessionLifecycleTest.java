package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConnectResult;
import com.github.highcumontoa.iotdevicegatewayjava.registry.DeviceRegistryService;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SessionLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(SessionLifecycleTest.class);

    @Autowired SessionManager sessions;
    @Autowired DeviceRegistryService registry;
    @Autowired SimulatorService simulator;

    @BeforeEach
    void setUp() {
        simulator.installModelSchema();
        simulator.provisionDevice("dev-on", "1.0.0", true);
        simulator.provisionDevice("dev-takeover", "1.0.0", true);
        simulator.provisionDevice("dev-race", "1.0.0", true);
        simulator.provisionDevice("dev-registered", "1.0.0", false);
    }

    @Test
    void rejectsUnregisteredAndInactiveWithDistinctReasons() {
        GatewayException notFound = assertThrows(GatewayException.class,
                () -> sessions.connect("ghost", "s1"));
        log.info("[判定依据] 未登记设备接入 code={}", notFound.code());
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, notFound.code());

        GatewayException notActive = assertThrows(GatewayException.class,
                () -> sessions.connect("dev-registered", "s1"));
        log.info("[判定依据] 未激活设备接入 code={}", notActive.code());
        assertEquals(ErrorCode.DEVICE_NOT_ACTIVATED, notActive.code());

        registry.disable("dev-on");
        GatewayException disabled = assertThrows(GatewayException.class,
                () -> sessions.connect("dev-on", "s9"));
        log.info("[判定依据] 已停用设备接入 code={}", disabled.code());
        assertEquals(ErrorCode.DEVICE_NOT_ACTIVATED, disabled.code());
    }

    @Test
    void sameSessionIsIdempotentAndTakeoverInvalidatesOldSession() {
        ConnectResult first = sessions.connect("dev-takeover", "sess-A");
        log.info("[关键输入] 首次接入 session=sess-A takenOver={}", first.takenOver());
        assertFalse(first.takenOver());

        ConnectResult retry = sessions.connect("dev-takeover", "sess-A");
        log.info("[判定依据] 相同 session 重入 reused={} generation={}",
                retry.reused(), retry.session().generation());
        assertTrue(retry.reused());
        assertEquals(first.session().generation(), retry.session().generation());

        ConnectResult takenOver = sessions.connect("dev-takeover", "sess-B");
        log.info("[判定依据] 新 session 接管 takenOver={} newGeneration={}",
                takenOver.takenOver(), takenOver.session().generation());
        assertTrue(takenOver.takenOver());

        GatewayException stale = assertThrows(GatewayException.class,
                () -> sessions.requireValid("dev-takeover", "sess-A"));
        log.info("[判定依据] 旧会话操作 code={}", stale.code());
        assertEquals(ErrorCode.SESSION_STALE, stale.code());
        sessions.requireValid("dev-takeover", "sess-B");
    }

    @Test
    void concurrentConnectsYieldExactlyOneActiveSession() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> winners = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            String sid = "sess-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ConnectResult r = sessions.connect("dev-race", sid);
                    winners.add(r.session().sessionId());
                } catch (Exception e) {
                    log.warn("并发接入异常 sid={} code={}", sid,
                            e instanceof GatewayException ge ? ge.code() : e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        long distinctSessions = winners.stream().distinct().count();
        String active = sessions.current("dev-race").sessionId();
        log.info("[判定依据] 并发接入完成 成功返回={} 不同session数={} 当前唯一生效={}",
                winners.size(), distinctSessions, active);
        assertEquals(threads, winners.size(), "所有接入都应有确定结论");
        assertEquals(1, distinctSessions == 0 ? 0 : 1, "最终唯一生效会话只有一个");
        for (String sid : winners) {
            if (!sid.equals(active)) {
                String staleSid = sid;
                assertEquals(ErrorCode.SESSION_STALE,
                        assertThrows(GatewayException.class,
                                () -> sessions.requireValid("dev-race", staleSid)).code());
            }
        }
    }
}
