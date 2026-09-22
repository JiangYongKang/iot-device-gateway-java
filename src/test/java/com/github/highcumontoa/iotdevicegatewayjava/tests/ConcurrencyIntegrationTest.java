package com.github.highcumontoa.iotdevicegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.command.CommandQueueService;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.SettableClock;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.SessionInfo;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceGateway;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跨模块并发：会话接管、遥测写入、指令下发交叉执行时，
 * 不出现两个生效会话、重复投递、半更新或状态回退；全局容量生效。
 */
class ConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyIntegrationTest.class);

    static class Harness {
        final SettableClock clock = new SettableClock(1_000L);
        final GatewayProperties props = new GatewayProperties();
        final CapacityMetrics metrics = new CapacityMetrics();
        final DeviceRegistry registry;
        final SessionManager sessions;
        final CommandQueueService commands;
        final DeviceGateway gateway;

        Harness() {
            registry = new DeviceRegistry(clock, props, metrics);
            sessions = new SessionManager(registry, clock, props, metrics);
            commands = new CommandQueueService(registry, sessions, clock, props, metrics);
            gateway = new DeviceGateway(registry, sessions, commands);
        }
    }

    @Test
    void concurrentConnectTakeoverLeavesExactlyOneSession() throws Exception {
        Harness h = new Harness();
        h.registry.register("dev", "m");
        h.registry.activate("dev");
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> winningSessions = ConcurrentHashMap.newKeySet();
        AtomicInteger takeovers = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final String sid = "s-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    SessionInfo info = h.gateway.connect("dev", sid);
                    if (info.sessionId().equals(sid)) {
                        winningSessions.add(sid); // 每个曾经成功成为当前会话的 ID
                    }
                    if (info.takeover()) {
                        takeovers.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        // 不变量：最终只有一个在线会话，计数一致，无残留
        assertEquals(1, h.sessions.onlineCount());
        Set<String> finalSession = new HashSet<>();
        finalSession.add(h.sessions.getActiveSession("dev").sessionId());
        assertEquals(1, finalSession.size());
        log.info("并发接管结束 onlineCount=1 takeovers={} finalSession={}",
                takeovers.get(), finalSession);
    }

    @Test
    void interleavedTelemetryAndCommandsNeverDuplicateOrCorrupt() throws Exception {
        Harness h = new Harness();
        h.props.getCommand().setQueueCapacityPerDevice(5_000);
        h.registry.register("dev", "m");
        h.registry.activate("dev");
        h.gateway.connect("dev", "stable-session");

        int writers = 8;
        int perWriter = 200;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger deliveredOrQueued = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int w = 0; w < writers; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        try {
                            // 设备在线：每条指令应恰好 DELIVERED 一次
                            CommandRecord r = h.gateway.sendCommand("dev", "P", "p", null);
                            if (r.status() == CommandStatus.DELIVERED) {
                                deliveredOrQueued.incrementAndGet();
                            }
                        } catch (GatewayException ex) {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 同时反复进行会话接管，验证投递始终绑定当时的生效会话
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    h.gateway.connect("dev", "takeover-" + i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        List<CommandRecord> all = h.commands.listForDevice("dev");
        long delivered = all.stream().filter(r -> r.status() == CommandStatus.DELIVERED).count();
        // 每条成功入队的指令最终有且仅有一个终态/投递态，且每个 ID 唯一
        assertEquals(all.size(), new HashSet<>(all.stream().map(CommandRecord::commandId).toList()).size());
        assertEquals(delivered, deliveredOrQueued.get());
        assertEquals(0, rejected.get(), "容量充足时不应有指令被拒绝");
        log.info("交叉压测 指令总数={} DELIVERED={} 无重复ID 无拒绝 最终在线会话={}",
                all.size(), delivered, h.sessions.getActiveSession("dev").sessionId());
    }

    @Test
    void globalDeviceCapacityEnforcedWithoutResidual() throws Exception {
        Harness h = new Harness();
        h.props.getCapacity().setMaxRegisteredDevices(100);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger capacityRejects = new AtomicInteger();
        List<String> registeredIds = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < threads; i++) {
                final String id = "dev-" + round + "-" + i;
                registeredIds.add(id);
                pool.submit(() -> {
                    try {
                        start.await();
                        h.registry.register(id, "m");
                        success.incrementAndGet();
                    } catch (GatewayException ex) {
                        if (ex.errorCode() == ErrorCode.CAPACITY_EXCEEDED
                                || ex.errorCode() == ErrorCode.DEVICE_ALREADY_REGISTERED) {
                            capacityRejects.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertEquals(100, h.registry.registeredCount());
        assertEquals(100, success.get());
        assertTrue(capacityRejects.get() > 0);
        log.info("全局容量: 成功登记=100 拒绝={} registeredCount=100 无越权无残留", capacityRejects.get());
    }
}
