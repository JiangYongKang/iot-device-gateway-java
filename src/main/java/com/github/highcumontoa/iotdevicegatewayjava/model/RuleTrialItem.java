package com.github.highcumontoa.iotdevicegatewayjava.model;

/** 试跑单条事件的两边对比：当前规则结论 vs 候选规则结论。 */
public record RuleTrialItem(
        String eventId,
        String deviceId,
        String metricField,
        double value,
        RuleEvaluation current,
        RuleEvaluation candidate,
        boolean changed
) {
}
