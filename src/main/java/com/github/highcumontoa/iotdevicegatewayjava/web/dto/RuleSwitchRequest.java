package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

/** 正式启用指定版本（activate）或回退到任意已发布历史版本（rollback）。 */
public record RuleSwitchRequest(String ruleId, int version, String reason) {
}
