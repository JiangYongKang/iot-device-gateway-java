package com.github.highcumontoa.iotdevicegatewayjava.telemetry;

/**
 * 指标字段约束：必填、数值类型、允许范围。
 * required=true 时缺失记 MISSING_FIELD；
 * 类型不是 Number 记 TYPE_MISMATCH；超出 [min,max] 记 OUT_OF_RANGE。
 */
public record MetricSpec(String field, boolean required, double min, double max) {
}
