package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 规则定义（版本化）。规则引擎只做权威判定，不执行带副作用动作。
 *
 * @param ruleId    规则 ID
 * @param version   单调递增版本号
 * @param field     判定字段名（取自事件属性）
 * @param operator  比较算子：GT / GTE / LT / LTE / EQ / NE
 * @param threshold 比较阈值（数值/布尔/字符串统一按字符串表达，比较时按字段实际类型解析）
 * @param decision  条件成立时给出的结论标签
 */
public record RuleSpec(
        String ruleId,
        long version,
        String field,
        String operator,
        String threshold,
        String decision
) {
}
