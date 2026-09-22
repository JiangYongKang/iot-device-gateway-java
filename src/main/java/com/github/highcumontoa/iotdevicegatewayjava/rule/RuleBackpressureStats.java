package com.github.highcumontoa.iotdevicegatewayjava.rule;

/** 规则引擎积压/丢弃可观测指标。 */
public record RuleBackpressureStats(
        int queueCapacity,
        int queueDepth,
        long submitted,
        long processed,
        long droppedBackpressure,
        long failedPoison
) {
}
