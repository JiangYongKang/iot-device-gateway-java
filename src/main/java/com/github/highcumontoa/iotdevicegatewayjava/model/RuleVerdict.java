package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/** 规则处理结论，绑定规则版本，保证规则更新后历史结论可区分、可复现。 */
public record RuleVerdict(
        String eventId,
        String ruleId,
        int ruleVersion,
        boolean fired,
        String action,
        Instant evaluatedAt
) {
}
