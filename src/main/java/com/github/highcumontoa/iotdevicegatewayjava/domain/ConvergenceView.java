package com.github.highcumontoa.iotdevicegatewayjava.domain;

import java.util.Map;

/**
 * 收敛查询结果：每个键的最新期望、设备回报与收敛结论可解释。
 */
public record ConvergenceView(
        String deviceId,
        long currentRevision,
        Map<String, DesiredEntry> desiredEntries,
        Map<String, Object> reported,
        boolean allConverged
) {
}
