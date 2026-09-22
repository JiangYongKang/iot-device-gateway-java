package com.github.highcumontoa.iotdevicegatewayjava.model;

/** 期望状态无法满足时的拒绝原因。 */
public enum DesiredRejectReason {
    UNSUPPORTED_FIELD,
    VALUE_OUT_OF_RANGE,
    READ_ONLY
}
