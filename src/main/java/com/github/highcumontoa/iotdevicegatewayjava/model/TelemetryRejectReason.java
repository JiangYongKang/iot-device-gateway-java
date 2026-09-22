package com.github.highcumontoa.iotdevicegatewayjava.model;

/** 遥测记录拒绝原因，必须可区分。 */
public enum TelemetryRejectReason {
    MISSING_FIELD,    // 必填字段缺失
    TYPE_MISMATCH,    // 字段类型不匹配（或上报了未定义字段）
    OUT_OF_RANGE      // 数值超出允许范围
}
