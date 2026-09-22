package com.github.highcumontoa.iotdevicegatewayjava.error;

/**
 * 领域内所有可预期错误的统一异常载体。
 *
 * @param errorCode 稳定错误分类
 * @param message   面向调用方的可解释信息（不得包含底层堆栈/SQL 等）
 */
public class GatewayException extends RuntimeException {

    private final ErrorCode errorCode;

    public GatewayException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public GatewayException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public static GatewayException of(ErrorCode errorCode, String message) {
        return new GatewayException(errorCode, message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
