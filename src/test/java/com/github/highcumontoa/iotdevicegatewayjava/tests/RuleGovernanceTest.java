package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;
import com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.RuleAuditEntry;
import com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.RuleReviewResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.TrialReport;
import com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.VerdictComparison;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleEngineService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则治理：候选历史试跑、并发版本切换边界、失败/超限拒绝、回退与历史对账。
 * 所有用例使用独立 metricField 前缀与 ruleId，避免共享 Spring 上下文下相互干扰。
 */
@SpringBootTest
class RuleGovernanceTest {

    private static final Logger log = LoggerFactory.getLogger(RuleGovernanceTest.class);

    @Autowired RuleEngineService rules;
    @Autowired GatewayProperties properties;

    private EventRecord event(String id, String field, double value) {
        return new EventRecord(id, "dev-gov-" + field, 1, field, value,
                Instant.ofEpochMilli(2000));
    }

    /** 高压下切换允许被明确拒绝（旧版本继续服务），按语义退避重试直到切换成功。 */
    private RuleDefinition activateWithRetry(String ruleId, int version, String reason)
            throws InterruptedException {
        GatewayException last = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            try {
                return rules.activate(ruleId, version, reason);
            } catch (GatewayException e) {
                if (e.code() != ErrorCode.RULE_SWITCH_CONFLICT) {
                    throw e;
                }
                last = e;
                Thread.sleep(5);
            }
        }
        throw last;
    }

    @Test
    void trialComparesCandidateVsCurrentIsRepeatedlyConsistentAndNeverTouchesLive() {
        String field = "gov-trial-temp";
        String ruleId = "gov-hot-trial";
        // 当前生效 v1：阈值 30
        rules.putRule(new RuleDefinition(ruleId, 0, field, 30.0, "alarm",
                true, Instant.now()));
        // 三条历史样本：25(不命中) 35(命中) 45(命中)
        List<EventRecord> samples = List.of(
                event("gov-trial-e1", field, 25.0),
                event("gov-trial-e2", field, 35.0),
                event("gov-trial-e3", field, 45.0));
        for (EventRecord e : samples) {
            rules.submit(e);
        }
        long processedBefore = rules.stats().processed();
        // 候选 v2：阈值升到 40 -> 35 由命中变不命中，其余不变
        RuleDefinition candidate = rules.prepareCandidate(new RuleDefinition(ruleId, 0,
                field, 40.0, "alarm", true, Instant.now()), "试跑-阈值上调到40");
        log.info("[关键输入] {} 当前 v1 阈值30，候选 v{} 阈值40，样本值=25/35/45",
                ruleId, candidate.version());
        assertEquals(2, candidate.version());

        TrialReport r1 = rules.runTrial(ruleId, null, null, samples, "第一次试跑");
        TrialReport r2 = rules.runTrial(ruleId, candidate.version(),
                null, samples, "同版本同样本重复试跑");

        log.info("[判定依据] 试跑 changed={}/{}：{}", r1.changedCount(), r1.sampleCount(),
                r1.items().stream().map(i -> i.eventId() + "[" + i.baselineFired()
                        + "->" + i.candidateFired() + "]").toList());
        assertEquals(3, r1.sampleCount());
        assertEquals(1, r1.changedCount(), "仅 35 这一条跨越阈值，整体变化=1");
        VerdictComparison only = r1.items().get(1);
        assertEquals("gov-trial-e2", only.eventId());
        assertTrue(only.baselineFired());
        assertFalse(only.candidateFired());
        assertTrue(only.changed());
        assertEquals("不再命中", only.reason());

        // 重复试跑结果一致（不含时间戳，逐项可比较）
        assertEquals(r1, r2, "同一批样本配同一组规则重复试跑，结果必须一致");

        // 试跑绝不影响实时处理：期间无新结论、无计数变化、生效版本仍是 v1
        assertEquals(processedBefore, rules.stats().processed(), "试跑不得产生判定计数");
        RuleVerdict live = rules.submit(event("gov-trial-live", field, 35.0));
        assertEquals(1, live.ruleVersion(), "试跑未启用候选，实时判定仍归属当前 v1");
        assertTrue(live.fired());

        // 基于历史事件 id 圈选样本也能得到同样差异
        TrialReport byIds = rules.runTrial(ruleId, null,
                List.of("gov-trial-e1", "gov-trial-e2", "gov-trial-e3"), null, "历史事件圈选试跑");
        assertEquals(1, byIds.changedCount());
        assertEquals(r1.items(), byIds.items(), "内联样本与历史事件同值同规则，结论一致");

        RuleAuditEntry trialAudit = rules.auditTrail(ruleId).stream()
                .filter(a -> "TRIAL".equals(a.action())).findFirst().orElseThrow();
        log.info("[版本选择] 试跑留痕 {} baseline=v{} candidate=v{} outcome={}",
                trialAudit.seq(), trialAudit.fromVersion(), trialAudit.toVersion(),
                trialAudit.outcome());
        assertEquals("SUCCESS", trialAudit.outcome());
    }

    @Test
    void concurrentSwitchAssignsEveryEventToExactlyOneVersionWithoutSplitOrLoss()
            throws Exception {
        String field = "gov-switch-temp";
        String ruleId = "gov-hot-switch";
        rules.putRule(new RuleDefinition(ruleId, 0, field, 30.0, "alarm",
                true, Instant.now()));
        RuleDefinition v2 = rules.prepareCandidate(new RuleDefinition(ruleId, 0, field,
                40.0, "alarm", true, Instant.now()), "候选阈值40");
        // 再准备 v3：阈值降到 20，保证与 v1/v2 都可区分（值 25：v1不命中 v3命中）
        RuleDefinition v3 = rules.prepareCandidate(new RuleDefinition(ruleId, 0, field,
                20.0, "alarm", true, Instant.now()), "候选阈值20");

        int threads = 12;
        int perThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch switchedToV2 = new CountDownLatch(1);
        CountDownLatch switchedToV3 = new CountDownLatch(1);
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();

        // 事件提交线程：在两个切换屏障之间持续提交“值25”事件
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String eid = "gov-sw-e-" + idx + "-" + i;
                        rules.submit(event(eid, field, 25.0));
                        if (i == perThread / 2) {
                            switchedToV2.countDown();
                        }
                    }
                    switchedToV3.countDown();
                } catch (Throwable e) {
                    workerError.compareAndSet(null, e);
                }
            }));
        }
        start.countDown();
        // 等事件提交进行到中段时切到 v2，再等收尾时切到 v3，切换与实时判定强制并发。
        // 高压下切换可能被明确拒绝（RULE_SWITCH_CONFLICT），按语义重试，最终必须成功且无半状态。
        assertTrue(switchedToV2.await(10, TimeUnit.SECONDS));
        RuleDefinition afterV2 = activateWithRetry(ruleId, v2.version(), "并发切换至v2");
        assertEquals(v2.version(), afterV2.version());
        assertTrue(switchedToV3.await(10, TimeUnit.SECONDS));
        RuleDefinition afterV3 = activateWithRetry(ruleId, v3.version(), "并发切换至v3");
        assertEquals(v3.version(), afterV3.version());
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertNull(workerError.get(), "并发提交/切换期间不应有异常");

        // 对账：每条事件恰好一条结论；按归属版本分组，值25下 v1/v2 不命中、v3 命中，
        // 版本归属与 fired 必须自洽，且不存在“半版本”结论。
        Map<Integer, Integer> countByVersion = new HashMap<>();
        int total = 0;
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                String eid = "gov-sw-e-" + t + "-" + i;
                List<RuleVerdict> verdicts = rules.verdicts(eid);
                assertEquals(1, verdicts.size(), "事件 " + eid + " 必须恰好判定一次");
                RuleVerdict v = verdicts.get(0);
                countByVersion.merge(v.ruleVersion(), 1, Integer::sum);
                boolean expectFired = v.ruleVersion() == v3.version();
                assertEquals(expectFired, v.fired(),
                        "事件 " + eid + " 归属 v" + v.ruleVersion() + "，结论必须与该版本一致");
                total++;
            }
        }
        log.info("[版本选择] 并发切换对账 总数={} 按版本分布={}（v1/v2阈值>=25不命中，v3阈值20命中）",
                total, countByVersion);
        assertEquals(threads * perThread, total, "不漏判任何一条事件");
        // 切换边界清晰：v2 切换发生在提交中段，v1 与 v3 至少都有事件归属；
        // 任何归属 v2 的事件结论必须是 v2 的结论（不命中），上面已逐条断言。
        assertTrue(countByVersion.getOrDefault(1, 0) > 0, "切换前事件归属 v1");
        assertTrue(countByVersion.getOrDefault(v3.version(), 0) > 0, "切换后事件归属 v3");

        List<RuleAuditEntry> switches = rules.auditTrail(ruleId).stream()
                .filter(a -> "ACTIVATE".equals(a.action())).toList();
        assertEquals(2, switches.size());
        assertTrue(switches.stream().allMatch(a -> "SUCCESS".equals(a.outcome())));
    }

    @Test
    void switchConflictAndCapacityLimitsAreRejectedWithoutHalfState() throws Exception {
        String field = "gov-conflict-temp";
        String ruleId = "gov-hot-conflict";
        rules.putRule(new RuleDefinition(ruleId, 0, field, 30.0, "alarm",
                true, Instant.now()));
        RuleDefinition v2 = rules.prepareCandidate(new RuleDefinition(ruleId, 0, field,
                40.0, "alarm", true, Instant.now()), "冲突用候选v2");

        // —— 场景A：实时判定占住切换锁，切换被明确拒绝，旧版本继续服务 ——
        CountDownLatch insideEvaluate = new CountDownLatch(1);
        CountDownLatch releaseEvaluate = new CountDownLatch(1);
        rules.setBeforeEvaluateHook(() -> {
            insideEvaluate.countDown();
            try {
                releaseEvaluate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 提交一条事件，使其判定停在切换临界区内，占住切换锁
            Future<RuleVerdict> f = pool.submit(
                    () -> rules.submit(event("gov-conflict-e", field, 50.0)));
            assertTrue(insideEvaluate.await(5, TimeUnit.SECONDS));

            // 临界区被实时判定占住：切换必须被明确拒绝（409），旧版本继续稳定服务
            GatewayException conflict = assertThrows(GatewayException.class,
                    () -> rules.activate(ruleId, v2.version(), "撞车切换"));
            log.info("[判定依据] 判定占用切换锁时 activate code={} msg={}",
                    conflict.code(), conflict.getMessage());
            assertEquals(ErrorCode.RULE_SWITCH_CONFLICT, conflict.code());
            assertEquals(1, rules.getRule(ruleId).version(), "拒绝切换后仍稳定生效旧版本 v1");

            releaseEvaluate.countDown();
            RuleVerdict verdict = f.get(5, TimeUnit.SECONDS);
            assertEquals(1, verdict.ruleVersion(), "临界区内事件整体按旧版本 v1 判定");
            assertTrue(verdict.fired());
        } finally {
            rules.setBeforeEvaluateHook(null);
        }

        // —— 场景B：切换占住切换锁，实时事件等待，切换完成后事件整体按新版本判定（不串版本） ——
        CountDownLatch insideSwitch = new CountDownLatch(1);
        CountDownLatch releaseSwitch = new CountDownLatch(1);
        rules.setBeforeSwitchHook(() -> {
            insideSwitch.countDown();
            try {
                releaseSwitch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            // 用再准备的 v3（阈值 20，值25在 v1 不命中、v3 命中）验证事件最终归属新版本
            RuleDefinition v3 = rules.prepareCandidate(new RuleDefinition(ruleId, 0, field,
                    20.0, "alarm", true, Instant.now()), "冲突用候选v3");
            Future<RuleDefinition> switching = pool.submit(
                    () -> rules.activate(ruleId, v3.version(), "占锁切换v3"));
            assertTrue(insideSwitch.await(5, TimeUnit.SECONDS));
            Future<RuleVerdict> eventDuringSwitch = pool.submit(
                    () -> rules.submit(event("gov-conflict-during", field, 25.0)));
            Thread.sleep(100);
            assertFalse(eventDuringSwitch.isDone(), "切换临界区内事件必须等待，不能抢判旧版本");
            releaseSwitch.countDown();
            assertEquals(v3.version(), switching.get(5, TimeUnit.SECONDS).version());
            RuleVerdict during = eventDuringSwitch.get(5, TimeUnit.SECONDS);
            log.info("[版本选择] 切换临界区内提交的事件最终归属 v{} fired={}（不得是旧版本结论）",
                    during.ruleVersion(), during.fired());
            assertEquals(v3.version(), during.ruleVersion(), "等待中的事件整体落在新版本，无半版本");
            assertTrue(during.fired());

            // 不存在版本：拒绝且当前版本不动
            GatewayException missing = assertThrows(GatewayException.class,
                    () -> rules.rollback(ruleId, 999, "回退到不存在版本"));
            assertEquals(ErrorCode.RULE_NOT_FOUND, missing.code());
            assertEquals(v3.version(), rules.getRule(ruleId).version());

            // 切到当前版本自身：幂等拒绝，不产生切换
            GatewayException same = assertThrows(GatewayException.class,
                    () -> rules.activate(ruleId, v3.version(), "重复切换当前版本"));
            assertEquals(ErrorCode.RULE_SWITCH_CONFLICT, same.code());

            // 版本历史容量打满：再准备候选被明确拒绝，无半成品版本残留
            int saved = properties.getMaxRuleVersionsPerRule();
            properties.setMaxRuleVersionsPerRule(3);
            try {
                GatewayException cap = assertThrows(GatewayException.class,
                        () -> rules.prepareCandidate(new RuleDefinition(ruleId, 0, field,
                                33.0, "alarm", true, Instant.now()), "撞容量上限"));
                log.info("[判定依据] 版本历史上限 code={} 已存版本={}",
                        cap.code(), rules.ruleHistory(ruleId).size());
                assertEquals(ErrorCode.CAPACITY_EXCEEDED, cap.code());
                assertEquals(3, rules.ruleHistory(ruleId).size(), "拒绝后不得残留半成品版本");
                assertEquals(v3.version(), rules.getRule(ruleId).version(), "生效版本不受影响");
            } finally {
                properties.setMaxRuleVersionsPerRule(saved);
            }

            // 审计留痕：被拒绝的切换也要能查清时间/原因/版本/结果
            RuleAuditEntry rejected = rules.auditTrail(ruleId).stream()
                    .filter(a -> "REJECTED".equals(a.outcome())
                            && "ACTIVATE".equals(a.action()))
                    .findFirst().orElseThrow();
            log.info("[关键输入] 拒绝留痕 seq={} {} v{}->v{} reason={}",
                    rejected.seq(), rejected.action(), rejected.fromVersion(),
                    rejected.toVersion(), rejected.reason());
            assertEquals("撞车切换", rejected.reason());
        } finally {
            pool.shutdownNow();
            rules.setBeforeSwitchHook(null);
        }
    }

    @Test
    void rollbackAndHistoricalReviewReconcileExactlyWithOriginalVersion() {
        String field = "gov-recon-temp";
        String ruleId = "gov-hot-recon";
        // v1 阈值 30：值 35 命中
        rules.putRule(new RuleDefinition(ruleId, 0, field, 30.0, "alarm",
                true, Instant.now()));
        RuleVerdict atV1 = rules.submit(event("gov-recon-e", field, 35.0));
        assertTrue(atV1.fired());
        assertEquals(1, atV1.ruleVersion());

        // v2 阈值 40：同值 35 不再命中，正式启用
        RuleDefinition v2 = rules.prepareCandidate(new RuleDefinition(ruleId, 0, field,
                40.0, "alarm", true, Instant.now()), "阈值上调40");
        rules.activate(ruleId, v2.version(), "正式启用v2");
        RuleVerdict atV2 = rules.submit(event("gov-recon-e2", field, 35.0));
        assertEquals(v2.version(), atV2.ruleVersion());
        assertFalse(atV2.fired());

        // 回退到任意历史版本 v1：之后事件重新按 v1 处理
        RuleDefinition back = rules.rollback(ruleId, 1, "v2误报偏多，回退v1");
        assertEquals(1, back.version());
        RuleVerdict afterRollback = rules.submit(event("gov-recon-e3", field, 35.0));
        log.info("[版本选择] 回退后事件归属 v{} fired={}",
                afterRollback.ruleVersion(), afterRollback.fired());
        assertEquals(1, afterRollback.ruleVersion());
        assertTrue(afterRollback.fired());

        // 历史对账：用 v1 复看 v1 当时的事件，结论必须与当时真实执行一致
        RuleReviewResult seeV1 = rules.review(ruleId, 1, event("gov-recon-e", field, 35.0));
        assertTrue(seeV1.matched());
        assertTrue(seeV1.replayed().fired());
        assertEquals("alarm", seeV1.replayed().action());
        assertNotNull(seeV1.original());
        assertEquals(seeV1.original().fired(), seeV1.replayed().fired(),
                "v1 复看结论必须与该事件落地的 v1 结论一致");

        // 同一条历史事件用 v2 复看：不命中——不能把新规则结果挂到旧版本名下，反之亦然
        RuleReviewResult seeV2ForOld = rules.review(ruleId, v2.version(),
                event("gov-recon-e", field, 35.0));
        log.info("[判定依据] 旧事件按 v{} 复看 fired={}（当时实际 v1 fired={}），basis={}",
                seeV2ForOld.ruleVersion(), seeV2ForOld.replayed().fired(),
                seeV2ForOld.original().fired(), seeV2ForOld.basis());
        assertFalse(seeV2ForOld.replayed().fired());
        assertEquals(1, seeV2ForOld.original().ruleVersion());
        assertTrue(seeV2ForOld.original().fired(), "落地结论保持 v1 原貌，不被复看篡改");

        // v2 期间的事件用 v1 复看：应当命中，证明复算严格按所选版本
        RuleReviewResult seeV1ForV2Era = rules.review(ruleId, 1,
                event("gov-recon-e2", field, 35.0));
        assertTrue(seeV1ForV2Era.replayed().fired());
        assertFalse(seeV1ForV2Era.original().fired());

        // 重复复看可复现
        RuleReviewResult again = rules.review(ruleId, 1, event("gov-recon-e", field, 35.0));
        assertEquals(seeV1.replayed().fired(), again.replayed().fired());
        assertEquals(seeV1.basis(), again.basis());

        // 全部操作留痕：时间/原因/版本/结果可查
        List<RuleAuditEntry> trail = rules.auditTrail(ruleId);
        Map<String, Long> byAction = new HashMap<>();
        for (RuleAuditEntry a : trail) {
            byAction.merge(a.action(), 1L, Long::sum);
        }
        log.info("[判定依据] {} 治理留痕统计={}（含 ROLLBACK 1 条、REVIEW 多条）",
                ruleId, byAction);
        assertTrue(byAction.getOrDefault("ROLLBACK", 0L) >= 1);
        assertTrue(byAction.getOrDefault("REVIEW", 0L) >= 4);
        RuleAuditEntry rollbackEntry = trail.stream()
                .filter(a -> "ROLLBACK".equals(a.action())).findFirst().orElseThrow();
        assertEquals("v2误报偏多，回退v1", rollbackEntry.reason());
        assertEquals(1, rollbackEntry.toVersion());
        assertEquals("SUCCESS", rollbackEntry.outcome());
    }
}
