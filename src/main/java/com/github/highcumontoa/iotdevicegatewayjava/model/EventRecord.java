package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/** 进入规则引擎的事件（权威数据，遥测写入成功后产生）。 */
public record EventRecord(
        String eventId,
        String deviceId,
        long telemetrySeq,
        String metricField,
        double value,
        Instant occurredAt
) {
}
