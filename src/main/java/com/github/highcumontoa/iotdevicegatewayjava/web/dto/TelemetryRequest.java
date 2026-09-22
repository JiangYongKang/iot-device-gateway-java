package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

import java.time.Instant;
import java.util.Map;

public record TelemetryRequest(long seq, Instant ts, Map<String, Object> metrics) {
}
