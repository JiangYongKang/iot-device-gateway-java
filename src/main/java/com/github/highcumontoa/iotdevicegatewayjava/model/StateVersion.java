package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;
import java.util.Map;

/**
 * 设备状态对（期望/上报），版本化；并发更新必须带期望版本号（CAS）。
 */
public record StateVersion(
        String deviceId,
        long desiredVersion,
        long reportedVersion,
        Map<String, Object> desired,
        Map<String, Object> reported,
        Instant updatedAt
) {
}
