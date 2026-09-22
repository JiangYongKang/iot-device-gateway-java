package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

public record RegisterDeviceRequest(String deviceId, String model,
                                    String hardwareVersion, String firmwareVersion) {
}
