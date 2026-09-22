package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/**
 * 设备会话（不可变快照）。sessionId 单调，接管时旧会话立即失效，
 * 任何来自旧会话的后续操作都必须被判定为 SESSION_STALE。
 */
public record DeviceSession(
        String deviceId,
        String sessionId,
        long generation,
        Instant connectedAt
) {
}
