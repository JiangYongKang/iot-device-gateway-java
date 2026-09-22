package com.github.highcumontoa.iotdevicegatewayjava.domain;

import java.util.Map;

/**
 * 遥测写入结果。
 */
public record TelemetryResult(
        String deviceId,
        long timestamp,
        Long sequence,
        Map<String, Object> acceptedMetrics,
        long acceptedAt
) {
}
