package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;
import java.util.Map;

/** 一次遥测上报：去重键 (deviceId, seq)，ts 用于乱序/时钟回拨判断。 */
public record TelemetryPoint(
        String deviceId,
        long seq,
        Instant ts,
        Map<String, Object> metrics
) {
}
