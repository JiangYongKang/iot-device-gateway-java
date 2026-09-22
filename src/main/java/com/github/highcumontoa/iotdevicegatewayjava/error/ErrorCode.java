package com.github.highcumontoa.iotdevicegatewayjava.error;

/**
 * 对外稳定的错误分类。每个错误码都有确定的 HTTP 状态映射，
 * 底层异常不会直接透出给调用方，统一转换为该枚举。
 */
public enum ErrorCode {
    // 4xx —— 调用方可据码区分处理方式
    BAD_REQUEST(400),
    VALIDATION_FAILED(400),
    DEVICE_NOT_FOUND(404),
    DEVICE_NOT_ACTIVATED(403),
    DEVICE_ALREADY_REGISTERED(409),
    DEVICE_OFFLINE(409),
    SESSION_CONFLICT(409),
    COMMAND_EXPIRED(410),
    COMMAND_QUEUE_FULL(503),
    COMMAND_NOT_FOUND(404),
    TELEMETRY_FIELD_MISSING(422),
    TELEMETRY_FIELD_TYPE_MISMATCH(422),
    TELEMETRY_FIELD_OUT_OF_RANGE(422),
    TELEMETRY_DUPLICATE(409),
    TELEMETRY_STALE_TIMESTAMP(409),
    TELEMETRY_CLOCK_ROLLBACK(422),
    STATE_VERSION_CONFLICT(409),
    STATE_UNSATISFIABLE(422),
    STATE_REVISION_NOT_FOUND(404),
    FIRMWARE_NOT_FOUND(404),
    FIRMWARE_INCOMPATIBLE(422),
    FIRMWARE_VERSION_NOT_ALLOWED(403),
    FIRMWARE_UPGRADE_IN_PROGRESS(409),
    RULE_NOT_FOUND(404),
    CAPACITY_EXCEEDED(507),
    RATE_LIMIT_EXCEEDED(429),
    EVENT_QUEUE_FULL(503),
    BACKLOG_DISCARDED(503),
    // 5xx —— 服务端内部错误，仅透出分类与追踪信息
    INTERNAL_ERROR(500),
    SERVICE_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
