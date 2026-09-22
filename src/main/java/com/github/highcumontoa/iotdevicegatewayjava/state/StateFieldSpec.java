package com.github.highcumontoa.iotdevicegatewayjava.state;

/**
 * 可下发期望字段约束：
 *  - writable=false 表示只读，期望下发会被 READ_ONLY 拒绝；
 *  - min/max 限定数值期望范围。
 */
public record StateFieldSpec(String field, boolean writable, double min, double max) {
}
