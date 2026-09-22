package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 遥测字段的权威定义（字段名、类型、合法范围）。
 * NUMBER 类型使用 min/max 判定范围；其他类型忽略 min/max。
 * required=false 的字段缺失时允许整包通过；required=true 缺失拒绝。
 */
public record MetricSchema(
        String name,
        MetricType type,
        boolean required,
        Double min,
        Double max
) {
    public static MetricSchema number(String name, boolean required, Double min, Double max) {
        return new MetricSchema(name, MetricType.NUMBER, required, min, max);
    }

    public static MetricSchema bool(String name, boolean required) {
        return new MetricSchema(name, MetricType.BOOLEAN, required, null, null);
    }

    public static MetricSchema string(String name, boolean required) {
        return new MetricSchema(name, MetricType.STRING, required, null, null);
    }
}
