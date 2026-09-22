package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

public record RuleRequest(String ruleId, String metricField, double threshold,
                          String action, Boolean enabled) {
}
