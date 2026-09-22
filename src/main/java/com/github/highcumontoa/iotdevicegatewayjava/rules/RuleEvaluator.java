package com.github.highcumontoa.iotdevicegatewayjava.rules;

import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceEvent;
import com.github.highcumontoa.iotdevicegatewayjava.domain.RuleOutcome;
import com.github.highcumontoa.iotdevicegatewayjava.domain.RuleSpec;

/**
 * 无状态规则求值器：给定规则快照与事件，输出确定结论。
 * 不做任何 I/O，便于以任意历史规则版本对事件进行可复现判定。
 */
final class RuleEvaluator {

    private RuleEvaluator() {
    }

    static RuleOutcome evaluate(RuleSpec rule, DeviceEvent event) {
        Object raw = event.attributes() == null ? null : event.attributes().get(rule.field());
        boolean matched;
        if (raw == null) {
            // 缺失字段：除 NE 外一律不命中，保证结论确定
            matched = "NE".equals(rule.operator());
        } else {
            matched = switch (rule.operator()) {
                case "EQ" -> valueEquals(raw, rule.threshold());
                case "NE" -> !valueEquals(raw, rule.threshold());
                case "GT", "GTE", "LT", "LTE" -> compareOrdered(raw, rule.threshold(), rule.operator());
                default -> throw new IllegalArgumentException("不支持的比较算子: " + rule.operator());
            };
        }
        return new RuleOutcome(rule.ruleId(), rule.version(), matched,
                matched ? rule.decision() : null);
    }

    private static boolean valueEquals(Object raw, String threshold) {
        if (raw instanceof Number n && isNumber(threshold)) {
            return n.doubleValue() == Double.parseDouble(threshold.trim());
        }
        if (raw instanceof Boolean b) {
            return b == Boolean.parseBoolean(threshold.trim());
        }
        return String.valueOf(raw).equals(threshold);
    }

    private static boolean compareOrdered(Object raw, String threshold, String operator) {
        if (!(raw instanceof Number) || !isNumber(threshold)) {
            throw new IllegalArgumentException("有序比较要求两侧均为数值: field=" + raw + " threshold=" + threshold);
        }
        double left = ((Number) raw).doubleValue();
        double right = Double.parseDouble(threshold.trim());
        return switch (operator) {
            case "GT" -> left > right;
            case "GTE" -> left >= right;
            case "LT" -> left < right;
            case "LTE" -> left <= right;
            default -> false;
        };
    }

    private static boolean isNumber(String s) {
        if (s == null) {
            return false;
        }
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
