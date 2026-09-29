package com.github.highcumontoa.iotdevicegatewayjava.model.rulegov;

import com.github.highcumontoa.iotdevicegatewayjava.model.RuleVerdict;

/**
 * 按指定历史版本复看历史事件的结果。
 * replayed 严格用该历史版本的规则定义现场重算，必须与该版本当时真实执行结论一致
 * （fired/action 一致）；original 为该事件实际落地的历史结论（可能绑定其它版本）。
 */
public record RuleReviewResult(
        String eventId,
        String ruleId,
        int ruleVersion,
        boolean matched,
        String basis,
        RuleVerdict replayed,
        RuleVerdict original
) {
}
