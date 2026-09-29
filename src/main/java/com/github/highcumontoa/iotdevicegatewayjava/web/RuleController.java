package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleBackpressureStats;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleEngineService;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.EventRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RuleCandidateRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RuleRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RuleSwitchRequest;
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

/** 规则增改、事件提交（权威数据）、结论与背压指标查询。 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleEngineService rules;

    public RuleController(RuleEngineService rules) {
        this.rules = rules;
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

    @GetMapping("/stats")
    public RuleBackpressureStats stats() {
        return rules.stats();
    }

    // ===== 规则治理：候选试跑 / 切换 / 回退 / 历史复看 =====

    @PostMapping("/candidates")
    public RuleDefinition prepareCandidate(@RequestBody RuleCandidateRequest req) {
        return rules.prepareCandidate(new RuleDefinition(req.ruleId(), 0, req.metricField(),
                req.threshold(), req.action(), !Boolean.FALSE.equals(req.enabled()),
                Instant.now()), req.reason());
    }

    @PostMapping("/trials")
    public com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.TrialReport runTrial(
            @RequestBody RuleTrialRequest req) {
        return rules.runTrial(req.ruleId(), req.candidateVersion(),
                req.eventIds(), req.samples(), req.reason());
    }

    @PostMapping("/activate")
    public RuleDefinition activate(@RequestBody RuleSwitchRequest req) {
        return rules.activate(req.ruleId(), req.version(), req.reason());
    }

    @PostMapping("/rollback")
    public RuleDefinition rollback(@RequestBody RuleSwitchRequest req) {
        return rules.rollback(req.ruleId(), req.version(), req.reason());
    }

    @PostMapping("/events/review")
    public com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.RuleReviewResult review(
            @RequestBody EventRequest req, @RequestParam String ruleId,
            @RequestParam int ruleVersion) {
        Instant ts = req.occurredAt() == null ? Instant.now() : req.occurredAt();
        return rules.review(ruleId, ruleVersion, new EventRecord(req.eventId(), req.deviceId(),
                req.telemetrySeq(), req.metricField(), req.value(), ts));
    }

    @GetMapping("/{ruleId}/versions")
    public List<RuleDefinition> versions(@PathVariable String ruleId) {
        return rules.ruleHistory(ruleId);
    }

    @GetMapping("/audit")
    public List<com.github.highcumontoa.iotdevicegatewayjava.model.rulegov.RuleAuditEntry> audit(
            @RequestParam(required = false) String ruleId) {
        return rules.auditTrail(ruleId);
    }
}
