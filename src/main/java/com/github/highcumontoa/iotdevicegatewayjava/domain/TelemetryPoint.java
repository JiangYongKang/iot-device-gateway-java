package com.github.highcumontoa.iotdevicegatewayjava.domain;

import java.util.Map;

/**
 * 设备上报的一帧遥测。
 *
 * @param deviceId  设备 ID
 * @param sequence  设备侧单调递增序列号（可为 null，表示不参与去重判定）
 * @param timestamp 设备侧时间戳（毫秒）
 * @param metrics   字段名 -> 值（值类型为 Long/Double/Boolean/String）
 */
public record TelemetryPoint(
        String deviceId,
        Long sequence,
        long timestamp,
        Map<String, Object> metrics
) {
}
