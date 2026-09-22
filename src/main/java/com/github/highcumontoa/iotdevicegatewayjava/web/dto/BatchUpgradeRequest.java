package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

import java.util.List;

public record BatchUpgradeRequest(List<String> deviceIds, String targetVersion,
                                  String idempotencyKeyPrefix) {
}
