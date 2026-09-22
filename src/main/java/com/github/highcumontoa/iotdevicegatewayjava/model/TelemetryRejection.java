package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/** 遥测被拒记录，保留可追溯的判定依据。 */
public record TelemetryRejection(
        String deviceId,
        Long seq,
        Instant ts,
        TelemetryRejectReason reason,
        String detail,
        Instant rejectedAt
) {
}
