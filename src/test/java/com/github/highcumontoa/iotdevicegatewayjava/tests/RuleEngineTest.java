package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleBackpressureStats;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleEngineService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RuleEngineTest {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineTest.class);

    @Autowired RuleEngineService rules;
    @Autowired GatewayProperties properties;

    private EventRecord event(String id, double value) {
        return new EventRecord(id, "dev-rule", 1, "temperature", value,
                Instant.ofEpochMilli(1000));
    }

    @Test
    void verdictsBindRuleVersionAndRemainDistinguishableAfterUpdate() {
        RuleDefinition v1 = rules.putRule(new RuleDefinition("hot", 0, "temperature",
                30.0, "alarm", true, Instant.now()));
        RuleVerdict before = rules.submit(event("evt-before", 35.0));
        log.info("[关键输入] v{} 阈值30，值35 fired={}", before.ruleVersion(), before.fired());
        assertEquals(v1.version(), before.ruleVersion());
        assertTrue(before.fired());

        RuleDefinition v2 = rules.putRule(new RuleDefinition("hot", 0, "temperature",
                40.0, "alarm", true, Instant.now()));
        RuleVerdict after = rules.submit(event("evt-after", 35.0));
        log.info("[判定依据] v{} 阈值40，值35 fired={}；历史事件结论仍为 v{} fired={}",
                after.ruleVersion(), after.fired(), before.ruleVersion(), before.fired());
        assertEquals(v2.version(), after.ruleVersion());
        assertFalse(after.fired());

        // 历史结论仍绑定旧版本、仍旧命中——规则更新后历史结论可区分
        RuleVerdict historical = rules.verdicts("evt-before").get(0);
        assertEquals(1, historical.ruleVersion());
        assertTrue(historical.fired());
    }

    @Test
    void backpressureDropsOldestAndPoisonEventDoesNotBreakChain() {
        int capacity = properties.getRuleQueueCapacity();
        // 灌满积压（不处理），再超量 -> 最旧被背压丢弃
        for (int i = 0; i < capacity; i++) {
            rules.enqueueWithoutProcessing(event("evt-bp-" + i, 10.0));
        }
        EventRecord displaced = rules.enqueueWithoutProcessing(event("evt-bp-over", 10.0));
        RuleBackpressureStats stats = rules.stats();
        log.info("[判定依据] 超量入队 被挤出事件={} depth={} dropped={}",
                displaced.eventId(), stats.queueDepth(), stats.droppedBackpressure());
        assertEquals("evt-bp-0", displaced.eventId());
        assertEquals(capacity, stats.queueDepth());
        assertTrue(stats.droppedBackpressure() >= 1);

        // 中间放一条毒消息（用事件 id 匹配），随后排一条正常事件，统一处理
        EventRecord poison = event("evt-poison", 50.0);
        EventRecord normalAfter = event("evt-normal-after", 50.0);
        rules.putRule(new RuleDefinition("hot-bp", 0, "temperature",
                40.0, "alarm", true, Instant.now()));
        rules.setPoisonMatcher(e -> "evt-poison".equals(e.eventId()));
        rules.enqueueWithoutProcessing(poison);
        rules.enqueueWithoutProcessing(normalAfter);
        rules.drainQueue();

        RuleVerdict normalVerdict = rules.verdicts("evt-normal-after").get(0);
        RuleBackpressureStats after = rules.stats();
        log.info("[判定依据] 毒消息后正常事件 fired={} failedPoison={} processed={}",
                normalVerdict.fired(), after.failedPoison(), after.processed());
        assertTrue(normalVerdict.fired(), "毒消息之后的正常事件仍被正确处理，链路未被拖垮");
        assertTrue(after.failedPoison() >= 1, "毒消息被计数隔离");
        rules.setPoisonMatcher(null);
    }
}
