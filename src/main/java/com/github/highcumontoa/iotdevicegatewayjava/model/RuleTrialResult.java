package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 候选规则历史试跑报告：只读对比，不触碰线上结论与实时处理。
 * 同批样本 + 同组规则重复试跑，items/统计结论一致（确定性纯函数）。
 */
public record RuleTrialResult(
        String trialId,
        String ruleId,
        int currentVersion,
        Integer candidateVersion,
        int totalEvents,
        int changedCount,
        List<RuleTrialItem> items,
        Instant at
) {
}
