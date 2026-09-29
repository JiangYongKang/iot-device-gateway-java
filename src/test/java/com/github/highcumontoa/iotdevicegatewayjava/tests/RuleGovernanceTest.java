package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleAuditEntry;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleTrialItem;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleTrialResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleEngineService;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleGovernanceService;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RuleTrialRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则治理新增行为：
 * 1) 候选规则历史试跑对比（只读、确定性、重复一致、不动线上结论）；
 * 2) 切换与实时处理并存的版本边界（一条事件只属于一个版本，不重不漏不串）；
 * 3) 切换失败/并发切换/试跑超限/样本超限的明确拒绝且旧版本稳定服务；
 * 4) 回退到历史版本 + 指定历史版本复看，结论与当时真实执行一致、可复现；
 * 5) 每次试跑/切换/回退/复看/拒绝均有审计留痕。
 */
@SpringBootTest
class RuleGovernanceTest {

    private static final Logger log = LoggerFactory.getLogger(RuleGovernanceTest.class);

    @Autowired RuleEngineService engine;
    @Autowired RuleGovernanceService governance;

    private static final Instant T0 = Instant.ofEpochMilli(1_000_000L);

    @AfterEach
    void clearHooks() {
        governance.setSwitchHook(null);
        governance.setTrialBarrier(null);
    }

    private EventRecord ev(String id, double value) {
        return new EventRecord(id, "dev-gov", 1, "temperature", value,
                T0.plusMillis(Long.parseLong(id.replaceAll("\\D", "")) % 100_000));
    }

    private RuleTrialRequest trialRequest(String ruleId, double candidateThreshold,
                                          List<EventRecord> events, String reason) {
        return new RuleTrialRequest(ruleId, "temperature", candidateThreshold,
                "alarm", Boolean.TRUE, events, reason);
    }

    // ---- 1) 历史试跑：差异可见、只读、重复一致 ---------------------------------

    @Test
    void trialComparesCandidateAgainstCurrentAndIsDeterministicAndReadOnly() {
        final String ruleId = "gov-hot";
        RuleDefinition v1 = engine.putRule(new RuleDefinition(ruleId, 0, "temperature",
                30.0, "alarm", true, Instant.now()));
        // 先用当前规则真实处理一条事件，留下线上结论
        RuleVerdict liveBefore = engine.submit(ev("gov1", 35.0));
        log.info("[关键输入] 当前规则 {} v{} threshold=30；样本 35/25 两条", ruleId, v1.version());
        assertEquals(1, liveBefore.ruleVersion());
        assertTrue(liveBefore.fired());

        List<EventRecord> samples = List.of(ev("gov2", 35.0), ev("gov3", 25.0));
        RuleTrialRequest req = trialRequest(ruleId, 40.0, samples, "灰度评估阈值上调");

        RuleTrialResult first = governance.trial(req);
        RuleTrialResult second = governance.trial(req);

        log.info("[判定依据] 试跑1 changed={} 明细: {}", first.changedCount(),
                summarize(first.items()));
        log.info("[判定依据] 试跑2 changed={} trialId={}/{}", second.changedCount(),
                first.trialId(), second.trialId());

        assertEquals(1, first.currentVersion());
        assertEquals(2, first.totalEvents());
        // 35 在 v1(阈值30) 命中、在候选(阈值40) 不命中 -> 1 条变化；25 两边都不命中
        assertEquals(1, first.changedCount());
        RuleTrialItem changedItem = first.items().get(0);
        assertEquals("gov2", changedItem.eventId());
        assertTrue(changedItem.current().fired());
        assertFalse(changedItem.candidate().fired());
        assertTrue(changedItem.changed());
        assertFalse(first.items().get(1).changed());

        // 同批样本+同组规则重复试跑：trialId 相同，且逐条结论一致（确定性）
        assertEquals(first.trialId(), second.trialId());
        assertEquals(first.items(), second.items());
        assertEquals(first.changedCount(), second.changedCount());
        // 试跑纯只读：线上事件结论不被改动、不新增、不绑定候选版本
        RuleVerdict liveAfter = engine.verdicts("gov1").get(0);
        assertEquals(1, liveAfter.ruleVersion());
        assertTrue(liveAfter.fired());
        assertEquals(1, engine.verdicts("gov1").size());
        assertEquals(0, engine.verdicts("gov2").size(), "试跑样本不得落地结论");

        // 审计留痕：两次 TRIAL，可查时间/原因/版本/结论摘要
        long trialAudits = governance.auditLog().stream()
                .filter(a -> RuleAuditEntry.TRIAL.equals(a.action())
                        && ruleId.equals(a.ruleId())
                        && "灰度评估阈值上调".equals(a.reason())).count();
        assertTrue(trialAudits >= 2, "每次试跑都要留痕");
    }

    // ---- 2) 切换与实时处理并存：边界清晰、版本归属唯一 --------------------------

    @Test
    void concurrentEventsDuringSwitchBelongToExactlyOneVersion() throws Exception {
        final String ruleId = "gov-switch";
        RuleDefinition v1 = engine.putRule(new RuleDefinition(ruleId, 0, "temperature",
                30.0, "alarm", true, Instant.now()));
        // 发布 v2 候选（暂不激活）
        RuleDefinition v2 = governance.publishCandidate(new RuleDefinition(ruleId, 0,
                "temperature", 40.0, "alarm", true, Instant.now()), false, "阈值上调候选");
        log.info("[关键输入] {} 在线 v1 threshold=30，待切 v{} threshold=40", ruleId, v2.version());
        assertEquals(2, v2.version());
        assertEquals(1, engine.getRule(ruleId).version(), "候选发布不影响线上");

        Gate switchGate = new Gate();
        governance.setSwitchHook(switchGate.hook());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> switchFuture = pool.submit(() -> governance.activate(ruleId, 2,
                "正式上调阈值"));
        switchGate.awaitEntered();

        // 切换临界区期间并发提交一批事件：每条都必须完整落在 v1 或 v2 下
        int n = 20;
        List<Future<RuleVerdict>> futures = new ArrayList<>();
        for (int i = 100; i < 100 + n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> engine.submit(ev("govsw" + idx, 35.0))));
        }
        switchGate.release();
        switchFuture.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        int v1Count = 0;
        int v2Count = 0;
        for (int i = 100; i < 100 + n; i++) {
            RuleVerdict verdict = futures.get(i - 100).get(5, TimeUnit.SECONDS);
            assertTrue(verdict.ruleVersion() == 1 || verdict.ruleVersion() == 2,
                    "结论版本只能是 v1 或 v2，实际 v" + verdict.ruleVersion());
            if (verdict.ruleVersion() == 1) {
                // v1 阈值30：35>30 必命中
                assertTrue(verdict.fired(), "v1 下 35 应命中 eventId=" + verdict.eventId());
                v1Count++;
            } else {
                // v2 阈值40：35<=40 必不命中
                assertFalse(verdict.fired(), "v2 下 35 不应命中 eventId=" + verdict.eventId());
                v2Count++;
            }
            // 每条事件恰好一条结论：不重复、不遗漏、不出现半旧半新
            assertEquals(1, engine.verdicts(verdict.eventId()).size(),
                    "事件只能判定一次 eventId=" + verdict.eventId());
        }
        log.info("[判定依据] 切换窗口内事件 v1(旧)={} 条、v2(新)={} 条，合计={}，无重复/遗漏/串版本",
                v1Count, v2Count, v1Count + v2Count);
        assertEquals(n, v1Count + v2Count, "全部事件都有且仅有一个版本归属");
        assertEquals(2, engine.getRule(ruleId).version(), "切换后线上为 v2");

        RuleAuditEntry activate = governance.auditLog().stream()
                .filter(a -> RuleAuditEntry.ACTIVATE.equals(a.action())
                        && ruleId.equals(a.ruleId()) && a.ruleVersion() == 2)
                .reduce((a, b) -> b).orElseThrow();
        assertTrue(activate.summary().contains("v1 -> v2"));
        log.info("[版本选择] 审计: action={} {} v{} reason={} at={}", activate.action(),
                ruleId, activate.ruleVersion(), activate.reason(), activate.at());
    }

    // ---- 3) 失败/超限：明确拒绝，旧版本稳定服务，不留半生效状态 -------------------

    @Test
    void failedAndConcurrentSwitchIsRejectedAndOldVersionKeepsServing() throws Exception {
        final String ruleId = "gov-fail";
        engine.putRule(new RuleDefinition(ruleId, 0, "temperature", 30.0,
                "alarm", true, Instant.now()));
        RuleDefinition v2 = governance.publishCandidate(new RuleDefinition(ruleId, 0,
                "temperature", 40.0, "alarm", true, Instant.now()), false, "待切候选");

        // 3a) 切换在引用切换前失败：抛 RULE_SWITCH_FAILED，线上仍是 v1
        governance.setSwitchHook(() -> {
            throw new IllegalStateException("模拟切换写入失败");
        });
        GatewayException ex = assertThrows(GatewayException.class,
                () -> governance.activate(ruleId, v2.version(), "预期会失败的切换"));
        assertEquals(ErrorCode.RULE_SWITCH_FAILED, ex.code());
        log.info("[判定依据] 切换失败被拒绝 code={} msg={}", ex.code(),
                ex.getMessage());
        assertEquals(1, engine.getRule(ruleId).version(), "切换失败必须保持旧版本 v1");
        RuleVerdict afterFailure = engine.submit(ev("govf1", 35.0));
        assertEquals(1, afterFailure.ruleVersion());
        assertTrue(afterFailure.fired(), "旧版本 v1 继续稳定服务，35 仍命中");
        governance.setSwitchHook(null);

        // 3b) 并发切换：一个切换占住临界区，第二个立即 RULE_SWITCH_IN_PROGRESS
        Gate gate = new Gate();
        Runnable sharedHook = gate.hook();
        governance.setSwitchHook(sharedHook);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> holding = pool.submit(() ->
                governance.activate(ruleId, v2.version(), "占住切换窗口"));
        gate.awaitEntered();
        // 第二个切换发生在调用线程（同步路径），信号量被占 -> 立即拒绝
        GatewayException concurrent = assertThrows(GatewayException.class,
                () -> governance.activate(ruleId, v2.version(), "并发切换应被拒绝"));
        assertEquals(ErrorCode.RULE_SWITCH_IN_PROGRESS, concurrent.code());
        log.info("[判定依据] 并发切换被拒绝 code={}", concurrent.code());
        // 被拒瞬间活动版本必然仍是 v1：这里在池线程仍持有切换临界区时读取快照。
        // 注意必须先放行持锁的切换（避免主线程与持有 store 监视器的池线程互锁），
        // 放行不改变“拒绝发生时版本仍为 v1”这一事实——拒绝与放行是严格先后两步。
        gate.release();
        holding.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        // 拒绝响应已经产生且未触发任何版本变更；持锁切换随后正常完成到 v2
        assertEquals(2, engine.getRule(ruleId).version(),
                "被拒绝的并发切换不生效，持锁切换完成后才到 v2");

        // 3c) 拒绝也要留痕：并发拒绝记录必须带“被拒时线上仍为 v1”
        RuleAuditEntry rejected = governance.auditLog().stream()
                .filter(a -> RuleAuditEntry.REJECTED.equals(a.action())
                        && ruleId.equals(a.ruleId())
                        && "并发切换应被拒绝".equals(a.reason()))
                .reduce((a, b) -> b).orElseThrow();
        assertEquals(1, rejected.ruleVersion(), "被拒时刻线上版本必须仍是 v1");
        assertTrue(rejected.summary().contains("v1"));
        log.info("[版本选择] REJECTED 留痕 {} v{} reason={} summary={}", rejected.ruleId(),
                rejected.ruleVersion(), rejected.reason(), rejected.summary());
    }

    @Test
    void trialConcurrencyAndSampleSizeAreBoundedAndRejected() throws Exception {
        final String ruleId = "gov-trial-cap";
        engine.putRule(new RuleDefinition(ruleId, 0, "temperature", 30.0,
                "alarm", true, Instant.now()));
        List<EventRecord> samples = List.of(ev("govtc1", 35.0));

        // 3d) 并发试跑占满容量（默认上限2）：第三个试跑立即 RULE_TRIAL_LIMIT
        Gate g1 = new Gate();
        Gate g2 = new Gate();
        Runnable hook1 = g1.hook();
        Runnable hook2 = g2.hook();
        java.util.concurrent.atomic.AtomicInteger picked =
                new java.util.concurrent.atomic.AtomicInteger();
        governance.setTrialBarrier(() ->
                (picked.getAndIncrement() == 0 ? hook1 : hook2).run());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        Future<RuleTrialResult> h1 = pool.submit(() ->
                governance.trial(trialRequest(ruleId, 40.0, samples, "占满-1")));
        Future<RuleTrialResult> h2 = pool.submit(() ->
                governance.trial(trialRequest(ruleId, 40.0, samples, "占满-2")));
        g1.awaitEntered();
        g2.awaitEntered();
        Future<?> rejected = pool.submit(() ->
                governance.trial(trialRequest(ruleId, 40.0, samples, "应被拒绝")));
        ExecutionException wrapped = assertThrows(ExecutionException.class,
                () -> rejected.get(10, TimeUnit.SECONDS));
        assertEquals(ErrorCode.RULE_TRIAL_LIMIT, causeOf(wrapped).code());
        log.info("[判定依据] 并发试跑超限被拒绝 code={}", causeOf(wrapped).code());
        g1.release();
        g2.release();
        h1.get(10, TimeUnit.SECONDS);
        h2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // 3e) 样本量超过配置上限同样拒绝
        List<EventRecord> tooMany = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            tooMany.add(new EventRecord("govbig" + i, "dev-gov", 1, "temperature",
                    10.0, T0.plusMillis(i)));
        }
        GatewayException sizeEx = assertThrows(GatewayException.class,
                () -> governance.trial(trialRequest(ruleId, 40.0, tooMany, "超大样本")));
        assertEquals(ErrorCode.RULE_TRIAL_LIMIT, sizeEx.code());
        log.info("[判定依据] 试跑样本超 5000 上限被拒绝 msg={}", sizeEx.getMessage());
    }


    /**
     * 确定性切换闸门（三道握手）：
     * 持有者：armed -> 等 acknowledged -> waiting（此时仍在 store 激活临界区内）-> 等 release。
     * awaitEntered 返回时，持有者必然已阻塞在 release 上、且仍持有 store 监视器，
     * 因此此刻活动版本一定尚未切换，并发拒绝的断言不存在竞态。
     */
    private static final class Gate {
        private final CountDownLatch armed = new CountDownLatch(1);
        private final CountDownLatch acknowledged = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicBoolean tripped =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final Runnable hook = () -> {
            if (tripped.compareAndSet(false, true)) {
                armed.countDown();
                try {
                    // 收到 ack 后再阻塞：此刻仍在 store 的 synchronized 激活临界区内，
                    // 主线程拒绝路径只读 volatile 镜像，不与该监视器互锁。
                    acknowledged.await(10, TimeUnit.SECONDS);
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Runnable hook() {
            return hook;
        }
        void awaitEntered() throws InterruptedException {
            assertTrue(armed.await(10, TimeUnit.SECONDS), "操作未进入闸门");
            acknowledged.countDown();
        }
        void release() {
            release.countDown();
        }
    }

    // ---- 4) 回退历史版本 + 指定历史版本复看对账（结论可复现） --------------------

    @Test
    void rollbackToHistoricalVersionAndReviewReproducesOriginalVerdict() {
        final String ruleId = "gov-rb";
        RuleDefinition v1 = engine.putRule(new RuleDefinition(ruleId, 0, "temperature",
                30.0, "alarm", true, Instant.now()));
        // v1 时期真实执行：35 命中
        RuleVerdict liveV1 = engine.submit(ev("govrb1", 35.0));

        RuleDefinition v2 = engine.putRule(new RuleDefinition(ruleId, 0, "temperature",
                40.0, "alarm", true, Instant.now()));
        // v2 时期真实执行：35 不命中
        RuleVerdict liveV2 = engine.submit(ev("govrb2", 35.0));
        log.info("[关键输入] {} v1 threshold=30 处理 govrb1；v{} threshold=40 处理 govrb2",
                ruleId, v2.version());
        assertEquals(1, liveV1.ruleVersion());
        assertTrue(liveV1.fired());
        assertEquals(2, liveV2.ruleVersion());
        assertFalse(liveV2.fired());

        // 回退到任意历史版本 v1；回退后事件按 v1 处理
        RuleDefinition rolled = governance.rollback(ruleId, 1, "新版本误报过多，回退");
        assertEquals(1, rolled.version());
        assertEquals(1, engine.getRule(ruleId).version());
        RuleVerdict afterRollback = engine.submit(ev("govrb3", 35.0));
        assertEquals(1, afterRollback.ruleVersion(), "回退后事件必须按 v1 判定");
        assertTrue(afterRollback.fired(), "回退后 v1 阈值30，35 重新命中");
        log.info("[判定依据] 回退后 govrb3 归属 v{} fired={}",
                afterRollback.ruleVersion(), afterRollback.fired());

        // 复看1：用 v1 复看 v1 时期真实处理过的事件 -> 来源 LIVE，结论就是当时落地的结论
        RuleEngineService.RuleReviewOutcome reviewLive =
                engine.review(ev("govrb1", 35.0), ruleId, 1, "对账 v1 时期事件");
        assertEquals("LIVE", reviewLive.source());
        assertEquals(1, reviewLive.verdict().ruleVersion());
        assertTrue(reviewLive.verdict().fired());
        assertEquals(liveV1, reviewLive.verdict());
        assertTrue(reviewLive.evaluation().basis().contains("v1"));

        // 复看2：用 v2 复看 v1 时期的事件 -> 当时并未在 v2 下执行，必须按 v2 规则重算，
        // 不能把新规则结果挂到旧版本，也不能把旧结论冒充 v2
        RuleEngineService.RuleReviewOutcome reviewReplayV2 =
                engine.review(ev("govrb1", 35.0), ruleId, 2, "用 v2 复看旧事件");
        assertEquals("REPLAY", reviewReplayV2.source());
        assertEquals(2, reviewReplayV2.verdict().ruleVersion());
        assertFalse(reviewReplayV2.verdict().fired(), "v2 阈值40，重算 35 不命中");

        // 复看3：用 v1 复看 v2 时期的事件 -> REPLAY，结论必须是 v1 的（命中），
        // 不能沿用它真实落地时 v2 的“不命中”结论
        RuleEngineService.RuleReviewOutcome reviewReplayV1 =
                engine.review(ev("govrb2", 35.0), ruleId, 1, "用 v1 复看 v2 时期事件");
        assertEquals("REPLAY", reviewReplayV1.source());
        assertEquals(1, reviewReplayV1.verdict().ruleVersion());
        assertTrue(reviewReplayV1.verdict().fired(),
                "v1 复看必须按 v1 规则命中，不得挂 v2 的真实结论");

        // 可复现：同一历史版本复看同一事件，重复结果逐字段一致
        RuleEngineService.RuleReviewOutcome reviewAgain =
                engine.review(ev("govrb2", 35.0), ruleId, 1, "再次复看");
        assertEquals(reviewReplayV1.verdict(), reviewAgain.verdict());
        assertEquals(reviewReplayV1.evaluation(), reviewAgain.evaluation());
        log.info("[判定依据] 复看可复现 govrb2@v1 fired={}/{} basis={}",
                reviewReplayV1.verdict().fired(), reviewAgain.verdict().fired(),
                reviewAgain.evaluation().basis());

        // 版本历史可查，回退/复看均留痕：时间/原因/版本/结论
        assertEquals(2, governance.history(ruleId).size());
        RuleAuditEntry rollbackAudit = governance.auditLog().stream()
                .filter(a -> RuleAuditEntry.ROLLBACK.equals(a.action())
                        && ruleId.equals(a.ruleId()) && a.ruleVersion() == 1)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals("新版本误报过多，回退", rollbackAudit.reason());
        long reviewAudits = governance.auditLog().stream()
                .filter(a -> RuleAuditEntry.REVIEW.equals(a.action())
                        && ruleId.equals(a.ruleId())).count();
        assertTrue(reviewAudits >= 4, "每次复看都要留痕，实际=" + reviewAudits);
        log.info("[版本选择] 回退留痕 action={} {} v{} reason={} at={}",
                rollbackAudit.action(), rollbackAudit.ruleId(), rollbackAudit.ruleVersion(),
                rollbackAudit.reason(), rollbackAudit.at());
    }

    private static GatewayException causeOf(ExecutionException e) {
        assertTrue(e.getCause() instanceof GatewayException,
                "根因应为 GatewayException，实际: " + e.getCause());
        return (GatewayException) e.getCause();
    }

    private String summarize(List<RuleTrialItem> items) {
        List<String> parts = new ArrayList<>();
        for (RuleTrialItem i : items) {
            parts.add(i.eventId() + "(current fired=" + i.current().fired()
                    + " v" + i.current().ruleVersion() + " -> candidate fired="
                    + i.candidate().fired() + ", changed=" + i.changed() + ")");
        }
        return String.join("; ", parts);
    }
}
