package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleBackpressureStats;
import com.github.highcumontoa.iotdevicegatewayjava.rule.RuleEngineService;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.EventRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RuleRequest;
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
}
