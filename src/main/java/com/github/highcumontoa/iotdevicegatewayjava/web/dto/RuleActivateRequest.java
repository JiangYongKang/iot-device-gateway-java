package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

/** 规则切换/回退请求：激活指定 ruleId 的某个已发布历史版本。 */
public record RuleActivateRequest(String ruleId, int version, String reason) {
}
