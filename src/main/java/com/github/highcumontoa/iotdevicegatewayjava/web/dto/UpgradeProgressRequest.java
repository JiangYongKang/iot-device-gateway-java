package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

public record UpgradeProgressRequest(String sessionId, String upgradeId,
                                     String status, String detail) {
}
