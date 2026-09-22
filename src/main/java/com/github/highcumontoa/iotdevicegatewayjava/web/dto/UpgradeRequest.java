package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

public record UpgradeRequest(String targetVersion, String idempotencyKey) {
}
