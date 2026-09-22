package com.github.highcumontoa.iotdevicegatewayjava.error;

/**
 * 稳定、可区分的对外错误分类。name() 即错误码，httpStatus 为对外 HTTP 状态。
 */
public enum ErrorCode {
    DEVICE_NOT_FOUND(404),
    DEVICE_NOT_ACTIVATED(403),
    DEVICE_ALREADY_ONLINE(409),
    SESSION_TAKEN_OVER(409),
    SESSION_STALE(409),
    COMMAND_EXPIRED(410),
    COMMAND_QUEUE_FULL(429),
    TELEMETRY_DUPLICATE(409),
    TELEMETRY_STALE(409),
    TELEMETRY_VALIDATION_FAILED(422),
    DESIRED_REJECTED(422),
    STATE_VERSION_CONFLICT(409),
    FIRMWARE_INCOMPATIBLE(422),
    FIRMWARE_ALREADY_IN_PROGRESS(409),
    FIRMWARE_DUPLICATE_REQUEST(409),
    FIRMWARE_NO_ACTIVE_UPGRADE(409),
    RULE_NOT_FOUND(404),
    CAPACITY_EXCEEDED(429),
    QUOTA_EXCEEDED(429),
    INVALID_REQUEST(400),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
