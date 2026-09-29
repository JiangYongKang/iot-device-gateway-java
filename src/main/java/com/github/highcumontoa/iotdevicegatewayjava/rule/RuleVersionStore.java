package com.github.highcumontoa.iotdevicegatewayjava.rule;

import com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleAuditEntry;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则版本存储与切换边界（串行化）：
 *  - 每个 ruleId 保留全部已发布历史版本（有界），可激活任意历史版本（回退）；
 *  - publish/activate 在同一把对象锁内完成“校验-发布-切活动引用”，切换原子，
 *    实时处理在处理前取一次活动规则快照，因此一条事件只可能整体落在一个版本下；
 *  - 切换钩子失败：不切引用、继续按旧版本稳定服务，并追加 REJECTED 留痕后抛出；
 *  - appendAudit 留痕所有试跑/切换/回退/复看/拒绝操作（有界，满则丢最旧）。
 */
@Component
public class RuleVersionStore {

    private static final Logger log = LoggerFactory.getLogger(RuleVersionStore.class);

    private final GatewayProperties properties;
    private final Clock clock;

    /** ruleId -> (version -> 已发布规则定义)，TreeMap 按版本有序。 */
    private final Map<String, TreeMap<Integer, RuleDefinition>> history = new TreeMap<>();
    /** ruleId -> 当前生效版本；不存在表示该 ruleId 尚无生效版本。 */
    private final Map<String, Integer> activeVersions = new TreeMap<>();
    /** ruleId -> 最近一次激活序号；同字段多个规则匹配时，最近激活者优先（确定性）。 */
    private final Map<String, Long> activationOrder = new TreeMap<>();
    /** 活动版本的 volatile 只读镜像：拒绝路径无需进入 synchronized 即可判断线上版本。 */
    private volatile Map<String, Integer> activeVersionView = Map.of();
    private final AtomicLong activationSeq = new AtomicLong(0);
    private final ArrayDeque<RuleAuditEntry> audit = new ArrayDeque<>();
    private final Object auditLock = new Object();

    public RuleVersionStore(GatewayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 发布新版本（版本号在锁内单调递增），并按 activate 决定是否立即切为生效版本。
     * 首次发布默认激活；之后 activate=false 只发布不影响线上（候选/灰度留档）。
     */
    public synchronized RuleDefinition publish(RuleDefinition rule, boolean activate) {
        if (rule == null || rule.ruleId() == null || rule.metricField() == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "规则缺少 ruleId/metricField");
        }
        TreeMap<Integer, RuleDefinition> versions =
                history.computeIfAbsent(rule.ruleId(), k -> new TreeMap<>());
        int next = versions.isEmpty() ? 1 : versions.lastKey() + 1;
        RuleDefinition stored = new RuleDefinition(rule.ruleId(), next, rule.metricField(),
                rule.threshold(), rule.action(), rule.enabled(), Instant.now(clock));
        versions.put(next, stored);
        trimVersions(rule.ruleId(), versions);
        boolean shouldActivate = activate || !activeVersions.containsKey(rule.ruleId());
        if (shouldActivate) {
            Integer old = activeVersions.put(rule.ruleId(), next);
            activationOrder.put(rule.ruleId(), activationSeq.incrementAndGet());
            activeVersionView = Map.copyOf(activeVersions);
            log.info("规则发布并激活 ruleId={} v{} -> v{} field={} threshold={} enabled={}",
                    rule.ruleId(), old, next, stored.metricField(), stored.threshold(),
                    stored.enabled());
            appendAudit(new RuleAuditEntry(RuleAuditEntry.PUBLISH, rule.ruleId(), next,
                    "发布并激活（旧版本 v" + old + "）",
                    "field=" + stored.metricField() + " threshold=" + stored.threshold()
                            + " action=" + stored.action() + " enabled=" + stored.enabled(),
                    Instant.now(clock)));
        } else {
            log.info("规则发布未激活（候选留档）ruleId={} v{}", rule.ruleId(), next);
        }
        return stored;
    }

    private void trimVersions(String ruleId, TreeMap<Integer, RuleDefinition> versions) {
        int cap = Math.max(1, properties.getMaxRuleVersionsPerRule());
        while (versions.size() > cap) {
            Integer oldest = versions.firstKey();
            // 生效版本不允许被裁剪，避免回退/对账找不到当前版本。
            if (oldest.equals(activeVersions.get(ruleId))) {
                Map.Entry<Integer, RuleDefinition> next = versions.higherEntry(oldest);
                if (next == null) {
                    break;
                }
                oldest = next.getKey();
            }
            versions.remove(oldest);
            log.warn("规则版本历史超上限，裁剪最旧版本 ruleId={} v{}", ruleId, oldest);
        }
    }

    /**
     * 激活已发布版本（切换/回退）。preSwitchHook 在锁内、引用切换前执行；
     * 钩子抛异常则不切换、旧版本继续生效，追加 REJECTED 留痕并向上抛出。
     */
    public synchronized RuleDefinition activate(String ruleId, int version, String action,
                                                String reason, Runnable preSwitchHook) {
        TreeMap<Integer, RuleDefinition> versions = history.get(ruleId);
        RuleDefinition target = versions == null ? null : versions.get(version);
        if (target == null) {
            throw new GatewayException(ErrorCode.RULE_VERSION_NOT_FOUND,
                    "规则 " + ruleId + " 不存在已发布版本 v" + version);
        }
        Integer oldVersion = activeVersions.get(ruleId);
        try {
            if (preSwitchHook != null) {
                preSwitchHook.run();
            }
        } catch (GatewayException e) {
            appendAudit(new RuleAuditEntry(RuleAuditEntry.REJECTED, ruleId, version, reason,
                    "切换被拒绝，继续按旧版本 v" + oldVersion + " 服务：" + e.getMessage(),
                    Instant.now(clock)));
            throw e;
        } catch (RuntimeException e) {
            appendAudit(new RuleAuditEntry(RuleAuditEntry.REJECTED, ruleId, version, reason,
                    "切换失败，继续按旧版本 v" + oldVersion + " 服务：" + e,
                    Instant.now(clock)));
            throw new GatewayException(ErrorCode.RULE_SWITCH_FAILED,
                    "规则切换失败，已保持旧版本 v" + oldVersion);
        }
        activeVersions.put(ruleId, version);
        activationOrder.put(ruleId, activationSeq.incrementAndGet());
        activeVersionView = Map.copyOf(activeVersions);
        appendAudit(new RuleAuditEntry(action, ruleId, version, reason,
                "v" + oldVersion + " -> v" + version + " field=" + target.metricField()
                        + " threshold=" + target.threshold() + " enabled=" + target.enabled(),
                Instant.now(clock)));
        log.info("规则版本切换完成 ruleId={} v{} -> v{} action={} reason={}",
                ruleId, oldVersion, version, action, reason);
        return target;
    }

    public synchronized RuleDefinition active(String ruleId) {
        Integer v = activeVersions.get(ruleId);
        if (v == null) {
            return null;
        }
        TreeMap<Integer, RuleDefinition> versions = history.get(ruleId);
        return versions == null ? null : versions.get(v);
    }

    /**
     * 当前全部生效规则快照，按“最近激活”顺序升序排列（最后一个是最近激活者）。
     * 处理侧用它做字段匹配时，多个规则同字段会确定性地选中最近激活的一个。
     */
    public synchronized List<RuleDefinition> activeSnapshot() {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(activeVersions.entrySet());
        entries.sort(java.util.Comparator.comparingLong(e ->
                activationOrder.getOrDefault(e.getKey(), Long.MIN_VALUE)));
        List<RuleDefinition> snapshot = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> e : entries) {
            TreeMap<Integer, RuleDefinition> versions = history.get(e.getKey());
            RuleDefinition rule = versions == null ? null : versions.get(e.getValue());
            if (rule != null) {
                snapshot.add(rule);
            }
        }
        return List.copyOf(snapshot);
    }

    public synchronized RuleDefinition getVersion(String ruleId, int version) {
        TreeMap<Integer, RuleDefinition> versions = history.get(ruleId);
        return versions == null ? null : versions.get(version);
    }

    public synchronized List<RuleDefinition> history(String ruleId) {
        TreeMap<Integer, RuleDefinition> versions = history.get(ruleId);
        return versions == null ? List.of() : List.copyOf(versions.values());
    }

    public void appendAudit(RuleAuditEntry entry) {
        synchronized (auditLock) {
            audit.addLast(entry);
            while (audit.size() > properties.getMaxRuleAuditEntries()) {
                audit.removeFirst();
            }
        }
    }

    public List<RuleAuditEntry> auditLog() {
        synchronized (auditLock) {
            return List.copyOf(audit);
        }
    }

    /** 只读视图（测试断言版本归属用）。 */
    synchronized Map<String, Integer> activeVersionsView() {
        return Map.copyOf(activeVersions);
    }

    /** 非阻塞读取某规则当前生效版本号（不进入 synchronized，切换拒绝路径用）。 */
    public Integer activeVersionOf(String ruleId) {
        return activeVersionView.get(ruleId);
    }
}
