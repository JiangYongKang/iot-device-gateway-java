package com.github.highcumontoa.iotdevicegatewayjava.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;

/**
 * 全局异常归一化：对外只暴露稳定错误码与可读 message，
 * 底层异常（NPE、IO 等）统一映射 INTERNAL_ERROR，不泄露堆栈与内部细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiError> handleGateway(GatewayException e) {
        log.warn("业务异常 code={} message={}", e.code(), e.getMessage());
        return ResponseEntity.status(e.code().httpStatus())
                .body(new ApiError(e.code().name(), e.getMessage(), Instant.now(clock)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException e) {
        log.warn("非法参数 message={}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.httpStatus())
                .body(new ApiError(ErrorCode.INVALID_REQUEST.name(), e.getMessage(),
                        Instant.now(clock)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> handleUnreadable(Exception e) {
        // 不回显解析器细节，统一稳定分类
        log.warn("请求体无法解析/校验失败 type={}", e.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.httpStatus())
                .body(new ApiError(ErrorCode.INVALID_REQUEST.name(),
                        "请求体格式错误或字段非法", Instant.now(clock)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("未预期异常（对外归一为 INTERNAL_ERROR）", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(new ApiError(ErrorCode.INTERNAL_ERROR.name(),
                        "内部错误，请联系管理员", Instant.now(clock)));
    }
}
