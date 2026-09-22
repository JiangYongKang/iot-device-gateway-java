package com.github.highcumontoa.iotdevicegatewayjava.domain;

import java.util.List;

/**
 * 一条事件的处理结果。
 *
 * @param eventId        事件 ID
 * @param outcomes       各规则在其当时版本下的结论
 * @param processedAt    实际处理时间
 * @param ruleErrorCount 处理过程中单条规则异常的条数（不拖垮整链路）
 */
public record EventResult(
        String eventId,
        List<RuleOutcome> outcomes,
        long processedAt,
        int ruleErrorCount
) {
}
