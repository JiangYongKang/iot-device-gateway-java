package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryPoint;
import com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryResult;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 遥测 schema 注册与遥测写入接口。
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    public record TelemetryRequest(String deviceId, Long sequence, Long timestamp,
                                   Map<String, Object> metrics) {}

    @PostMapping
    public TelemetryResult ingest(@RequestBody TelemetryRequest req) {
        if (req.timestamp() == null) {
            throw new com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException(
                    com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode.VALIDATION_FAILED,
                    "遥测帧必须包含 timestamp");
        }
        return telemetryService.ingest(
                new TelemetryPoint(req.deviceId(), req.sequence(), req.timestamp(), req.metrics()));
    }

    @GetMapping("/{deviceId}/latest")
    public Map<String, Object> latest(@PathVariable String deviceId) {
        return telemetryService.latest(deviceId);
    }
}
