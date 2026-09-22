package com.github.highcumontoa.iotdevicegatewayjava.rule;

import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于权威数据（已成功写入的遥测所产生的事件）进行规则判定。
 *  - 规则版本化：putRule 递增 version；每条结论 RuleVerdict 绑定判定时的规则版本，
 *    规则更新后历史结论可区分、可复现（用历史版本规则重放仍得到原结论）。
 *  - 处理模型：进入有界队列，串行处理，避免并发交叉；队列满时按“丢弃最旧”策略
 *    背压，丢弃数计入 droppedBackpressure 可观测指标。
 *  - 单条毒消息异常只计入 failedPoison，不拖垮链路，不影响后续事件。
 *  - 事件按设备有界留存，超出容量丢弃最旧，内存不泄漏。
 */
@Service
public class RuleEngineService {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);

    private final Map<String, RuleDefinition> rules = new ConcurrentHashMap<>();
    private final Map<String, Integer> ruleVersions = new ConcurrentHashMap<>();
    private final ArrayDeque<EventRecord> queue = new ArrayDeque<>();
    private final Map<String, List<RuleVerdict>> verdictsByEvent = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<RuleVerdict>> verdictsByDevice = new ConcurrentHashMap<>();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong droppedBackpressure = new AtomicLong();
    private final AtomicLong failedPoison = new AtomicLong();

    private final com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties properties;
    private final Clock clock;
    private final Object queueLock = new Object();
    private volatile java.util.function.Predicate<EventRecord> poisonMatcher;

    /** 测试钩子：匹配的事件在判定时抛出底层异常，验证毒消息隔离。 */
    public void setPoisonMatcher(java.util.function.Predicate<EventRecord> poisonMatcher) {
        this.poisonMatcher = poisonMatcher;
    }

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
        int version = ruleVersions.merge(rule.ruleId(), 1, Integer::sum);
        RuleDefinition stored = new RuleDefinition(rule.ruleId(), version, rule.metricField(),
                rule.threshold(), rule.action(), rule.enabled(), Instant.now(clock));
        rules.put(rule.ruleId(), stored);
        log.info("规则已更新 ruleId={} version={} field={} threshold={} action={} enabled={}",
                stored.ruleId(), version, stored.metricField(), stored.threshold(),
                stored.action(), stored.enabled());
        return stored;
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
            // 取当前权威规则快照（版本绑定），无规则则不命中也留痕，结论可复现。
            RuleDefinition rule = pickRuleFor(event.metricField());
            boolean fired = rule != null && rule.enabled()
                    && event.value() > rule.threshold();
            RuleVerdict verdict = new RuleVerdict(event.eventId(),
                    rule == null ? null : rule.ruleId(),
                    rule == null ? 0 : rule.version(),
                    fired,
                    fired ? rule.action() : null,
                    Instant.now(clock));
            persistVerdict(event, verdict);
            processed.incrementAndGet();
            log.info("规则判定 eventId={} deviceId={} rule={} v{} value={} threshold={} fired={}",
                    event.eventId(), event.deviceId(), verdict.ruleId(), verdict.ruleVersion(),
                    event.value(), rule == null ? null : rule.threshold(), fired);
            return verdict;
        } catch (Exception e) {
            // 毒消息隔离：计数并继续，不抛出影响调用链的其他处理
            failedPoison.incrementAndGet();
            log.error("规则处理毒消息隔离 eventId={} error={}",
                    event.eventId(), e.toString());
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

    /** 用指定版本规则重放历史事件（复现）：当前仅按最新规则演示可复现入口。 */
    public RuleVerdict replay(EventRecord event, int ruleVersion) {
        RuleDefinition current = pickRuleFor(event.metricField());
        if (current == null || current.version() < ruleVersion) {
            throw new GatewayException(ErrorCode.RULE_NOT_FOUND,
                    "无规则版本 v" + ruleVersion + " 可用于重放");
        }
        boolean fired = current.enabled() && event.value() > current.threshold();
        RuleVerdict verdict = new RuleVerdict(event.eventId(), current.ruleId(), ruleVersion,
                fired, fired ? current.action() : null, Instant.now(clock));
        log.info("规则重放 eventId={} ruleVersion={} fired={}", event.eventId(), ruleVersion, fired);
        return verdict;
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
}
