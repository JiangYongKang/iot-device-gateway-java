package com.github.highcumontoa.iotdevicegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.SettableClock;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceEvent;
import com.github.highcumontoa.iotdevicegatewayjava.domain.EventResult;
import com.github.highcumontoa.iotdevicegatewayjava.domain.RuleOutcome;
import com.github.highcumontoa.iotdevicegatewayjava.domain.RuleSpec;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.rules.RuleEngineService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规则引擎：版本化结论可复现、规则更新后历史结论可区分、积压背压/丢弃可观测、单规则异常隔离。
 */
class RuleEngineTest {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineTest.class);

    private RuleEngineService engine(GatewayProperties props) {
        return new RuleEngineService(new SettableClock(1_000L), props, new CapacityMetrics());
    }

    private GatewayProperties props(String policy, int capacity) {
        GatewayProperties p = new GatewayProperties();
        p.getRules().setBacklogPolicy(policy);
        p.getRules().setBacklogCapacity(capacity);
        return p;
    }

    private DeviceEvent event(String id, double temp) {
        return new DeviceEvent(id, "dev", 1_000L, Map.of("temp", temp));
    }

    @Test
    void historicalConclusionsSurviveRuleUpdatesAndAreReproducible() {
        RuleEngineService svc = engine(props("DISCARD_OLDEST", 100));
        // 规则 v1：temp > 50 判定 HOT
        RuleSpec v1 = svc.upsertRule(new RuleSpec("hot", 0L, "temp", "GT", "50", "HOT"));
        assertEquals(1L, v1.version());

        DeviceEvent e = event("evt-1", 60);
        EventResult first = svc.processNow(e);
        RuleOutcome o1 = first.outcomes().get(0);
        assertTrue(o1.matched());
        assertEquals(1L, o1.ruleVersion());
        assertEquals("HOT", o1.decision());

        // 规则更新为 v2：阈值提高到 70
        RuleSpec v2 = svc.upsertRule(new RuleSpec("hot", 1L, "temp", "GT", "70", "HOT"));
        assertEquals(2L, v2.version());

        // 历史结论保持 v1 判定，不随规则更新改变（可区分、可复现）
        EventResult historical = svc.replay("evt-1", null);
        assertEquals(1L, historical.outcomes().get(0).ruleVersion());
        assertTrue(historical.outcomes().get(0).matched());

        // 以新版本重算：同一事件结论变为不命中（复现新规则下的结论）
        EventResult underV2 = svc.replay("evt-1", 2L);
        assertEquals(2L, underV2.outcomes().get(0).ruleVersion());
        assertFalse(underV2.outcomes().get(0).matched());

        // 规则并发更新版本冲突
        GatewayException conflict = assertThrows(GatewayException.class,
                () -> svc.upsertRule(new RuleSpec("hot", 1L, "temp", "GT", "90", "HOT")));
        assertEquals(ErrorCode.STATE_VERSION_CONFLICT, conflict.errorCode());
        log.info("历史结论版本=1 保持命中；新规则版本=2 复算不命中；并发更新冲突码={}",
                conflict.errorCode());
    }

    @Test
    void discardOldestBackpressureIsObservable() throws Exception {
        GatewayProperties p = props("DISCARD_OLDEST", 2);
        p.getRules().setWorkerThreads(1);
        CapacityMetrics metrics = new CapacityMetrics();
        RuleEngineService svc = new RuleEngineService(new SettableClock(1_000L), p, metrics);
        svc.upsertRule(new RuleSpec("hot", 0L, "temp", "GT", "50", "HOT"));

        // 先同步处理一个事件用于建立历史，然后暂停消费把积压填满
        svc.processNow(event("processed-early", 60));
        svc.pauseProcessing();
        svc.submit(event("e1", 60));
        svc.submit(event("e2", 60));
        // 第三个提交应淘汰最旧 e1
        svc.submit(event("e3", 60));
        assertEquals(2, svc.backlogDepth());
        assertEquals(1, metrics.eventBacklogDiscards());
        assertTrue(svc.discardedSnapshot().containsKey("e1"));
        log.info("DISCARD_OLDEST 背压: depth=2 discards=1 被淘汰={}", svc.discardedSnapshot().keySet());

        svc.resumeProcessing();
        svc.waitForProcessed(5_000, 3); // 含早期1 + 队列处理2
        assertEquals(3, metrics.eventsProcessed());
    }

    @Test
    void discardNewestRejectsWithStableCode() {
        GatewayProperties p = props("DISCARD_NEWEST", 1);
        CapacityMetrics metrics = new CapacityMetrics();
        RuleEngineService svc = new RuleEngineService(new SettableClock(1_000L), p, metrics);
        svc.pauseProcessing();
        svc.upsertRule(new RuleSpec("hot", 0L, "temp", "GT", "50", "HOT"));
        svc.submit(event("e1", 60));
        GatewayException rejected = assertThrows(GatewayException.class,
                () -> svc.submit(event("e2", 60)));
        assertEquals(ErrorCode.BACKLOG_DISCARDED, rejected.errorCode());
        assertEquals(1, metrics.eventBacklogRejections());
        assertEquals(1, svc.backlogDepth());
        log.info("DISCARD_NEWEST 拒绝码={} rejections=1 队列深度保持1", rejected.errorCode());
    }

    @Test
    void singleRuleErrorIsIsolatedAndCounted() {
        CapacityMetrics metrics = new CapacityMetrics();
        RuleEngineService svc = engine(props("DISCARD_OLDEST", 10));
        // 正常规则：对数值字段 level 判定
        svc.upsertRule(new RuleSpec("ok", 0L, "level", "GT", "5", "HIGH"));
        // 异常规则：对字符串字段 temp 做有序数值比较，求值时必抛异常
        svc.upsertRule(new RuleSpec("bad", 0L, "temp", "GT", "50", "HOT"));
        DeviceEvent badEvent = new DeviceEvent("evt-bad", "dev", 1L,
                Map.of("temp", "not-a-number", "level", 9));
        EventResult result = svc.processNow(badEvent);
        // 恰好一条规则因类型错误被隔离，且不影响另一条规则产出
        assertEquals(1, result.ruleErrorCount());
        assertEquals(1, result.outcomes().size(), "出错规则之外的规则仍产出结论");
        assertEquals("ok", result.outcomes().get(0).ruleId());
        assertTrue(result.outcomes().get(0).matched());
        log.info("单规则异常隔离: ruleErrorCount=1 其余规则正常输出 rule=ok matched=true");
    }
}
