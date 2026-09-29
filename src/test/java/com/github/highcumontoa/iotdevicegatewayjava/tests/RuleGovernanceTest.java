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
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleBackpressureStats;
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
        RuleBackpressureStats statsBefore = rules.stats();
        // 每个工作线程分三阶段提交：v1 阶段 / v2 阶段 / v3 阶段；
        // 阶段推进由主线程在“切换成功后”显式开闸，保证每个生效版本下都确定有事件进入判定，
        // 不再依赖“某线程恰好跑到一半”这种随机器负载变化的偶然时序。
        int phase1 = perThread / 3;
        int phase2 = perThread / 3;
        int phase3 = perThread - phase1 - phase2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch gateV2 = new CountDownLatch(1);
        CountDownLatch gateV3 = new CountDownLatch(1);
        // 判定点（切换锁临界区内）计数：确认已有足够事件真正在对应版本的临界区内开始判定，
        // 主线程此刻发起切换必然与在途实时判定真实并发（可能 BUSY 被拒，按约定重试）。
        java.util.concurrent.atomic.AtomicInteger evalSeenV1 =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger evalSeenV2 =
                new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch v1EvaluationsInFlight = new CountDownLatch(threads);
        CountDownLatch v2EvaluationsInFlight = new CountDownLatch(threads);
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();
        rules.setBeforeEvaluateHook(() -> {
            int liveVersion = rules.getRule(ruleId).version();
            if (liveVersion == 1 && evalSeenV1.getAndIncrement() < threads) {
                v1EvaluationsInFlight.countDown();
            } else if (liveVersion == v2.version() && evalSeenV2.getAndIncrement() < threads) {
                v2EvaluationsInFlight.countDown();
            }
        });

        // 事件提交线程：三段“值25”事件，段间在闸门前等待，提交本身始终与切换并发。
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < phase1; i++) {
                        rules.submit(event("gov-sw-e-" + idx + "-" + i, field, 25.0));
                    }
                    gateV2.await();
                    for (int i = phase1; i < phase1 + phase2; i++) {
                        rules.submit(event("gov-sw-e-" + idx + "-" + i, field, 25.0));
                    }
                    gateV3.await();
                    for (int i = phase1 + phase2; i < perThread; i++) {
                        rules.submit(event("gov-sw-e-" + idx + "-" + i, field, 25.0));
                    }
                } catch (Throwable e) {
                    workerError.compareAndSet(null, e);
                }
            }));
        }
        try {
            start.countDown();
            // 等够 threads 条事件已经在 v1 判定临界区内开始判定，再切 v2：切换与判定强制并发；
            // 若被 BUSY 明确拒绝，旧版本继续服务，按语义重试直到成功，无半生效状态。
            assertTrue(v1EvaluationsInFlight.await(10, TimeUnit.SECONDS));
            RuleDefinition afterV2 = activateWithRetry(ruleId, v2.version(), "并发切换至v2");
            assertEquals(v2.version(), afterV2.version());
            gateV2.countDown();
            // 同样保证 v2 生效期间有事件在 v2 临界区内判定，再切 v3。
            assertTrue(v2EvaluationsInFlight.await(10, TimeUnit.SECONDS));
            RuleDefinition afterV3 = activateWithRetry(ruleId, v3.version(), "并发切换至v3");
            assertEquals(v3.version(), afterV3.version());
            gateV3.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            assertNull(workerError.get(), "并发提交/切换期间不应有异常");
        } finally {
            rules.setBeforeEvaluateHook(null);
            pool.shutdown();
        }

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
        // 切换边界清晰且覆盖稳定（下界与线程调度无关）：
        // v1/v2 都至少有 threads 条事件在各自判定临界区内被计数；
        // v3 阶段整段在 v3 成功开闸之后，phase3 事件必然全部归属 v3。
        assertTrue(countByVersion.getOrDefault(1, 0) >= threads, "切换前确有事件归属 v1");
        assertTrue(countByVersion.getOrDefault(v2.version(), 0) >= threads,
                "v2 生效期间确有事件归属 v2");
        assertTrue(countByVersion.getOrDefault(v3.version(), 0) >= threads * phase3,
                "v3 开闸后的事件全部归属 v3");
        assertEquals(v3.version(), rules.getRule(ruleId).version(), "最终生效版本必须是 v3");

        // 留痕对账：成功记录与拒绝记录必须能分开追溯，且不依赖并发时序（拒绝多少次都不影响结论）。
        List<RuleAuditEntry> activateEntries = rules.auditTrail(ruleId).stream()
                .filter(a -> "ACTIVATE".equals(a.action())).toList();
        List<RuleAuditEntry> succeeded = activateEntries.stream()
                .filter(a -> "SUCCESS".equals(a.outcome())).toList();
        List<RuleAuditEntry> rejected = activateEntries.stream()
                .filter(a -> "REJECTED".equals(a.outcome())).toList();
        // 成功切换恰好两次，迁移路径确定：1->2、2->3，与重试/撞车次数无关。
        assertEquals(2, succeeded.size(), "成功切换恰好两次（被拒尝试不算成功也不算失败）");
        assertEquals(1, succeeded.get(0).fromVersion());
        assertEquals(v2.version(), succeeded.get(0).toVersion());
        assertEquals(v2.version(), succeeded.get(1).fromVersion());
        assertEquals(v3.version(), succeeded.get(1).toVersion());
        assertTrue(succeeded.get(0).seq() < succeeded.get(1).seq());
        // 所有被拒尝试都必须是“繁忙拒绝”，且记录的 fromVersion 是拒绝时真正生效的版本，
        // toVersion 是被拒目标；每一条拒绝都在其后续成功之前，绝不允许 REJECT_SAME/版本错乱。
        for (RuleAuditEntry r : rejected) {
            assertTrue(r.detail().startsWith("REJECT_BUSY"),
                    "并发压测中只允许繁忙拒绝，实际 detail=" + r.detail());
            if (r.toVersion().equals(v2.version())) {
                assertEquals(1, r.fromVersion(), "拒绝切 v2 时生效版本只能是 v1");
                assertTrue(r.seq() < succeeded.get(0).seq());
            } else {
                assertEquals(v3.version(), r.toVersion());
                assertEquals(v2.version(), r.fromVersion(), "拒绝切 v3 时生效版本只能是 v2");
                assertTrue(r.seq() > succeeded.get(0).seq());
                assertTrue(r.seq() < succeeded.get(1).seq());
            }
        }
        log.info("[判定依据] 切换留痕分开核对：成功={} 条，繁忙拒绝={} 条（随并发时序变化但不影响对账）",
                succeeded.size(), rejected.size());

        // 最终统计不变量：480 提交 480 处理，无背压丢弃、无毒消息、队列排空，
        // 一次合法拒绝不进任何失败计数，重试成功不重复计数。
        // 注意 stats() 为引擎级全局计数器（Spring 上下文跨用例共享），按本用例前后差值核对。
        RuleBackpressureStats finalStats = rules.stats();
        assertEquals(threads * perThread,
                finalStats.submitted() - statsBefore.submitted(), "提交数确定");
        assertEquals(threads * perThread,
                finalStats.processed() - statsBefore.processed(), "处理数=提交数，拒绝/重试不影响统计");
        assertEquals(0L, finalStats.droppedBackpressure() - statsBefore.droppedBackpressure(),
                "本场景不应触发背压丢弃");
        assertEquals(0L, finalStats.failedPoison() - statsBefore.failedPoison(),
                "本场景无毒消息");
        assertEquals(0, finalStats.queueDepth(), "处理完成后队列必须排空");
    }

    /**
     * 确定性交织：实时判定占住切换锁期间，切换被明确拒绝（且按约定重试仍被拒），
     * 系统持续按旧版本稳定服务；判定结束后重试切换成功，后续事件整体按新版本判定。
     * 同一段交织重复执行多轮，最终生效版本、逐条事件归属、成功/拒绝分开的留痕必须完全一致，
     * 不依赖任何线程时序偶然性（不使用 tryLock 撞运气，由屏障精确编排）。
     */
    @Test
    void rejectedSwitchKeepsOldVersionAndRetryAfterBusyPeriodSucceedsDeterministically()
            throws Exception {
        String field = "gov-retry-temp";
        String ruleId = "gov-hot-retry";
        // 4) 统计不被拒绝污染：3 提交 3 处理，无丢弃无毒消息（全局计数器按差值核对）
        RuleBackpressureStats stBefore = rules.stats();
        rules.putRule(new RuleDefinition(ruleId, 0, field, 30.0, "alarm",
                true, Instant.now()));
        RuleDefinition v2 = rules.prepareCandidate(new RuleDefinition(ruleId, 0, field,
                40.0, "alarm", true, Instant.now()), "拒绝重试用候选v2");
        RuleDefinition v3 = rules.prepareCandidate(new RuleDefinition(ruleId, 0, field,
                20.0, "alarm", true, Instant.now()), "拒绝重试用候选v3");

        // 编排：实时判定进入切换临界区后先到齐两个切换线程（各自 tryLock 必失败、被明确拒绝），
        // 再放行判定；判定结束后两个线程按顺序重试，成功顺序确定为 v2 先、v3 后。
        CountDownLatch insideEvaluate = new CountDownLatch(1);
        CountDownLatch rejectedProbe = new CountDownLatch(2);
        CountDownLatch releaseEvaluate = new CountDownLatch(1);
        rules.setBeforeEvaluateHook(() -> {
            insideEvaluate.countDown();
            try {
                releaseEvaluate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            // 事件1：值35，在 v1(阈值30) 命中；其判定全程占住切换锁
            Future<RuleVerdict> e1 = pool.submit(
                    () -> rules.submit(event("gov-retry-e1", field, 35.0)));
            assertTrue(insideEvaluate.await(5, TimeUnit.SECONDS));

            // 两个切换尝试并发撞在判定临界区上：必须都被明确拒绝，且留痕可分开追溯
            Future<GatewayException> rejectV2 = pool.submit(() -> {
                try {
                    rules.activate(ruleId, v2.version(), "繁忙期切换v2");
                    return null;
                } catch (GatewayException ex) {
                    rejectedProbe.countDown();
                    return ex;
                }
            });
            Future<GatewayException> rejectV3 = pool.submit(() -> {
                try {
                    rules.activate(ruleId, v3.version(), "繁忙期切换v3");
                    return null;
                } catch (GatewayException ex) {
                    rejectedProbe.countDown();
                    return ex;
                }
            });
            assertTrue(rejectedProbe.await(5, TimeUnit.SECONDS), "两个切换尝试都应被拒绝");
            // 被拒期间旧版本持续稳定服务（此时判定锁仍被 e1 持有，生效引用不可能被动过）
            assertEquals(1, rules.getRule(ruleId).version(), "合法拒绝后仍按旧版本 v1 服务");
            GatewayException ex2 = rejectV2.get(5, TimeUnit.SECONDS);
            GatewayException ex3 = rejectV3.get(5, TimeUnit.SECONDS);
            assertNotNull(ex2);
            assertNotNull(ex3);
            assertEquals(ErrorCode.RULE_SWITCH_CONFLICT, ex2.code());
            assertEquals(ErrorCode.RULE_SWITCH_CONFLICT, ex3.code());
            assertEquals(1, rules.getRule(ruleId).version(), "两次拒绝后仍稳定生效 v1");

            // 放行判定：事件1整体按旧版本 v1 判定（v1 阈值30，值35 命中）
            releaseEvaluate.countDown();
            RuleVerdict v1Verdict = e1.get(5, TimeUnit.SECONDS);
            assertEquals(1, v1Verdict.ruleVersion());
            assertTrue(v1Verdict.fired());

            // 判定结束后重试：v2 先成功、v3 后成功（顺序固定，事件归属不随时序变化）
            RuleDefinition onV2 = rules.activate(ruleId, v2.version(), "重试启用v2");
            assertEquals(v2.version(), onV2.version());
            RuleVerdict e2 = rules.submit(event("gov-retry-e2", field, 25.0));
            assertEquals(v2.version(), e2.ruleVersion(), "v2 生效后事件归属 v2");
            assertFalse(e2.fired(), "值25 在 v2(阈值40) 不命中");

            RuleDefinition onV3 = rules.activate(ruleId, v3.version(), "重试启用v3");
            assertEquals(v3.version(), onV3.version());
            RuleVerdict e3 = rules.submit(event("gov-retry-e3", field, 25.0));
            assertEquals(v3.version(), e3.ruleVersion(), "v3 生效后事件归属 v3");
            assertTrue(e3.fired(), "值25 在 v3(阈值20) 命中");

            // 再试一次已生效的 v3：幂等拒绝，不算成功、不算失败，不影响最终生效版本
            GatewayException same = assertThrows(GatewayException.class,
                    () -> rules.activate(ruleId, v3.version(), "重复切换v3"));
            assertEquals(ErrorCode.RULE_SWITCH_CONFLICT, same.code());
            assertEquals(v3.version(), rules.getRule(ruleId).version());
        } finally {
            rules.setBeforeEvaluateHook(null);
            pool.shutdown();
        }

        // ===== 逐轮确定的最终对账（同一段交织重复跑，以下结果必须逐字节一致） =====
        // 1) 最终生效版本
        assertEquals(v3.version(), rules.getRule(ruleId).version());
        // 2) 每条事件恰好一条结论，版本归属与 fired 自洽
        Map<String, Integer> versionByEvent = new HashMap<>();
        Map<String, Boolean> firedByEvent = new HashMap<>();
        for (String eid : List.of("gov-retry-e1", "gov-retry-e2", "gov-retry-e3")) {
            List<RuleVerdict> vs = rules.verdicts(eid);
            assertEquals(1, vs.size(), eid + " 必须恰好判定一次，拒绝/重试不造成重复判定");
            versionByEvent.put(eid, vs.get(0).ruleVersion());
            firedByEvent.put(eid, vs.get(0).fired());
        }
        assertEquals(Map.of(
                "gov-retry-e1", 1,
                "gov-retry-e2", v2.version(),
                "gov-retry-e3", v3.version()), versionByEvent,
                "事件版本归属必须逐轮一致：e1->v1, e2->v2, e3->v3");
        assertEquals(Map.of(
                "gov-retry-e1", true,
                "gov-retry-e2", false,
                "gov-retry-e3", true), firedByEvent,
                "事件结论必须逐轮一致，不允许一部分按旧规则一部分按新规则");

        // 3) 成功记录与拒绝记录分开追溯，条数、版本对、顺序全部确定
        List<RuleAuditEntry> all = rules.auditTrail(ruleId).stream()
                .filter(a -> "ACTIVATE".equals(a.action())).toList();
        List<RuleAuditEntry> ok = all.stream()
                .filter(a -> "SUCCESS".equals(a.outcome())).toList();
        List<RuleAuditEntry> busy = all.stream()
                .filter(a -> "REJECTED".equals(a.outcome())
                        && a.detail().startsWith("REJECT_BUSY")).toList();
        List<RuleAuditEntry> sameRejects = all.stream()
                .filter(a -> "REJECTED".equals(a.outcome())
                        && a.detail().startsWith("REJECT_SAME")).toList();
        assertEquals(2, ok.size());
        assertEquals(List.of(1, v2.version()),
                List.of(ok.get(0).fromVersion(), ok.get(0).toVersion()));
        assertEquals(List.of(v2.version(), v3.version()),
                List.of(ok.get(1).fromVersion(), ok.get(1).toVersion()));
        assertEquals(2, busy.size(), "繁忙期两次尝试各留一条 REJECT_BUSY，且 fromVersion 均为 v1");
        for (RuleAuditEntry r : busy) {
            assertEquals(1, r.fromVersion(), "被拒时生效版本仍是 v1，拒绝不影响版本归属");
            assertTrue(r.seq() < ok.get(0).seq(), "所有繁忙拒绝都发生在任何成功切换之前");
        }
        assertEquals(1, sameRejects.size(), "重复切换当前版本留下一条 REJECT_SAME");
        assertEquals(v3.version(), sameRejects.get(0).fromVersion());
        assertTrue(sameRejects.get(0).seq() > ok.get(1).seq());
        // 繁忙拒绝按目标版本也能分别查清
        assertEquals(1, busy.stream().filter(r -> r.toVersion().equals(v2.version())).count());
        assertEquals(1, busy.stream().filter(r -> r.toVersion().equals(v3.version())).count());
        log.info("[判定依据] 拒绝/重试/成功留痕可分开追溯：SUCCESS {}，REJECT_BUSY {}，REJECT_SAME {}",
                ok.stream().map(a -> a.fromVersion() + "->" + a.toVersion()).toList(),
                busy.stream().map(a -> a.fromVersion() + "->" + a.toVersion()).toList(),
                sameRejects.stream().map(a -> a.fromVersion() + "->" + a.toVersion()).toList());

        // 4) 统计不被拒绝污染：3 提交 3 处理，无丢弃无毒消息（全局计数器按差值核对）
        RuleBackpressureStats st = rules.stats();
        assertEquals(3, st.submitted() - stBefore.submitted());
        assertEquals(3, st.processed() - stBefore.processed());
        assertEquals(0L, st.droppedBackpressure() - stBefore.droppedBackpressure());
        assertEquals(0L, st.failedPoison() - stBefore.failedPoison());
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
