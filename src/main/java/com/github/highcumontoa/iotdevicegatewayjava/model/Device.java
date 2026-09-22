package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/** 设备登记信息（不可变）。 */
public record Device(
        String id,
        String model,
        DeviceStatus status,
        String firmwareVersion,
        String hardwareVersion,
        Instant registeredAt
) {
}
