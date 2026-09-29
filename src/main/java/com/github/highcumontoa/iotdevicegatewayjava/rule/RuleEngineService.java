package com.github.highcumontoa.iotdevicegatewayjava.rule;

import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;
import com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.RuleAuditEntry;
import com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.RuleReviewResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.TrialReport;
import com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.VerdictComparison;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于权威数据（已成功写入的遥测所产生的事件）进行规则判定。
 *  - 规则版本化：putRule / prepareCandidate 递增 version；每个版本定义不可变、全量保留
 *    （有界），任意已发布版本都可回退、可复看，复看结论与该版本当时执行一致。
 *  - 实时判定在切换锁内取“当前生效版本”快照：一条事件只可能算在一个版本下；
 *    activate/rollback 与实时判定互斥，失败/冲突保持旧版本稳定服务，不留半生效状态。
 *  - 候选试跑为纯函数对比：不写结论、不入队、不影响实时处理与指标，重复试跑结果一致。
 *  - 处理模型：进入有界队列，串行处理，避免并发交叉；队列满时按“丢弃最旧”策略
 *    背压，丢弃数计入 droppedBackpressure 可观测指标。
 *  - 单条毒消息异常只计入 failedPoison，不拖垮链路，不影响后续事件。
 *  - 事件、结论、版本历史、审计记录均有界留存，超出容量丢弃最旧或明确拒绝，内存不泄漏。
 */
@Service
public class RuleEngineService {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);

    /** ruleId -> 当前生效版本（实时判定只认这里的快照）。 */
    private final Map<String, RuleDefinition> rules = new ConcurrentHashMap<>();
    /** ruleId -> 版本号 -> 不可变版本定义，全量保留（有界），供回退/复看/试跑取用。 */
    private final Map<String, NavigableMap<Integer, RuleDefinition>> ruleHistory = new ConcurrentHashMap<>();
    /** ruleId -> 已准备但尚未启用的候选版本（每个规则至多一个，再次准备覆盖）。 */
    private final Map<String, RuleDefinition> candidates = new ConcurrentHashMap<>();
    /** ruleId -> 单调版本号。 */
    private final Map<String, Integer> ruleVersions = new ConcurrentHashMap<>();

    private final ArrayDeque<EventRecord> queue = new ArrayDeque<>();
    private final Map<String, List<RuleVerdict>> verdictsByEvent = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<RuleVerdict>> verdictsByDevice = new ConcurrentHashMap<>();
    /** 实时事件留存（有界，全局），供试跑圈选历史样本与复看取原始事件。 */
    private final ArrayDeque<EventRecord> storedEvents = new ArrayDeque<>();
    private final Map<String, EventRecord> eventIndex = new ConcurrentHashMap<>();
    /** 治理操作留痕（有界，全局有序）。 */
    private final ArrayDeque<RuleAuditEntry> auditLog = new ArrayDeque<>();

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong droppedBackpressure = new AtomicLong();
    private final AtomicLong failedPoison = new AtomicLong();
    private final AtomicLong auditSeq = new AtomicLong();

    private final com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties properties;
    private final Clock clock;
    /** 队列、结论、事件留存等内部结构的串行化锁。 */
    private final ReentrantLock queueLock = new ReentrantLock();
    /**
     * 版本切换锁：activate/rollback 与实时事件判定互斥，保证切换边界清晰。
     * tryLock 失败（已有切换/判定在临界区）明确拒绝，调用方可重试，绝不产生半生效状态。
     */
    private final ReentrantLock switchLock = new ReentrantLock();
    private volatile java.util.function.Predicate<EventRecord> poisonMatcher;

    /** 测试钩子：匹配的事件在判定时抛出底层异常，验证毒消息隔离。 */
    public void setPoisonMatcher(java.util.function.Predicate<EventRecord> poisonMatcher) {
        this.poisonMatcher = poisonMatcher;
    }

    /** 测试钩子：切换期间阻塞实时判定，用于稳定复现“切换边界”与并发归属。 */
    public void setBeforeEvaluateHook(Runnable beforeEvaluateHook) {
        this.beforeEvaluateHook = beforeEvaluateHook;
    }

    private volatile Runnable beforeEvaluateHook;

    /** 测试钩子：在切换临界区内（占住切换锁、尚未替换版本）阻塞，用于稳定复现切换撞车。 */
    public void setBeforeSwitchHook(Runnable beforeSwitchHook) {
        this.beforeSwitchHook = beforeSwitchHook;
    }

    private volatile Runnable beforeSwitchHook;

    public RuleEngineService(
            com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties properties,
            Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 新增或更新规则；version 由引擎单调分配，客户端给的 version 仅作展示校验。 */
    public RuleDefinition putRule(RuleDefinition rule) {
        if (rule == null || rule.ruleId() == null || rule.metricField() == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "规则缺少 ruleId/metricField");
        }
        RuleDefinition stored;
        switchLock.lock();
        try {
            boolean firstVersion = !ruleHistory.containsKey(rule.ruleId());
            if (firstVersion && ruleHistory.size() >= properties.getMaxRules()) {
                throw new GatewayException(ErrorCode.QUOTA_EXCEEDED,
                        "规则总数已达上限 " + properties.getMaxRules()
                                + "，拒绝新增规则（不影响现有规则服务）");
            }
            int version = ruleVersions.merge(rule.ruleId(), 1, Integer::sum);
            stored = new RuleDefinition(rule.ruleId(), version, rule.metricField(),
                    rule.threshold(), rule.action(), rule.enabled(), Instant.now(clock));
            publishVersion(stored);
            // 兼容原语义：putRule 后立即生效
            rules.put(rule.ruleId(), stored);
            candidates.remove(rule.ruleId());
        } finally {
            switchLock.unlock();
        }
        log.info("规则已更新 ruleId={} version={} field={} threshold={} action={} enabled={}",
                stored.ruleId(), stored.version(), stored.metricField(), stored.threshold(),
                stored.action(), stored.enabled());
        return stored;
    }

    /** 登记一个不可变版本定义到版本历史（有界：超限时拒绝新版本，不留半写入）。 */
    private void publishVersion(RuleDefinition stored) {
        NavigableMap<Integer, RuleDefinition> history =
                ruleHistory.computeIfAbsent(stored.ruleId(), k -> new ConcurrentSkipListMap<>());
        if (!history.isEmpty() && history.size() >= properties.getMaxRuleVersionsPerRule()) {
            throw new GatewayException(ErrorCode.CAPACITY_EXCEEDED,
                    "规则 " + stored.ruleId() + " 的历史版本已达上限 "
                            + properties.getMaxRuleVersionsPerRule() + "，拒绝登记新版本");
        }
        history.put(stored.version(), stored);
    }

    public RuleDefinition getRule(String ruleId) {
        RuleDefinition rule = rules.get(ruleId);
        if (rule == null) {
            throw new GatewayException(ErrorCode.RULE_NOT_FOUND, "规则不存在: " + ruleId);
        }
        return rule;
    }

    /**
     * 提交事件并同步串行处理至完成：保证 submit 返回时结论已落定，测试可立即断言。
     * 同一 eventId 重复提交幂等：直接返回首次权威结论，不重复判定、不重复计数。
     * 队列满 -> 背压丢弃最旧事件（并计数），新事件入队。
     */
    public RuleVerdict submit(EventRecord event) {
        if (event == null || event.eventId() == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "事件为空");
        }
        RuleVerdict existing = existingVerdict(event.eventId());
        if (existing != null) {
            log.info("事件重复提交幂等返回 eventId={} rule={} v{} fired={}",
                    event.eventId(), existing.ruleId(), existing.ruleVersion(), existing.fired());
            return existing;
        }
        EventRecord displaced = null;
        queueLock.lock();
        try {
            submitted.incrementAndGet();
            if (queue.size() >= properties.getRuleQueueCapacity()) {
                displaced = queue.pollFirst();
                droppedBackpressure.incrementAndGet();
                log.warn("规则队列背压：丢弃最旧事件 eventId={} depth={}",
                        displaced == null ? null : displaced.eventId(), queue.size());
            }
            queue.addLast(event);
        } finally {
            queueLock.unlock();
        }
        if (displaced != null) {
            log.warn("被背压丢弃的事件无结论 eventId={} deviceId={}",
                    displaced.eventId(), displaced.deviceId());
        }
        return processOne(event);
    }

    /**
     * 仅入队、不处理：用于复现“处理积压”场景并验证背压策略。
     * 队列满时按策略丢弃最旧事件并计入 droppedBackpressure；返回被挤出的事件（可能为 null）。
     */
    public EventRecord enqueueWithoutProcessing(EventRecord event) {
        if (event == null || event.eventId() == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "事件为空");
        }
        EventRecord displaced;
        queueLock.lock();
        try {
            submitted.incrementAndGet();
            displaced = null;
            if (queue.size() >= properties.getRuleQueueCapacity()) {
                displaced = queue.pollFirst();
                droppedBackpressure.incrementAndGet();
                log.warn("规则队列背压：丢弃最旧事件 eventId={} depth={}",
                        displaced == null ? null : displaced.eventId(), queue.size());
            }
            queue.addLast(event);
        } finally {
            queueLock.unlock();
        }
        return displaced;
    }

    /** 串行处理当前积压队列中的全部事件；单条毒消息不影响其余处理。 */
    public void drainQueue() {
        List<EventRecord> snapshot;
        queueLock.lock();
        try {
            snapshot = new ArrayList<>(queue);
        } finally {
            queueLock.unlock();
        }
        for (EventRecord event : snapshot) {
            queueLock.lock();
            try {
                if (!queue.contains(event)) {
                    continue;
                }
            } finally {
                queueLock.unlock();
            }
            processOne(event);
        }
    }

    private RuleVerdict processOne(EventRecord event) {
        // 已被其他线程判定过：幂等返回权威结论，不重复、不漏算、不重复计数。
        RuleVerdict duplicate = existingVerdict(event.eventId());
        if (duplicate != null) {
            queueLock.lock();
            try {
                queue.remove(event);
            } finally {
                queueLock.unlock();
            }
            return duplicate;
        }
        RuleVerdict verdict;
        boolean poisoned = false;
        // 版本快照与判定在切换锁内完成：与 activate/rollback 互斥，
        // 一条事件只可能整体按旧版本或整体按新版本判定，不会跨版本半截判定。
        switchLock.lock();
        try {
            Runnable hook = beforeEvaluateHook;
            if (hook != null) {
                hook.run();
            }
            RuleDefinition rule;
            try {
                var matcher = poisonMatcher;
                if (matcher != null && matcher.test(event)) {
                    throw new IllegalStateException("模拟底层异常（毒消息）");
                }
                // 取当前权威规则快照（版本绑定），无规则则不命中也留痕，结论可复现。
                rule = pickRuleFor(event.metricField());
                boolean fired = rule != null && rule.enabled()
                        && event.value() > rule.threshold();
                verdict = new RuleVerdict(event.eventId(),
                        rule == null ? null : rule.ruleId(),
                        rule == null ? 0 : rule.version(),
                        fired,
                        fired ? rule.action() : null,
                        Instant.now(clock));
            } catch (Exception e) {
                // 毒消息隔离：计数并继续，不抛出影响调用链的其他处理
                poisoned = true;
                log.error("规则处理毒消息隔离 eventId={} error={}",
                        event.eventId(), e.toString());
                verdict = new RuleVerdict(event.eventId(), null, -1, false,
                        null, Instant.now(clock));
            }
        } finally {
            switchLock.unlock();
        }
        queueLock.lock();
        try {
            // 双检：切换锁释放后可能有并发线程已完成同一事件，结论只落一次。
            RuleVerdict raced = existingVerdict(event.eventId());
            if (raced != null) {
                queue.remove(event);
                return raced;
            }
            persistVerdict(event, verdict);
            queue.remove(event);
        } finally {
            queueLock.unlock();
        }
        if (poisoned) {
            failedPoison.incrementAndGet();
        }
        processed.incrementAndGet();
        log.info("规则判定 eventId={} deviceId={} rule={} v{} value={} threshold={} fired={}",
                event.eventId(), event.deviceId(), verdict.ruleId(), verdict.ruleVersion(),
                event.value(), verdict.ruleVersion() <= 0 ? null
                        : ruleVersionThreshold(event.metricField(), verdict.ruleVersion()),
                verdict.fired());
        return verdict;
    }

    private Double ruleVersionThreshold(String metricField, int version) {
        for (RuleDefinition rule : rules.values()) {
            if (rule.metricField().equals(metricField) && rule.version() == version) {
                return rule.threshold();
            }
        }
        return null;
    }

    private RuleDefinition pickRuleFor(String metricField) {
        RuleDefinition result = null;
        for (RuleDefinition rule : rules.values()) {
            if (rule.metricField().equals(metricField) && rule.enabled()) {
                result = rule;
            }
        }
        return result;
    }

    private void persistVerdict(EventRecord event, RuleVerdict verdict) {
        verdictsByEvent.computeIfAbsent(event.eventId(), k ->
                Collections.synchronizedList(new ArrayList<>())).add(verdict);
        ArrayDeque<RuleVerdict> deque = verdictsByDevice.computeIfAbsent(
                event.deviceId(), k -> new ArrayDeque<>());
        deque.addLast(verdict);
        while (deque.size() > properties.getMaxEventsPerDevice()) {
            deque.removeFirst();
        }
        // 留存原始事件（全局有界），供试跑圈选历史样本与按版本复看。
        if (!eventIndex.containsKey(event.eventId())) {
            storedEvents.addLast(event);
            eventIndex.put(event.eventId(), event);
            while (storedEvents.size() > properties.getMaxStoredEvents()) {
                EventRecord oldest = storedEvents.pollFirst();
                if (oldest != null) {
                    eventIndex.remove(oldest.eventId());
                }
            }
        }
    }

    private RuleVerdict existingVerdict(String eventId) {
        List<RuleVerdict> all = verdictsByEvent.get(eventId);
        if (all == null || all.isEmpty()) {
            return null;
        }
        synchronized (all) {
            return all.get(all.size() - 1);
        }
    }

    public List<RuleVerdict> verdicts(String eventId) {
        return List.copyOf(verdictsByEvent.getOrDefault(eventId, List.of()));
    }

    public List<RuleVerdict> deviceVerdicts(String deviceId) {
        queueLock.lock();
        try {
            ArrayDeque<RuleVerdict> deque = verdictsByDevice.get(deviceId);
            return deque == null ? List.of() : List.copyOf(deque);
        } finally {
            queueLock.unlock();
        }
    }

    /** 用指定历史版本规则重放历史事件（复现）：取该版本定义现场重算，不改动任何落地结论。 */
    public RuleVerdict replay(EventRecord event, int ruleVersion) {
        RuleDefinition rule = versionOf(null, event.metricField(), ruleVersion);
        boolean fired = rule.enabled() && event.value() > rule.threshold();
        RuleVerdict verdict = new RuleVerdict(event.eventId(), rule.ruleId(), ruleVersion,
                fired, fired ? rule.action() : null, Instant.now(clock));
        log.info("规则重放 eventId={} rule={} ruleVersion={} fired={}",
                event.eventId(), rule.ruleId(), ruleVersion, fired);
        return verdict;
    }

    public RuleBackpressureStats stats() {
        queueLock.lock();
        try {
            return new RuleBackpressureStats(
                    properties.getRuleQueueCapacity(),
                    queue.size(),
                    submitted.get(),
                    processed.get(),
                    droppedBackpressure.get(),
                    failedPoison.get());
        } finally {
            queueLock.unlock();
        }
    }

    // ===== 规则治理（候选试跑/切换/回退/历史复看） =====

    /** 取某规则的指定版本定义；ruleId 为空时按 metricField 在全部历史中定位。 */
    private RuleDefinition versionOf(String ruleId, String metricField, int version) {
        if (ruleId != null) {
            NavigableMap<Integer, RuleDefinition> history = ruleHistory.get(ruleId);
            RuleDefinition found = history == null ? null : history.get(version);
            if (found == null) {
                throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                        "规则 " + ruleId + " 不存在或无历史版本 v" + version);
            }
            return found;
        }
        for (NavigableMap<Integer, RuleDefinition> history : ruleHistory.values()) {
            RuleDefinition found = history.get(version);
            if (found != null && found.metricField().equals(metricField)) {
                return found;
            }
        }
        throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                "无字段 " + metricField + " 的规则版本 v" + version + " 可用于重放");
    }

    /** 追加一条有界审计记录，返回已落库的条目。 */
    private RuleAuditEntry audit(String action, String ruleId, Integer fromVersion,
                                 Integer toVersion, String reason, String outcome,
                                 String detail) {
        RuleAuditEntry entry;
        queueLock.lock();
        try {
            entry = new RuleAuditEntry(auditSeq.incrementAndGet(), action, ruleId,
                    fromVersion, toVersion, reason, outcome, detail, Instant.now(clock));
            auditLog.addLast(entry);
            while (auditLog.size() > properties.getMaxAuditEntries()) {
                auditLog.pollFirst();
            }
        } finally {
            queueLock.unlock();
        }
        log.info("规则治理留痕 seq={} action={} rule={} {}->{} outcome={} reason={}",
                entry.seq(), action, ruleId, fromVersion, toVersion, outcome, reason);
        return entry;
    }

    /** 纯函数：用给定版本定义对事件做判定，不触碰任何共享状态，保证可复现。 */
    private RuleVerdict evaluate(EventRecord event, RuleDefinition rule) {
        boolean fired = rule.enabled() && event.value() > rule.threshold();
        return new RuleVerdict(event.eventId(), rule.ruleId(), rule.version(),
                fired, fired ? rule.action() : null, null);
    }

    /**
     * 准备候选版本：只登记为“待启用候选”，不改变当前生效版本，不影响实时处理与已落地结论。
     * 候选版本号在该规则版本序列上单调分配；容量上限打满时明确拒绝（不产生半成品版本）。
     */
    public RuleDefinition prepareCandidate(RuleDefinition candidate, String reason) {
        if (candidate == null || candidate.ruleId() == null
                || candidate.metricField() == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "候选规则缺少 ruleId/metricField");
        }
        String ruleId = candidate.ruleId();
        NavigableMap<Integer, RuleDefinition> history = ruleHistory.get(ruleId);
        if (history == null || history.isEmpty()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "规则 " + ruleId + " 尚未发布过，请先 POST /api/rules 创建首版本");
        }
        RuleDefinition current = rules.get(ruleId);
        if (current == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "规则 " + ruleId + " 当前无生效版本，无法准备候选");
        }
        if (!current.metricField().equals(candidate.metricField())) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "候选规则的 metricField 必须与已发布规则一致：" + current.metricField());
        }
        RuleDefinition stored;
        switchLock.lock();
        try {
            int nextVersion = ruleVersions.merge(ruleId, 1, Integer::sum);
            stored = new RuleDefinition(ruleId, nextVersion, candidate.metricField(),
                    candidate.threshold(), candidate.action(), candidate.enabled(),
                    Instant.now(clock));
            try {
                publishVersion(stored);
            } catch (GatewayException e) {
                // 版本历史容量打满：回滚版本号分配，不留半成品
                ruleVersions.put(ruleId, nextVersion - 1);
                throw e;
            }
            candidates.put(ruleId, stored);
        } finally {
            switchLock.unlock();
        }
        log.info("候选规则已准备 ruleId={} candidateVersion={}（当前生效仍为 v{}）",
                ruleId, stored.version(), current.version());
        return stored;
    }

    /**
     * 历史试跑：同一批样本分别用当前生效版本与候选版本纯函数判定，逐条对比并汇总差异。
     * 不写结论、不入队、不改变任何指标，重复试跑结果完全一致。
     */
    public TrialReport runTrial(String ruleId, Integer candidateVersion,
                                List<String> eventIds, List<EventRecord> samples,
                                String reason) {
        RuleDefinition baseline = rules.get(ruleId);
        if (baseline == null) {
            throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                    "规则 " + ruleId + " 无生效版本，无法试跑");
        }
        RuleDefinition candidate;
        if (candidateVersion == null || candidateVersion == 0) {
            candidate = candidates.get(ruleId);
            if (candidate == null) {
                throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                        "规则 " + ruleId + " 尚无候选版本，请先准备候选");
            }
        } else {
            NavigableMap<Integer, RuleDefinition> history = ruleHistory.get(ruleId);
            candidate = history == null ? null : history.get(candidateVersion);
            if (candidate == null) {
                throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                        "规则 " + ruleId + " 不存在历史版本 v" + candidateVersion);
            }
        }
        List<EventRecord> chosen = pickTrialSamples(ruleId, baseline, candidate,
                eventIds, samples);
        List<VerdictComparison> items = new ArrayList<>(chosen.size());
        int changed = 0;
        for (EventRecord event : chosen) {
            RuleVerdict b = evaluate(event, baseline);
            RuleVerdict c = evaluate(event, candidate);
            boolean diff = b.fired() != c.fired()
                    || !java.util.Objects.equals(b.action(), c.action());
            if (diff) {
                changed++;
            }
            String why = !b.fired() && c.fired() ? "新增命中"
                    : b.fired() && !c.fired() ? "不再命中"
                    : diff ? "命中动作变化" : "结论一致";
            items.add(new VerdictComparison(event.eventId(), event.deviceId(),
                    event.metricField(), event.value(), ruleId,
                    b.ruleVersion(), b.fired(), b.action(),
                    c.ruleVersion(), c.fired(), c.action(), diff, why));
        }
        TrialReport report = new TrialReport(ruleId, baseline.version(), candidate.version(),
                chosen.size(), changed, List.copyOf(items));
        audit("TRIAL", ruleId, baseline.version(), candidate.version(), reason, "SUCCESS",
                "样本=" + chosen.size() + " 结论变化=" + changed);
        log.info("候选规则历史试跑 ruleId={} baseline=v{} candidate=v{} samples={} changed={}",
                ruleId, baseline.version(), candidate.version(), chosen.size(), changed);
        return report;
    }

    private List<EventRecord> pickTrialSamples(String ruleId, RuleDefinition baseline,
                                               RuleDefinition candidate,
                                               List<String> eventIds,
                                               List<EventRecord> samples) {
        boolean byIds = eventIds != null && !eventIds.isEmpty();
        boolean bySamples = samples != null && !samples.isEmpty();
        if (byIds == bySamples) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "试跑必须且只能提供一种样本来源：eventIds（历史事件）或 samples（内联样本）");
        }
        if (bySamples) {
            List<EventRecord> chosen = new ArrayList<>(samples.size());
            for (EventRecord event : samples) {
                if (event == null || event.eventId() == null) {
                    throw new GatewayException(ErrorCode.INVALID_REQUEST, "试跑样本事件为空");
                }
                if (!baseline.metricField().equals(event.metricField())) {
                    throw new GatewayException(ErrorCode.INVALID_REQUEST,
                            "样本 " + event.eventId() + " 的 metricField=" + event.metricField()
                                    + " 与规则字段 " + baseline.metricField() + " 不匹配");
                }
                chosen.add(event);
            }
            return chosen;
        }
        List<EventRecord> chosen = new ArrayList<>(eventIds.size());
        for (String eventId : eventIds) {
            EventRecord event = eventIndex.get(eventId);
            if (event == null) {
                throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                        "历史事件不存在或已超出留存窗口: " + eventId);
            }
            if (!baseline.metricField().equals(event.metricField())) {
                throw new GatewayException(ErrorCode.INVALID_REQUEST,
                        "历史事件 " + eventId + " 的 metricField 与规则字段 "
                                + baseline.metricField() + " 不匹配");
            }
            chosen.add(event);
        }
        return chosen;
    }

    /**
     * 正式启用指定已发布版本（通常是候选版本）。与实时事件判定互斥：
     * tryLock 失败（并发切换或压力过大）明确拒绝 RULE_SWITCH_CONFLICT，旧版本继续稳定服务；
     * 目标版本非法或不存在也拒绝，绝不留下半生效状态。
     */
    public RuleDefinition activate(String ruleId, int version, String reason) {
        return switchVersion(ruleId, version, reason, "ACTIVATE");
    }

    /** 回退到任意已发布历史版本；成功后实时事件按回退后版本处理。 */
    public RuleDefinition rollback(String ruleId, int version, String reason) {
        return switchVersion(ruleId, version, reason, "ROLLBACK");
    }

    /**
     * 拒绝留痕 detail 的稳定前缀（机读标记，措辞不随线程时序变化，便于按类型分开追溯）。
     * REJECT_BUSY：并发繁忙拒绝（可重试）；REJECT_SAME：目标即当前版本（无操作）；
     * REJECT_MISSING：目标版本不存在。
     */
    static final String REJECT_BUSY = "REJECT_BUSY";
    static final String REJECT_SAME = "REJECT_SAME";
    static final String REJECT_MISSING = "REJECT_MISSING";

    private RuleDefinition switchVersion(String ruleId, int version, String reason,
                                         String action) {
        RuleDefinition existing = rules.get(ruleId);
        if (existing == null) {
            throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                    "规则 " + ruleId + " 不存在，无法" + action);
        }
        NavigableMap<Integer, RuleDefinition> history = ruleHistory.get(ruleId);
        RuleDefinition target = history == null ? null : history.get(version);
        if (target == null) {
            audit(action, ruleId, existing.version(), version, reason, "REJECTED",
                    REJECT_MISSING + "：目标历史版本不存在，当前仍稳定生效 v"
                            + existing.version());
            throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                    "规则 " + ruleId + " 不存在历史版本 v" + version);
        }
        // tryLock 失败只代表“此刻繁忙”：明确拒绝、不触碰任何版本状态，
        // 留痕中 fromVersion 必须是拒绝时真正生效的版本，调用方稍后重试即可。
        if (!switchLock.tryLock()) {
            RuleDefinition busyCurrent = rules.get(ruleId);
            audit(action, ruleId,
                    busyCurrent == null ? null : busyCurrent.version(), version,
                    reason, "REJECTED",
                    REJECT_BUSY + "：切换与实时判定/另一切换并发，明确拒绝，"
                            + "旧版本继续稳定服务，可稍后重试");
            throw new GatewayException(ErrorCode.RULE_SWITCH_CONFLICT,
                    "规则切换繁忙（与实时判定并发），已拒绝本次切换，"
                            + "当前仍稳定生效 v" + (busyCurrent == null ? "?"
                                    : busyCurrent.version()) + "，请稍后重试");
        }
        // 进入临界区后重新读取当前生效版本：所有版本判定（同版本拒绝/实际迁移版本）
        // 都以锁内快照为准，消除锁外读旧值导致的留痕失真与重试时盲覆盖新引用。
        RuleDefinition current;
        try {
            current = rules.get(ruleId);
            if (current == null) {
                throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                        "规则 " + ruleId + " 不存在，无法" + action);
            }
            if (current.version() == version) {
                // 目标即当前版本（典型场景：并发的另一次切换已完成，本次请求/重试落到同版本）：
                // 幂等拒绝，不产生重复 SUCCESS 留痕，也不移动任何引用。
                audit(action, ruleId, current.version(), version, reason, "REJECTED",
                        REJECT_SAME + "：目标版本即当前生效版本，切换被拒绝（无操作）");
                throw new GatewayException(ErrorCode.RULE_SWITCH_CONFLICT,
                        "规则 " + ruleId + " 当前已生效 v" + version + "，无需切换");
            }
            Runnable switchHook = beforeSwitchHook;
            if (switchHook != null) {
                switchHook.run();
            }
            // 临界区内不允许有实时判定穿插：rules 引用整体替换，事件要么全按旧、要么全按新。
            rules.put(ruleId, target);
            candidates.remove(ruleId);
        } finally {
            switchLock.unlock();
        }
        audit(action, ruleId, current.version(), target.version(), reason, "SUCCESS",
                "切换后实时事件按 v" + target.version() + " 判定");
        log.info("规则{}完成 ruleId={} v{} -> v{}",
                "ROLLBACK".equals(action) ? "回退" : "启用",
                ruleId, current.version(), target.version());
        return target;
    }

    /**
     * 按历史版本复看事件：用该版本的不可变定义现场重算，结论与该版本当时真实执行一致，
     * 绝不把新规则结果挂到旧版本名下。可使用传入样本或已留存历史事件；不改变任何落地结论。
     */
    public RuleReviewResult review(String ruleId, int version, EventRecord event) {
        if (event == null || event.eventId() == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "复看事件为空");
        }
        RuleDefinition rule = versionOf(ruleId, null, version);
        EventRecord stored = eventIndex.get(event.eventId());
        EventRecord source = stored != null ? stored : event;
        boolean matched = rule.metricField().equals(source.metricField());
        RuleVerdict replayed;
        String basis;
        if (matched) {
            replayed = evaluate(source, rule);
            basis = "字段=" + rule.metricField() + "，阈值=" + rule.threshold()
                    + "，事件值=" + source.value() + "，比较 value > threshold"
                    + "，enabled=" + rule.enabled();
        } else {
            // 字段不匹配：该版本规则对这条事件根本不适用，结论只能是不命中，
            // 且绝不能用其它字段的规则结果冒充。
            replayed = new RuleVerdict(source.eventId(), rule.ruleId(), version,
                    false, null, null);
            basis = "事件字段 " + source.metricField() + " 与规则字段 "
                    + rule.metricField() + " 不匹配，该版本下不适用、不命中";
        }
        RuleVerdict original = existingVerdict(source.eventId());
        RuleReviewResult result = new RuleReviewResult(source.eventId(), ruleId, version,
                matched, basis, replayed, original);
        audit("REVIEW", ruleId, null, version,
                "复看事件 " + source.eventId(), "SUCCESS",
                "matched=" + matched + " fired=" + replayed.fired());
        log.info("历史版本复看 eventId={} ruleId={} v{} matched={} fired={}",
                source.eventId(), ruleId, version, matched, replayed.fired());
        return result;
    }

    /** 某规则全部已发布版本（含候选，按版本号升序）。 */
    public List<RuleDefinition> ruleHistory(String ruleId) {
        NavigableMap<Integer, RuleDefinition> history = ruleHistory.get(ruleId);
        if (history == null || history.isEmpty()) {
            throw new GatewayException(ErrorCode.RULE_NOT_FOUND, "规则不存在: " + ruleId);
        }
        return List.copyOf(history.values());
    }

    /** 治理操作留痕（试跑/切换/回退/复看），按时间升序；ruleId 为空返回全部。 */
    public List<RuleAuditEntry> auditTrail(String ruleId) {
        queueLock.lock();
        try {
            if (ruleId == null || ruleId.isBlank()) {
                return List.copyOf(auditLog);
            }
            List<RuleAuditEntry> filtered = new ArrayList<>();
            for (RuleAuditEntry entry : auditLog) {
                if (ruleId.equals(entry.ruleId())) {
                    filtered.add(entry);
                }
            }
            return List.copyOf(filtered);
        } finally {
            queueLock.unlock();
        }
    }
}
