package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/** 固件版本信息与兼容规则（不可变）。 */
public record FirmwareRecord(
        String version,
        String fromMinInclusive,
        String fromMaxExclusive,
        String compatibleModel,
        boolean yanked,
        Instant releasedAt
) {
}
