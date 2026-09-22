package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

public record FirmwarePublishRequest(String version, String fromMinInclusive,
                                     String fromMaxExclusive, String compatibleModel,
                                     Boolean yanked) {
}
