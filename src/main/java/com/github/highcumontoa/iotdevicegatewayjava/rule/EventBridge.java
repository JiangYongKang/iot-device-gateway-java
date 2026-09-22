package com.github.highcumontoa.iotdevicegatewayjava.rule;

import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryPoint;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 遥测成功写入后，为每个数值指标派生权威事件送入规则引擎。
 * 拒绝的遥测不会走到这里，因此规则结论只建立在权威数据之上。
 */
@Component
public class EventBridge {

    private final TelemetryService telemetry;
    private final RuleEngineService rules;
    private final AtomicLong eventSeq = new AtomicLong(0);

    public EventBridge(TelemetryService telemetry, RuleEngineService rules) {
        this.telemetry = telemetry;
        this.rules = rules;
    }

    @PostConstruct
    void wire() {
        telemetry.setAfterAccept(this::onAccepted);
    }

    void onAccepted(TelemetryPoint point) {
        for (Map.Entry<String, Object> e : point.metrics().entrySet()) {
            if (e.getValue() instanceof Number n) {
                String eventId = "evt-" + point.deviceId() + "-" + point.seq()
                        + "-" + e.getKey() + "-" + eventSeq.incrementAndGet();
                rules.submit(new EventRecord(eventId, point.deviceId(), point.seq(),
                        e.getKey(), n.doubleValue(), point.ts()));
            }
        }
    }
}
