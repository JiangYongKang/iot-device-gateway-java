package com.github.highcumontoa.iotdevicegatewayjava.rule;

import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleAuditEntry;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleEvaluation;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 基于权威数据（已成功写入的遥测所产生的事件）进行规则判定。
 *  - 规则版本化：发布新版本递增 version；活动版本由 {@link RuleVersionStore} 原子切换。
 *    每条事件在处理前取一次“当前生效规则快照”，整条事件只绑定这一个版本，
 *    切换边界清晰，不会出现同一条事件一部分按旧规则、一部分按新规则。
 *  - 切换并存：实时提交与规则切换共用 store 内的同一把锁，切换期间处理仍可进行
 *    （快照要么全旧、要么全新）；切换失败保持旧版本稳定服务。
 *  - 处理模型：进入有界队列，串行处理，避免并发交叉；队列满时按“丢弃最旧”策略
 *    背压，丢弃数计入 droppedBackpressure 可观测指标。
 *  - 单条毒消息异常只计入 failedPoison，不拖垮链路，不影响后续事件。
 *  - 历史复看：review 取当时真实落地结论（live 来源）；若该版本下事件未在线上执行，
 *    则用该历史版本规则确定性重算（replay 来源），绝不把新规则结果挂到旧版本名下。
 */
@Service
public class RuleEngineService {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);

    private final RuleVersionStore store;
    private final com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties properties;
    private final Clock clock;

    private final ArrayDeque<EventRecord> queue = new ArrayDeque<>();
    private final java.util.Map<String, List<RuleVerdict>> verdictsByEvent =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, ArrayDeque<RuleVerdict>> verdictsByDevice =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong droppedBackpressure = new AtomicLong();
    private final AtomicLong failedPoison = new AtomicLong();
    private final Object queueLock = new Object();
    private volatile Predicate<EventRecord> poisonMatcher;

    /** 测试钩子：匹配的事件在判定时抛出底层异常，验证毒消息隔离。 */
    public void setPoisonMatcher(Predicate<EventRecord> poisonMatcher) {
        this.poisonMatcher = poisonMatcher;
    }

    public RuleEngineService(
            RuleVersionStore store,
            com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties properties,
            Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 新增或更新规则并立即发布激活；version 由版本存储单调分配。
     * 发布与活动引用切换在同一把锁内原子完成。
     */
    public RuleDefinition putRule(RuleDefinition rule) {
        return store.publish(rule, true);
    }

    public RuleDefinition getRule(String ruleId) {
        RuleDefinition rule = store.active(ruleId);
        if (rule == null) {
            throw new GatewayException(ErrorCode.RULE_NOT_FOUND, "规则不存在: " + ruleId);
        }
        return rule;
    }

    /**
     * 提交事件并同步串行处理至完成：保证 submit 返回时结论已落定，测试可立即断言。
     * 队列满 -> 背压丢弃最旧事件（并计数），新事件入队。
     */
    public RuleVerdict submit(EventRecord event) {
        if (event == null || event.eventId() == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "事件为空");
        }
        EventRecord displaced = null;
        synchronized (queueLock) {
            submitted.incrementAndGet();
            if (queue.size() >= properties.getRuleQueueCapacity()) {
                displaced = queue.pollFirst();
                droppedBackpressure.incrementAndGet();
                log.warn("规则队列背压：丢弃最旧事件 eventId={} depth={}",
                        displaced == null ? null : displaced.eventId(), queue.size());
            }
            queue.addLast(event);
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
        synchronized (queueLock) {
            submitted.incrementAndGet();
            displaced = null;
            if (queue.size() >= properties.getRuleQueueCapacity()) {
                displaced = queue.pollFirst();
                droppedBackpressure.incrementAndGet();
                log.warn("规则队列背压：丢弃最旧事件 eventId={} depth={}",
                        displaced == null ? null : displaced.eventId(), queue.size());
            }
            queue.addLast(event);
        }
        return displaced;
    }

    /** 串行处理当前积压队列中的全部事件；单条毒消息不影响其余处理。 */
    public void drainQueue() {
        List<EventRecord> snapshot;
        synchronized (queueLock) {
            snapshot = new ArrayList<>(queue);
        }
        for (EventRecord event : snapshot) {
            synchronized (queueLock) {
                if (!queue.contains(event)) {
                    continue;
                }
            }
            processOne(event);
        }
    }

    private RuleVerdict processOne(EventRecord event) {
        try {
            var matcher = poisonMatcher;
            if (matcher != null && matcher.test(event)) {
                throw new IllegalStateException("模拟底层异常（毒消息）");
            }
            // 取当前权威规则快照（版本绑定）：一次快照贯穿整条事件的判定。
            RuleDefinition rule = pickRuleFor(event.metricField());
            RuleEvaluation evaluation = rule == null
                    ? RuleEvaluator.unmatched(event)
                    : RuleEvaluator.evaluate(event, rule);
            RuleVerdict verdict = new RuleVerdict(event.eventId(),
                    evaluation.ruleId(),
                    rule == null ? 0 : rule.version(),
                    evaluation.fired(),
                    evaluation.action(),
                    Instant.now(clock));
            persistVerdict(event, verdict);
            processed.incrementAndGet();
            log.info("规则判定 eventId={} deviceId={} rule={} v{} value={} threshold={} fired={} basis={}",
                    event.eventId(), event.deviceId(), verdict.ruleId(), verdict.ruleVersion(),
                    event.value(), rule == null ? null : rule.threshold(), evaluation.fired(),
                    evaluation.basis());
            return verdict;
        } catch (Exception e) {
            // 毒消息隔离：计数并继续，不抛出影响调用链的其他处理
            failedPoison.incrementAndGet();
            log.error("规则处理毒消息隔离 eventId={} error={}", event.eventId(), e.toString());
            RuleVerdict error = new RuleVerdict(event.eventId(), null, -1, false,
                    null, Instant.now(clock));
            persistVerdict(event, error);
            processed.incrementAndGet();
            return error;
        } finally {
            synchronized (queueLock) {
                queue.remove(event);
            }
        }
    }

    private RuleDefinition pickRuleFor(String metricField) {
        // 活动快照按 ruleId 有序，确定选最后一个字段匹配的启用规则，结果可复现。
        RuleDefinition result = null;
        for (RuleDefinition rule : store.activeSnapshot()) {
            if (rule.metricField().equals(metricField) && rule.enabled()) {
                result = rule;
            }
        }
        return result;
    }

    private void persistVerdict(EventRecord event, RuleVerdict verdict) {
        verdictsByEvent.computeIfAbsent(event.eventId(), k ->
                java.util.Collections.synchronizedList(new ArrayList<>())).add(verdict);
        synchronized (queueLock) {
            ArrayDeque<RuleVerdict> deque = verdictsByDevice.computeIfAbsent(
                    event.deviceId(), k -> new ArrayDeque<>());
            deque.addLast(verdict);
            while (deque.size() > properties.getMaxEventsPerDevice()) {
                deque.removeFirst();
            }
        }
    }

    public List<RuleVerdict> verdicts(String eventId) {
        return List.copyOf(verdictsByEvent.getOrDefault(eventId, List.of()));
    }

    public List<RuleVerdict> deviceVerdicts(String deviceId) {
        synchronized (queueLock) {
            ArrayDeque<RuleVerdict> deque = verdictsByDevice.get(deviceId);
            return deque == null ? List.of() : List.copyOf(deque);
        }
    }

    /**
     * 指定历史版本确定性重算（复看兜底入口）：必须能在版本历史中找到该版本，
     * 且按该版本当时的规则内容判定，而不是当前最新规则。
     * 结论时间取事件发生时间，同一事件+同一版本重复重放结果逐字段一致。
     */
    public RuleVerdict replay(EventRecord event, int ruleVersion) {
        RuleDefinition rule = findRuleForFieldAtVersion(event.metricField(), ruleVersion);
        RuleEvaluation evaluation = RuleEvaluator.evaluate(event, rule);
        RuleVerdict verdict = new RuleVerdict(event.eventId(), rule.ruleId(), ruleVersion,
                evaluation.fired(), evaluation.action(), event.occurredAt());
        log.info("规则历史重放 eventId={} rule={} v{} fired={} basis={}",
                event.eventId(), rule.ruleId(), ruleVersion, evaluation.fired(),
                evaluation.basis());
        return verdict;
    }

    private RuleDefinition findRuleForFieldAtVersion(String metricField, int ruleVersion) {
        for (String ruleId : store.activeVersionsView().keySet()) {
            RuleDefinition candidate = store.getVersion(ruleId, ruleVersion);
            if (candidate != null && candidate.metricField().equals(metricField)) {
                return candidate;
            }
        }
        throw new GatewayException(ErrorCode.RULE_VERSION_NOT_FOUND,
                "无规则版本 v" + ruleVersion + " 可用于字段[" + metricField + "]复看");
    }

    /**
     * 历史复看：指定历史版本查看一条过去的事件，返回当时真实结论与判断依据。
     *  - source=LIVE：该事件在该版本下真实在线上执行过，返回的就是当时落地结论；
     *  - source=REPLAY：该版本下没有线上落地结论，用该历史版本规则确定性重算。
     * 两种来源都严格绑定请求的历史版本，不允许用新版本结果冒名。
     */
    public RuleReviewOutcome review(EventRecord event, String ruleId, int ruleVersion,
                                    String reason) {
        RuleDefinition rule = ruleId == null
                ? findRuleForFieldAtVersion(event.metricField(), ruleVersion)
                : requireVersion(ruleId, ruleVersion);
        RuleVerdict live = findLiveVerdict(event.eventId(), ruleId, ruleVersion);
        RuleReviewOutcome outcome;
        if (live != null) {
            outcome = new RuleReviewOutcome(rule, RuleEvaluator.evaluate(event, rule),
                    live, "LIVE");
        } else {
            RuleEvaluation evaluation = RuleEvaluator.evaluate(event, rule);
            RuleVerdict recomputed = new RuleVerdict(event.eventId(), rule.ruleId(),
                    ruleVersion, evaluation.fired(), evaluation.action(), event.occurredAt());
            outcome = new RuleReviewOutcome(rule, evaluation, recomputed, "REPLAY");
        }
        store.appendAudit(new RuleAuditEntry(RuleAuditEntry.REVIEW,
                rule.ruleId(), ruleVersion, reason,
                "复看 eventId=" + event.eventId() + " source=" + outcome.source()
                        + " fired=" + outcome.verdict().fired()
                        + " basis=" + outcome.evaluation().basis(),
                Instant.now(clock)));
        log.info("[历史复看] eventId={} rule={} v{} source={} fired={} reason={}",
                event.eventId(), rule.ruleId(), ruleVersion, outcome.source(),
                outcome.verdict().fired(), reason);
        return outcome;
    }

    private RuleDefinition requireVersion(String ruleId, int ruleVersion) {
        RuleDefinition rule = store.getVersion(ruleId, ruleVersion);
        if (rule == null) {
            throw new GatewayException(ErrorCode.RULE_VERSION_NOT_FOUND,
                    "规则 " + ruleId + " 不存在已发布版本 v" + ruleVersion);
        }
        return rule;
    }

    private RuleVerdict findLiveVerdict(String eventId, String ruleId, int ruleVersion) {
        List<RuleVerdict> verdicts = verdictsByEvent.get(eventId);
        if (verdicts == null) {
            return null;
        }
        synchronized (verdicts) {
            RuleVerdict fallback = null;
            for (RuleVerdict v : verdicts) {
                // 版本精确归属：必须绑定同一 ruleId + 同一版本才算当时真实执行。
                boolean idMatch = ruleId == null || ruleId.equals(v.ruleId());
                if (idMatch && v.ruleVersion() == ruleVersion) {
                    return v;
                }
                // 无规则场景（ruleId 为 null，version=0）保留兜底。
                if (ruleId == null && v.ruleId() == null && v.ruleVersion() == ruleVersion) {
                    fallback = v;
                }
            }
            return fallback;
        }
    }

    public RuleBackpressureStats stats() {
        synchronized (queueLock) {
            return new RuleBackpressureStats(
                    properties.getRuleQueueCapacity(),
                    queue.size(),
                    submitted.get(),
                    processed.get(),
                    droppedBackpressure.get(),
                    failedPoison.get());
        }
    }

    /** 历史复看结果：版本定义、判断依据、结论及来源（LIVE 真实落地 / REPLAY 确定性重算）。 */
    public record RuleReviewOutcome(
            RuleDefinition rule,
            RuleEvaluation evaluation,
            RuleVerdict verdict,
            String source
    ) {
    }
}
