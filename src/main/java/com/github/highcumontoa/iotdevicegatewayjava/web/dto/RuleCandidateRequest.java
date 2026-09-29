package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

/** 准备候选规则版本（不影响实时判定）；ruleId 必须是已发布过的规则。 */
public record RuleCandidateRequest(String ruleId, String metricField, double threshold,
                                   String action, Boolean enabled, String reason) {
}
