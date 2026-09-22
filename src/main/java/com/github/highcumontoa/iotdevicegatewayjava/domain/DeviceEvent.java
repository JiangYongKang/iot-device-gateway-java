package com.github.highcumontoa.iotdevicegatewayjava.domain;

import java.util.Map;

/**
 * 进入规则引擎的事件。事件基于权威数据（遥测/状态）构造，
 * 规则引擎不对其进行任何外部 I/O。
 */
public record DeviceEvent(
        String eventId,
        String deviceId,
        long occurredAt,
        Map<String, Object> attributes
) {
}
