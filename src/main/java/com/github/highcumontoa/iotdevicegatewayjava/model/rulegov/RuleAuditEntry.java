package com.github.highcumontoa.iotdevicegatewayjava.model.rulegov;

import java.time.Instant;

/**
 * 规则治理操作留痕：每次候选试跑、正式切换、回退、历史复看各产生一条。
 * action 取值：TRIAL / ACTIVATE / ROLLBACK / REVIEW。
 * outcome 仅记录操作本身结果：SUCCESS / REJECTED，不承载业务结论。
 */
public record RuleAuditEntry(
        long seq,
        String action,
        String ruleId,
        Integer fromVersion,
        Integer toVersion,
        String reason,
        String outcome,
        String detail,
        Instant at
) {
}
