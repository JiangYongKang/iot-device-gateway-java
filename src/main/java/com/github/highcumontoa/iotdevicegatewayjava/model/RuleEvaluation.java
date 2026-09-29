package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/**
 * 一次规则判定的完整结果（含判断依据），试跑对比与历史复看共用。
 * matched=false 表示该事件字段没有任何适用规则；fired 表示命中阈值。
 */
public record RuleEvaluation(
        String ruleId,
        int ruleVersion,
        boolean matched,
        boolean fired,
        String action,
        double threshold,
        String basis,
        Instant evaluatedAt
) {
}
