package com.github.highcumontoa.iotdevicegatewayjava.model.rulegov;

import java.util.List;

/**
 * 候选规则历史试跑报告：只做对比，不产生任何已落地结论。
 * total/changed 汇总整体影响面；items 给出逐条差异；内容完全由样本与规则版本决定，
 * 重复试跑结果一致（幂等、可复现）。
 */
public record TrialReport(
        String ruleId,
        int baselineVersion,
        int candidateVersion,
        int sampleCount,
        int changedCount,
        List<VerdictComparison> items
) {
}
