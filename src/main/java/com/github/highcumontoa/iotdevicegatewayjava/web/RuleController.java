package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceEvent;
import com.github.highcumontoa.iotdevicegatewayjava.domain.EventResult;
import com.github.highcumontoa.iotdevicegatewayjava.domain.RuleSpec;
import com.github.highcumontoa.iotdevicegatewayjava.rules.RuleEngineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 版本化规则维护、事件提交（异步积压）与立即处理/复现接口。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleEngineService ruleEngineService;

    public RuleController(RuleEngineService ruleEngineService) {
        this.ruleEngineService = ruleEngineService;
    }

    public record RuleRequest(String ruleId, Long version, String field, String operator,
                              String threshold, String decision) {}
    public record EventRequest(String eventId, String deviceId, Long occurredAt,
                               Map<String, Object> attributes) {}

    @PostMapping
    public RuleSpec upsert(@RequestBody RuleRequest req) {
        long version = req.version() == null ? 0L : req.version();
        return ruleEngineService.upsertRule(
                new RuleSpec(req.ruleId(), version, req.field(), req.operator(), req.threshold(), req.decision()));
    }

    @GetMapping("/{ruleId}")
    public RuleSpec get(@PathVariable String ruleId) {
        return ruleEngineService.getRule(ruleId);
    }

    @PostMapping("/events")
    public EventResult submit(@RequestBody EventRequest req) {
        long occurredAt = req.occurredAt() == null ? System.currentTimeMillis() : req.occurredAt();
        return ruleEngineService.submit(
                new DeviceEvent(req.eventId(), req.deviceId(), occurredAt,
                        req.attributes() == null ? Map.of() : req.attributes()));
    }

    @PostMapping("/events/process-now")
    public EventResult processNow(@RequestBody EventRequest req) {
        long occurredAt = req.occurredAt() == null ? System.currentTimeMillis() : req.occurredAt();
        return ruleEngineService.processNow(
                new DeviceEvent(req.eventId(), req.deviceId(), occurredAt,
                        req.attributes() == null ? Map.of() : req.attributes()));
    }

    @PostMapping("/events/{eventId}/replay")
    public EventResult replay(@PathVariable String eventId,
                              @RequestParam(required = false) Long ruleVersionOverride) {
        return ruleEngineService.replay(eventId, ruleVersionOverride);
    }
}
