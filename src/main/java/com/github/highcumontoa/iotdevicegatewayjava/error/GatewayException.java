package com.github.highcumontoa.iotdevicegatewayjava.error;

/** 业务异常基类：携带稳定错误码，不向外部透出底层异常细节。 */
public class GatewayException extends RuntimeException {
    private final ErrorCode code;

    public GatewayException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /** cause 仅用于服务端日志，全局处理器永不向客户端透出其内容。 */
    public GatewayException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
