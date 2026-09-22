package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * 把全部异常归类为 {@link ErrorCode}，保证对外错误分类稳定可区分。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex) {
        String traceId = UUID.randomUUID().toString();
        log.warn("gateway error code={} message={} traceId={}", ex.errorCode(), ex.getMessage(), traceId);
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(new ErrorResponse(ex.errorCode().name(), ex.getMessage(), traceId));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        log.warn("bad request traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(new ErrorResponse(ErrorCode.BAD_REQUEST.name(), "请求参数不合法", traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        // 底层异常细节仅记录服务端日志，不返回给调用方
        log.error("unexpected error traceId={}", traceId, ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", traceId));
    }
}
