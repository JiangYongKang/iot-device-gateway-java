package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 遥测字段允许的类型。类型不匹配按 TELEMETRY_FIELD_TYPE_MISMATCH 拒绝。
 */
public enum MetricType {
    NUMBER,
    BOOLEAN,
    STRING
}
