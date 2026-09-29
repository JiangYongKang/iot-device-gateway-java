package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/**
 * 规则治理操作留痕（试跑/切换/回退/复看/拒绝）。
 * action 见 TRIAL/ACTIVATE/ROLLBACK/REVIEW/REJECTED/PUBLISH 常量。
 */
public record RuleAuditEntry(
        String action,
        String ruleId,
        Integer ruleVersion,
        String reason,
        String summary,
        Instant at
) {
    public static final String PUBLISH = "PUBLISH";
    public static final String TRIAL = "TRIAL";
    public static final String ACTIVATE = "ACTIVATE";
    public static final String ROLLBACK = "ROLLBACK";
    public static final String REVIEW = "REVIEW";
    public static final String REJECTED = "REJECTED";
}
