package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import com.github.highcumontoa.iotdevicegatewayjava.rules.RuleEngineService;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读诊断接口：暴露容量/背压/丢弃等可观测计数。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final DeviceRegistry registry;
    private final SessionManager sessions;
    private final RuleEngineService rules;
    private final FirmwareService firmware;
    private final CapacityMetrics metrics;

    public AdminController(DeviceRegistry registry, SessionManager sessions, RuleEngineService rules,
                           FirmwareService firmware, CapacityMetrics metrics) {
        this.registry = registry;
        this.sessions = sessions;
        this.rules = rules;
        this.firmware = firmware;
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registeredDevices", registry.registeredCount());
        out.put("onlineSessions", sessions.onlineCount());
        out.put("ruleBacklogDepth", rules.backlogDepth());
        out.put("capacityRejections", metrics.capacityRejections());
        out.put("commandQueueRejections", metrics.commandQueueRejections());
        out.put("eventBacklogDiscards", metrics.eventBacklogDiscards());
        out.put("eventBacklogRejections", metrics.eventBacklogRejections());
        out.put("eventRuleErrors", metrics.eventRuleErrors());
        out.put("eventsProcessed", metrics.eventsProcessed());
        out.put("commandsExpired", metrics.commandsExpired());
        return out;
    }
}
