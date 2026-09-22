package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 一条设备会话的不可变快照。
 *
 * @param deviceId          设备 ID
 * @param sessionId         会话 ID（每次接入唯一）
 * @param connectedAt       建立时间
 * @param previousSessionId 被接管的旧会话 ID；首次接入为 null
 * @param takeover          是否发生了会话接管
 */
public record SessionInfo(
        String deviceId,
        String sessionId,
        long connectedAt,
        String previousSessionId,
        boolean takeover
) {
}
