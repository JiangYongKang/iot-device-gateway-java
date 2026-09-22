package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/** 下发给设备的指令（不可变）。 */
public record Command(
        String commandId,
        String deviceId,
        String payload,
        Instant createdAt,
        Instant expireAt,
        CommandStatus status,
        String failReason
) {
}
