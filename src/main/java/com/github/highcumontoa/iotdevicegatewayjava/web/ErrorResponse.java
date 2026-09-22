package com.github.highcumontoa.iotdevicegatewayjava.web;

/**
 * 统一错误响应体。底层异常不直接透出，仅返回稳定错误码与可解释信息。
 */
public record ErrorResponse(
        String errorCode,
        String message,
        String traceId
) {
}
