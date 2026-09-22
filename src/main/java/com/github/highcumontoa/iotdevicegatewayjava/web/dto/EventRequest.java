package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

import java.time.Instant;

public record EventRequest(String eventId, String deviceId, long telemetrySeq,
                           String metricField, double value, Instant occurredAt) {
}
