package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 一条事件在某个规则版本下的权威结论。
 *
 * @param ruleId         规则 ID
 * @param ruleVersion    判定所用规则版本（规则更新后历史结论仍保留旧版本，可区分、可复现）
 * @param matched        是否命中
 * @param decision       命中时的结论标签；未命中为 null
 */
public record RuleOutcome(
        String ruleId,
        long ruleVersion,
        boolean matched,
        String decision
) {
}
