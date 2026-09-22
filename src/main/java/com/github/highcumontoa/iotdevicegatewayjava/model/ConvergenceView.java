package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.util.Map;

/** 收敛查询视图：逐字段给出结论与依据。 */
public record ConvergenceView(
        String deviceId,
        ConvergenceStatus overall,
        Map<String, String> fieldReasons
) {
}
