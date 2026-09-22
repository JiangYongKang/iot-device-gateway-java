package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/**
 * 权威规则定义（版本化）。规则更新后历史结论可区分：结论记录所用规则版本。
 * condition: 字段名；threshold: 阈值；action: 命中动作标签。
 */
public record RuleDefinition(
        String ruleId,
        int version,
        String metricField,
        double threshold,
        String action,
        boolean enabled,
        Instant createdAt
) {
}
