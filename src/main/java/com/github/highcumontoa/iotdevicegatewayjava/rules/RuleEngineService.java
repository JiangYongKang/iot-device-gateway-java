package com.github.highcumontoa.iotdevicegatewayjava.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.Clock;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceEvent;
import com.github.highcumontoa.iotdevicegatewayjava.domain.EventResult;
import com.github.highcumontoa.iotdevicegatewayjava.domain.RuleOutcome;
import com.github.highcumontoa.iotdevicegatewayjava.domain.RuleSpec;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 版本化规则引擎。
 *
 * <ul>
 *   <li>规则按 ID 保存全部版本；事件结论固定记录其判定时的规则版本，更新规则后历史结论可区分；</li>
 *   <li>异步积压使用有界队列：DISCARD_NEWEST 拒绝新事件、DISCARD_OLDEST 淘汰最旧事件，
 *       两种策略均递增可观测计数；</li>
 *   <li>单条规则/单个事件异常被隔离计数，绝不拖垮处理链路；</li>
 *   <li>processNow 同步求值，replay 以指定（或历史）规则版本复现结论。</li>
 * </ul>
 */
@Service
public class RuleEngineService {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);

    /** 规则 ID -> 全部版本（按版本号有序） */
    private final Map<String, List<RuleSpec>> ruleVersions = new ConcurrentHashMap<>();
    /** 已处理事件 ID -> 事件（供 replay 复现） */
    private final Map<String, DeviceEvent> knownEvents = new ConcurrentHashMap<>();
    /** 已处理事件 ID -> 处理时结论（历史结论保留判定版本，不因规则更新而改变） */
    private final Map<String, EventResult> processedResults = new ConcurrentHashMap<>();
    /** 事件 ID -> 提交时被丢弃的记录（可观测） */
    private final Map<String, String> discardedEvents = new ConcurrentHashMap<>();

    private final java.util.concurrent.ConcurrentLinkedDeque<DeviceEvent> backlog =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final AtomicLong backlogSize = new AtomicLong();
    /** 处理开关：测试可暂停消费以确定性地构造积压；生产始终为 true。 */
    private volatile boolean processingEnabled = true;
    private final ExecutorService workers;
    private final Object backlogLock = new Object();

    private final Clock clock;
    private final GatewayProperties properties;
    private final CapacityMetrics metrics;

    public RuleEngineService(Clock clock, GatewayProperties properties, CapacityMetrics metrics) {
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
        int threads = Math.max(1, properties.getRules().getWorkerThreads());
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "rule-engine-worker");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < threads; i++) {
            workers.submit(this::workerLoop);
        }
    }

    /**
     * 新建或更新规则。
     * requested.version 为 0 或空时由服务端分配下一个版本；
     * 非 0 时作为乐观期望：必须等于当前最大版本，否则 STATE_VERSION_CONFLICT（防止并发覆盖规则）。
     */
    public RuleSpec upsertRule(RuleSpec requested) {
        if (requested == null || requested.ruleId() == null || requested.ruleId().isBlank()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "规则 ID 不能为空");
        }
        if (requested.field() == null || requested.operator() == null) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "规则字段与算子不能为空");
        }
        validateOperator(requested.operator());

        List<RuleSpec> versions = ruleVersions.computeIfAbsent(requested.ruleId(),
                k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        synchronized (versions) {
            long currentMax = versions.isEmpty() ? 0L : versions.get(versions.size() - 1).version();
            long expected = requested.version();
            long newVersion;
            if (expected <= 0) {
                newVersion = currentMax + 1;
            } else {
                if (expected != currentMax) {
                    throw GatewayException.of(ErrorCode.STATE_VERSION_CONFLICT,
                            "规则版本冲突 rule=" + requested.ruleId()
                                    + " expected=" + expected + " actual=" + currentMax);
                }
                newVersion = currentMax + 1;
            }
            RuleSpec saved = new RuleSpec(requested.ruleId(), newVersion, requested.field(),
                    requested.operator(), requested.threshold(), requested.decision());
            versions.add(saved);
            log.info("rule upserted id={} version={} field={} {} {} -> {}",
                    saved.ruleId(), newVersion, saved.field(), saved.operator(),
                    saved.threshold(), saved.decision());
            return saved;
        }
    }

    public RuleSpec getRule(String ruleId) {
        List<RuleSpec> versions = ruleVersions.get(ruleId);
        if (versions == null || versions.isEmpty()) {
            throw GatewayException.of(ErrorCode.RULE_NOT_FOUND, "规则不存在: " + ruleId);
        }
        synchronized (versions) {
            return versions.get(versions.size() - 1);
        }
    }

    /** 取指定规则的指定版本；version 为 null 取最新。 */
    public RuleSpec getRuleVersion(String ruleId, Long version) {
        List<RuleSpec> versions = ruleVersions.get(ruleId);
        if (versions == null || versions.isEmpty()) {
            throw GatewayException.of(ErrorCode.RULE_NOT_FOUND, "规则不存在: " + ruleId);
        }
        synchronized (versions) {
            if (version == null) {
                return versions.get(versions.size() - 1);
            }
            for (RuleSpec spec : versions) {
                if (spec.version() == version) {
                    return spec;
                }
            }
            throw GatewayException.of(ErrorCode.STATE_REVISION_NOT_FOUND,
                    "规则版本不存在 rule=" + ruleId + " version=" + version);
        }
    }

    /**
     * 提交事件进入异步积压。队列满时按策略处理：
     * DISCARD_NEWEST：拒绝本次提交（EVENT_QUEUE_FULL，调用方可重试）；
     * DISCARD_OLDEST：淘汰队首最旧事件并接纳新事件（被淘汰事件在 discardedEvents 可查）。
     */
    public EventResult submit(DeviceEvent event) {
        validateEvent(event);
        knownEvents.putIfAbsent(event.eventId(), event);
        int capacity = properties.getRules().getBacklogCapacity();
        String policy = properties.getRules().getBacklogPolicy();

        synchronized (backlogLock) {
            if (backlogSize.get() >= capacity) {
                if ("BLOCK".equals(policy)) {
                    // BLOCK 语义在本地无界等待会拖垮调用方，这里等同拒绝并给出可区分码
                    metrics.incrementEventBacklogRejections();
                    throw GatewayException.of(ErrorCode.EVENT_QUEUE_FULL,
                            "规则积压已满，BLOCK 策略下拒绝提交 event=" + event.eventId());
                }
                if ("DISCARD_NEWEST".equals(policy)) {
                    metrics.incrementEventBacklogRejections();
                    throw GatewayException.of(ErrorCode.BACKLOG_DISCARDED,
                            "规则积压已满，按 DISCARD_NEWEST 丢弃新事件 event=" + event.eventId());
                }
                // 默认 DISCARD_OLDEST
                DeviceEvent oldest = backlog.poll();
                if (oldest != null) {
                    backlogSize.decrementAndGet();
                    metrics.incrementEventBacklogDiscards();
                    discardedEvents.put(oldest.eventId(),
                            "积压超限按 DISCARD_OLDEST 淘汰 at=" + clock.nowMillis());
                    log.warn("backlog discard oldest event={} metricDiscards={}",
                            oldest.eventId(), metrics.eventBacklogDiscards());
                }
            }
            backlog.add(event);
            backlogSize.incrementAndGet();
            backlogLock.notifyAll();
        }
        // 返回排队凭证（结论稍后可通过 replay(eventId,null) 查询）
        return new EventResult(event.eventId(), List.of(), clock.nowMillis(), 0);
    }

    /** 同步立即处理：不进入积压队列，直接按当前最新规则版本求值。 */
    public EventResult processNow(DeviceEvent event) {
        validateEvent(event);
        knownEvents.putIfAbsent(event.eventId(), event);
        EventResult result = evaluateAgainstLatest(event);
        if (processedResults.putIfAbsent(event.eventId(), result) == null) {
            metrics.incrementEventsProcessed();
        }
        return result;
    }

    /**
     * 复现历史事件结论：
     * ruleVersionOverride 为 null 时返回该事件首次处理时保存的结论（保留旧规则版本，可区分）；
     * 指定版本时用该版本规则重新求值（结果中规则版本可解释）。
     */
    public EventResult replay(String eventId, Long ruleVersionOverride) {
        DeviceEvent event = knownEvents.get(eventId);
        if (event == null) {
            throw GatewayException.of(ErrorCode.RULE_NOT_FOUND, "事件未登记，无法复现: " + eventId);
        }
        if (ruleVersionOverride == null) {
            EventResult historical = processedResults.get(eventId);
            if (historical != null) {
                return historical;
            }
            // 尚未异步处理：按最新规则即时求值
            return evaluateAgainstLatest(event);
        }
        return evaluateWithVersionOverride(event, ruleVersionOverride);
    }

    /** 等待异步处理达到已处理条数（测试用，使用独立真实时间，不依赖业务时钟）。 */
    public java.util.List<DeviceEvent> waitForProcessed(long timeoutMillis, int expectedCount)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (backlogLock) {
            while (metrics.eventsProcessed() < expectedCount
                    && System.currentTimeMillis() < deadline) {
                backlogLock.wait(50);
            }
        }
        List<DeviceEvent> out = new ArrayList<>();
        for (String id : knownEvents.keySet()) {
            if (processedResults.containsKey(id)) {
                out.add(knownEvents.get(id));
            }
        }
        return out;
    }

    public int backlogDepth() {
        return (int) backlogSize.get();
    }

    /** 供测试与诊断：被淘汰事件记录。 */
    public Map<String, String> discardedSnapshot() {
        return Map.copyOf(discardedEvents);
    }

    // ---- 内部 ----

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                DeviceEvent event = null;
                synchronized (backlogLock) {
                    if (processingEnabled && backlogSize.get() > 0) {
                        event = backlog.poll();
                        if (event != null) {
                            backlogSize.decrementAndGet();
                        }
                    }
                }
                if (event == null) {
                    synchronized (backlogLock) {
                        backlogLock.wait(20);
                    }
                    continue;
                }
                processOne(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 单事件意外异常不得杀死工作线程
                log.error("rule worker swallowed event-level error", e);
                metrics.incrementEventRuleErrors();
            }
        }
    }

    /** 暂停异步消费（仅测试用于确定性构造积压）。 */
    public void pauseProcessing() {
        processingEnabled = false;
    }

    /** 恢复异步消费并唤醒工作线程。 */
    public void resumeProcessing() {
        processingEnabled = true;
        synchronized (backlogLock) {
            backlogLock.notifyAll();
        }
    }

    private void processOne(DeviceEvent event) {
        try {
            EventResult result = evaluateAgainstLatest(event);
            processedResults.putIfAbsent(event.eventId(), result);
            metrics.incrementEventsProcessed();
            synchronized (backlogLock) {
                backlogLock.notifyAll();
            }
        } catch (Exception e) {
            // 事件级异常隔离并计数，绝不拖垮链路
            metrics.incrementEventRuleErrors();
            log.error("event processing isolated failure event={}", event.eventId(), e);
        }
    }

    private EventResult evaluateAgainstLatest(DeviceEvent event) {
        List<RuleOutcome> outcomes = new ArrayList<>();
        int errors = 0;
        // 固定当前规则集合快照（按 ID 排序保证结论顺序确定）
        List<String> ids = new ArrayList<>(ruleVersions.keySet());
        ids.sort(Comparator.naturalOrder());
        for (String ruleId : ids) {
            try {
                RuleSpec rule = getRuleVersion(ruleId, null);
                outcomes.add(RuleEvaluator.evaluate(rule, event));
            } catch (Exception e) {
                errors++;
                metrics.incrementEventRuleErrors();
                log.warn("single rule error isolated rule={} event={}: {}",
                        ruleId, event.eventId(), e.getMessage());
            }
        }
        return new EventResult(event.eventId(), List.copyOf(outcomes), clock.nowMillis(), errors);
    }

    private EventResult evaluateWithVersionOverride(DeviceEvent event, long version) {
        List<RuleOutcome> outcomes = new ArrayList<>();
        int errors = 0;
        List<String> ids = new ArrayList<>(ruleVersions.keySet());
        ids.sort(Comparator.naturalOrder());
        for (String ruleId : ids) {
            try {
                RuleSpec rule = getRuleVersion(ruleId, version);
                outcomes.add(RuleEvaluator.evaluate(rule, event));
            } catch (GatewayException e) {
                if (e.errorCode() == ErrorCode.STATE_REVISION_NOT_FOUND) {
                    throw e; // 指定版本不存在是调用方错误，透出
                }
                errors++;
                metrics.incrementEventRuleErrors();
            }
        }
        return new EventResult(event.eventId(), List.copyOf(outcomes), clock.nowMillis(), errors);
    }

    private static void validateEvent(DeviceEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "事件 ID 不能为空");
        }
        if (event.deviceId() == null || event.deviceId().isBlank()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "事件必须包含设备 ID");
        }
    }

    private static void validateOperator(String operator) {
        if (!List.of("GT", "GTE", "LT", "LTE", "EQ", "NE").contains(operator)) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "不支持的比较算子: " + operator);
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
