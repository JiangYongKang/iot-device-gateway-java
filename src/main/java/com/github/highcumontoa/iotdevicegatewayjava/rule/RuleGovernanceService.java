package com.github.highcumontoa.iotdevicegatewayjava.rule;

import com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleAuditEntry;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleEvaluation;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleTrialItem;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleTrialResult;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RuleTrialRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 规则治理：候选规则历史试跑（只读对比）、正式切换、回退、审计留痕。
 * 实时处理在 {@link RuleEngineService}，治理操作经 {@link RuleVersionStore} 与它
 * 共用同一切换边界：切换在 store 锁内原子完成，处理侧取活动快照，二者并存不串版本。
 *
 * 试跑不经过处理队列、不写线上结论、不影响实时处理，纯内存只读计算；
 * 同批样本 + 同组规则重复试跑，items 与统计结论逐字段一致（结论时间取事件时间）。
 */
@Service
public class RuleGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(RuleGovernanceService.class);

    private final RuleVersionStore store;
    private final GatewayProperties properties;
    private final Clock clock;
    /** 并发切换/回退闸门：同一时刻只允许一个切换进入临界区，其余立即拒绝。 */
    private final Semaphore switchPermits = new Semaphore(1, true);
    /** 并发试跑上限：超过配置容量立即拒绝，不排队。 */
    private final Semaphore trialPermits;
    private volatile Runnable trialBarrier;
    /** 测试钩子：在 store 切换临界区内、引用切换前执行（用于卡住切换边界/注入失败）。 */
    private volatile Runnable switchHook;

    public RuleGovernanceService(RuleVersionStore store, GatewayProperties properties,
                                 Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.trialPermits = new Semaphore(Math.max(1, properties.getMaxConcurrentTrials()), true);
    }

    /**
     * 候选规则历史试跑。当前规则与候选规则分别对同一批样本判定，逐条给出差异与汇总。
     * 试跑是只读操作：不发布、不激活、不写线上结论、不进入实时处理队列。
     */
    public RuleTrialResult trial(RuleTrialRequest request) {
        validateTrialRequest(request);
        if (request.events().size() > properties.getMaxTrialEvents()) {
            throw new GatewayException(ErrorCode.RULE_TRIAL_LIMIT,
                    "试跑样本数 " + request.events().size() + " 超过上限 "
                            + properties.getMaxTrialEvents());
        }
        if (!trialPermits.tryAcquire()) {
            store.appendAudit(new RuleAuditEntry(RuleAuditEntry.REJECTED, request.ruleId(),
                    null, request.reason(), "并发试跑数已达上限 "
                            + properties.getMaxConcurrentTrials() + "，拒绝试跑",
                    Instant.now(clock)));
            throw new GatewayException(ErrorCode.RULE_TRIAL_LIMIT,
                    "并发试跑数已达上限 " + properties.getMaxConcurrentTrials());
        }
        try {
            Runnable barrier = trialBarrier;
            if (barrier != null) {
                barrier.run();
            }
            RuleDefinition candidate = new RuleDefinition(request.ruleId(), 0,
                    request.metricField(), request.threshold(), request.action(),
                    !Boolean.FALSE.equals(request.enabled()), Instant.now(clock));

            RuleDefinition current = store.active(request.ruleId());
            // 同 ruleId 尚无在线规则时，按同字段兜底找一个在线规则作为基线。
            if (current == null) {
                for (RuleDefinition rule : store.activeSnapshot()) {
                    if (rule.metricField().equals(request.metricField())) {
                        current = rule;
                        break;
                    }
                }
            }
            int currentVersion = current == null ? 0 : current.version();

            List<RuleTrialItem> items = new ArrayList<>(request.events().size());
            int changed = 0;
            for (EventRecord event : request.events()) {
                // 仅对候选字段（或当前基线字段）的事件做对比，其余事件两侧都不适用。
                RuleEvaluation currentEval = current == null
                        ? RuleEvaluator.unmatched(event)
                        : RuleEvaluator.evaluate(event, current);
                RuleEvaluation candidateEval = RuleEvaluator.evaluate(event, candidate);
                boolean diff = currentEval.fired() != candidateEval.fired()
                        || !java.util.Objects.equals(currentEval.action(),
                                candidateEval.action())
                        || currentEval.matched() != candidateEval.matched();
                if (diff) {
                    changed++;
                }
                items.add(new RuleTrialItem(event.eventId(), event.deviceId(),
                        event.metricField(), event.value(), currentEval, candidateEval, diff));
            }

            String trialId = deterministicTrialId(request);
            RuleTrialResult result = new RuleTrialResult(trialId, request.ruleId(),
                    currentVersion, null, request.events().size(), changed,
                    List.copyOf(items), Instant.now(clock));
            store.appendAudit(new RuleAuditEntry(RuleAuditEntry.TRIAL, request.ruleId(), null,
                    request.reason(),
                    "trialId=" + trialId + " 基线v" + currentVersion
                            + " 样本=" + result.totalEvents() + " 结论变化=" + changed
                            + " 候选threshold=" + request.threshold(),
                    Instant.now(clock)));
            log.info("[规则试跑] trialId={} ruleId={} baseline=v{} samples={} changed={} reason={}",
                    trialId, request.ruleId(), currentVersion, result.totalEvents(),
                    changed, request.reason());
            return result;
        } finally {
            trialPermits.release();
        }
    }

    private void validateTrialRequest(RuleTrialRequest request) {
        if (request == null || request.ruleId() == null || request.metricField() == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "试跑请求缺少 ruleId/metricField");
        }
        if (request.events() == null || request.events().isEmpty()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "试跑样本不能为空");
        }
        for (EventRecord e : request.events()) {
            if (e == null || e.eventId() == null || e.metricField() == null) {
                throw new GatewayException(ErrorCode.INVALID_REQUEST,
                        "试跑样本包含空事件或缺 eventId/metricField");
            }
        }
    }

    /**
     * 同批样本 + 同组规则 => 同一 trialId（对事件标识、字段、阈值等关键输入做稳定摘要），
     * 便于对账时确认两次试跑是同一组输入。
     */
    private String deterministicTrialId(RuleTrialRequest request) {
        long hash = 1469598103934665603L;
        hash = mix(hash, request.ruleId());
        hash = mix(hash, request.metricField());
        hash = mix(hash, Double.doubleToLongBits(request.threshold()));
        hash = mix(hash, request.action());
        hash = mix(hash, Boolean.FALSE.equals(request.enabled()) ? 0L : 1L);
        for (EventRecord e : request.events()) {
            hash = mix(hash, e.eventId());
            hash = mix(hash, e.metricField());
            hash = mix(hash, Double.doubleToLongBits(e.value()));
            hash = mix(hash, e.occurredAt() == null ? 0L : e.occurredAt().toEpochMilli());
        }
        return "trial-" + Long.toHexString(hash);
    }

    private static long mix(long hash, String s) {
        hash = mix(hash, s == null ? 0L : (long) s.length());
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                hash = mix(hash, s.charAt(i));
            }
        }
        return hash;
    }

    private static long mix(long hash, long k) {
        long h = hash ^ k;
        h *= 1099511628211L;
        return h;
    }

    /** 发布候选版本；activate=true 时原子切换为生效版本，false 仅留档不影响线上。 */
    public RuleDefinition publishCandidate(RuleDefinition candidate, boolean activate,
                                           String reason) {
        if (candidate == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "候选规则为空");
        }
        if (!activate) {
            RuleDefinition stored = store.publish(candidate, false);
            store.appendAudit(new RuleAuditEntry(RuleAuditEntry.PUBLISH, candidate.ruleId(),
                    stored.version(), reason, "候选版本发布（暂不激活）", Instant.now(clock)));
            return stored;
        }
        return doSwitch(RuleAuditEntry.ACTIVATE, candidate.ruleId(), reason,
                () -> store.publish(candidate, true));
    }

    /** 正式切换到已发布版本；并发切换超容量或切换失败时明确拒绝，旧版本继续稳定服务。 */
    public RuleDefinition activate(String ruleId, int version, String reason) {
        return doSwitch(RuleAuditEntry.ACTIVATE, ruleId, reason,
                () -> store.activate(ruleId, version, RuleAuditEntry.ACTIVATE, reason,
                        switchHook));
    }

    /** 回退到任意已发布历史版本；之后事件按回退后版本处理，审计动作记为 ROLLBACK。 */
    public RuleDefinition rollback(String ruleId, int version, String reason) {
        return doSwitch(RuleAuditEntry.ROLLBACK, ruleId, reason,
                () -> store.activate(ruleId, version, RuleAuditEntry.ROLLBACK, reason,
                        switchHook));
    }

    /**
     * 切换临界区包装：信号量保证同一时刻至多一个切换；拿不到许可立即拒绝。
     * 临界区内若抛异常（含注入的失败），store 保证活动引用不变，继续按旧版本服务。
     */
    private RuleDefinition doSwitch(String action, String ruleId, String reason,
                                    java.util.function.Supplier<RuleDefinition> op) {
        if (!switchPermits.tryAcquire()) {
            // 被拒时刻不触碰任何切换临界区，只追加一条审计，记录当前仍在线上的版本。
            Integer liveVersion = currentLiveVersion(ruleId);
            store.appendAudit(new RuleAuditEntry(RuleAuditEntry.REJECTED, ruleId, liveVersion,
                    reason, "已有规则切换在进行中，拒绝并发切换，继续按当前版本 v"
                            + liveVersion + " 服务", Instant.now(clock)));
            throw new GatewayException(ErrorCode.RULE_SWITCH_IN_PROGRESS,
                    "规则切换正在进行中，请稍后重试");
        }
        try {
            return op.get();
        } finally {
            switchPermits.release();
        }
    }

    private Integer currentLiveVersion(String ruleId) {
        return store.activeVersionOf(ruleId);
    }

    public List<RuleDefinition> history(String ruleId) {
        return store.history(ruleId);
    }

    public List<RuleAuditEntry> auditLog() {
        return store.auditLog();
    }

    /** 测试钩子：试跑获取到许可后执行（用 CountDownLatch 卡住，复现并发试跑占满）。 */
    public void setTrialBarrier(Runnable barrier) {
        this.trialBarrier = barrier;
    }

    /** 测试钩子：切换在 store 临界区内、引用切换前执行（卡住边界或抛异常模拟失败）。 */
    public void setSwitchHook(Runnable hook) {
        this.switchHook = hook;
    }
}
