package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

public record CommandRequest(String payload, Long ttlSeconds) {
}
