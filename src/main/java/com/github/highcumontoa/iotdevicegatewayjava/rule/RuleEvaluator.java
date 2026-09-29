package com.github.highcumontoa.iotdevicegatewayjava.rule;

import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleDefinition;
import com.github.highcumontoa.iotdevicegatewayjava.model.RuleEvaluation;

/**
 * 规则判定的纯函数：同一事件 + 同一规则定义 => 完全一致的 RuleEvaluation。
 * 实时处理、历史试跑、历史复看共用同一份判定逻辑，保证结论可复现。
 * 结论时间取事件发生时间 occurredAt，而不是当前时钟，因此重复计算结果逐字段一致。
 */
final class RuleEvaluator {

    private RuleEvaluator() {
    }

    /** 无适用规则（该指标字段没有任何生效规则）：不匹配、不命中，仍留判断依据。 */
    static RuleEvaluation unmatched(EventRecord event) {
        return new RuleEvaluation(null, 0, false, false, null, 0.0d,
                "字段[" + event.metricField() + "]无适用规则，不判定",
                event.occurredAt());
    }

    /**
     * 按给定规则版本判定。matched 表示存在适用于该字段的规则（含被停用）；
     * fired 表示启用且 value &gt; threshold。basis 给出字段/阈值/实际值/比较结果。
     */
    static RuleEvaluation evaluate(EventRecord event, RuleDefinition rule) {
        boolean fieldMatches = rule.metricField().equals(event.metricField());
        boolean matched = fieldMatches;
        if (!fieldMatches) {
            return new RuleEvaluation(rule.ruleId(), rule.version(), false, false, null,
                    rule.threshold(),
                    "规则字段[" + rule.metricField() + "]与事件字段["
                            + event.metricField() + "]不一致，不适用",
                    event.occurredAt());
        }
        if (!rule.enabled()) {
            return new RuleEvaluation(rule.ruleId(), rule.version(), matched, false, null,
                    rule.threshold(),
                    "规则[" + rule.ruleId() + "] v" + rule.version()
                            + " 已停用，value=" + event.value() + " 不判定",
                    event.occurredAt());
        }
        boolean fired = event.value() > rule.threshold();
        String basis = "规则[" + rule.ruleId() + "] v" + rule.version()
                + " 字段[" + event.metricField() + "] value=" + event.value()
                + " " + (fired ? ">" : "<=") + " threshold=" + rule.threshold()
                + " => fired=" + fired;
        return new RuleEvaluation(rule.ruleId(), rule.version(), matched, fired,
                fired ? rule.action() : null, rule.threshold(), basis, event.occurredAt());
    }
}
