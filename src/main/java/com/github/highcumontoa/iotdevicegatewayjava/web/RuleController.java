package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleAuditEntry;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleTrialResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleBackpressureStats;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleEngineService;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleGovernanceService;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.EventRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RuleActivateRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RuleRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RuleTrialRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 规则增改、事件提交（权威数据）、结论与背压指标查询，
 * 以及候选规则历史试跑、版本切换/回退、历史复看、审计留痕查询。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleEngineService rules;
    private final RuleGovernanceService governance;

    public RuleController(RuleEngineService rules, RuleGovernanceService governance) {
        this.rules = rules;
        this.governance = governance;
    }

    @PostMapping
    public RuleDefinition putRule(@RequestBody RuleRequest req) {
        return rules.putRule(new RuleDefinition(req.ruleId(), 0, req.metricField(),
                req.threshold(), req.action(), !Boolean.FALSE.equals(req.enabled()),
                Instant.now()));
    }

    @PostMapping("/events")
    public RuleVerdict submitEvent(@RequestBody EventRequest req) {
        Instant ts = req.occurredAt() == null ? Instant.now() : req.occurredAt();
        return rules.submit(new EventRecord(req.eventId(), req.deviceId(),
                req.telemetrySeq(), req.metricField(), req.value(), ts));
    }

    @GetMapping("/events/{eventId}/verdicts")
    public List<RuleVerdict> verdicts(@PathVariable String eventId) {
        return rules.verdicts(eventId);
    }

    @PostMapping("/events/replay")
    public RuleVerdict replay(@RequestBody EventRequest req,
                              @RequestParam int ruleVersion) {
        Instant ts = req.occurredAt() == null ? Instant.now() : req.occurredAt();
        return rules.replay(new EventRecord(req.eventId(), req.deviceId(),
                req.telemetrySeq(), req.metricField(), req.value(), ts), ruleVersion);
    }

    /** 历史复看：指定规则（可省略，按事件字段匹配）与历史版本复看一条过去的事件。 */
    @PostMapping("/events/review")
    public RuleEngineService.RuleReviewOutcome review(@RequestBody EventRequest req,
                                                      @RequestParam int ruleVersion,
                                                      @RequestParam(required = false) String ruleId,
                                                      @RequestParam(required = false) String reason) {
        Instant ts = req.occurredAt() == null ? Instant.now() : req.occurredAt();
        return rules.review(new EventRecord(req.eventId(), req.deviceId(),
                req.telemetrySeq(), req.metricField(), req.value(), ts), ruleId,
                ruleVersion, reason);
    }

    /** 候选规则历史试跑：只读对比当前规则与候选规则，不改线上结论、不影响实时处理。 */
    @PostMapping("/trial")
    public RuleTrialResult trial(@RequestBody RuleTrialRequest request) {
        return governance.trial(request);
    }

    /** 正式切换到已发布版本。 */
    @PostMapping("/activate")
    public RuleDefinition activate(@RequestBody RuleActivateRequest req) {
        return governance.activate(req.ruleId(), req.version(), req.reason());
    }

    /** 回退到任意已发布历史版本。 */
    @PostMapping("/rollback")
    public RuleDefinition rollback(@RequestBody RuleActivateRequest req) {
        return governance.rollback(req.ruleId(), req.version(), req.reason());
    }

    /** 发布候选版本（activate=false 仅留档不影响线上）。 */
    @PostMapping("/candidates")
    public RuleDefinition publishCandidate(@RequestBody RuleRequest req,
                                           @RequestParam(defaultValue = "false") boolean activate,
                                           @RequestParam(required = false) String reason) {
        return governance.publishCandidate(
                new RuleDefinition(req.ruleId(), 0, req.metricField(), req.threshold(),
                        req.action(), !Boolean.FALSE.equals(req.enabled()), Instant.now()),
                activate, reason);
    }

    @GetMapping("/{ruleId}/versions")
    public List<RuleDefinition> versions(@PathVariable String ruleId) {
        return governance.history(ruleId);
    }

    /** 试跑/切换/回退/复看/拒绝的操作留痕（时间、原因、版本、结论摘要）。 */
    @GetMapping("/audit")
    public List<RuleAuditEntry> audit() {
        return governance.auditLog();
    }

    @GetMapping("/stats")
    public RuleBackpressureStats stats() {
        return rules.stats();
    }
}
