package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;

import java.util.List;

/** 候选规则历史试跑请求：候选规则内容 + 圈定的历史事件/样本 + 原因。 */
public record RuleTrialRequest(
        String ruleId,
        String metricField,
        double threshold,
        String action,
        Boolean enabled,
        List<EventRecord> events,
        String reason
) {
}
