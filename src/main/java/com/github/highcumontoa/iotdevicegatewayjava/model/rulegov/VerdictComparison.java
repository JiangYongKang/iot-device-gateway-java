package com.github.highcumontoa.iotdevicegatewayjava.model.rulegov;

/**
 * 同一条样本事件在“当前生效规则”与“候选规则”两边的结论对比。
 * 试跑结果为纯函数计算：不含 evaluatedAt 等不稳定字段，同一批样本配同一组规则
 * 重复试跑，逐项字段完全一致。
 */
public record VerdictComparison(
        String eventId,
        String deviceId,
        String metricField,
        double value,
        String ruleId,
        int baselineVersion,
        boolean baselineFired,
        String baselineAction,
        int candidateVersion,
        boolean candidateFired,
        String candidateAction,
        boolean changed,
        String reason
) {
}
