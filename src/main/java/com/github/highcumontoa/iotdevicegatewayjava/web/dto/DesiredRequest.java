package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

import java.util.Map;

public record DesiredRequest(long expectedVersion, Map<String, Object> desired) {
}
