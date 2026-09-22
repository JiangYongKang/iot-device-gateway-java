package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.domain.MetricSchema;
import com.github.highcumontoa.iotdevicegatewayjava.domain.MetricType;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 遥测字段权威 schema 注册接口。
 */
@RestController
@RequestMapping("/api/telemetry/schemas")
public class SchemaController {

    private final TelemetryService telemetryService;

    public SchemaController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    public record SchemaRequest(String name, MetricType type, Boolean required, Double min, Double max) {}

    @PostMapping
    public MetricSchema register(@RequestBody SchemaRequest req) {
        boolean required = Boolean.TRUE.equals(req.required());
        MetricSchema schema = new MetricSchema(req.name(), req.type(), required, req.min(), req.max());
        telemetryService.registerSchema(schema);
        return schema;
    }
}
