package com.github.highcumontoa.iotdevicegatewayjava.domain;

import java.util.Map;

/**
 * 设备回报的状态。
 *
 * @param deviceId        设备 ID
 * @param reported        设备当前状态键值
 * @param rejectedKeys    设备明确无法满足的期望键
 * @param rejectionReasons 被拒键 -> 设备给出的原因
 * @param reportedAt      回报时间
 */
public record ReportedState(
        String deviceId,
        Map<String, Object> reported,
        java.util.Set<String> rejectedKeys,
        Map<String, String> rejectionReasons,
        long reportedAt
) {
}
