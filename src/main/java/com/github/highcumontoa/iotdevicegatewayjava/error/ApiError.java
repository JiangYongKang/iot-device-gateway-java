package com.github.highcumontoa.iotdevicegatewayjava.error;

import java.time.Instant;

/** 对外统一错误响应体。 */
public record ApiError(String code, String message, Instant time) {
}
