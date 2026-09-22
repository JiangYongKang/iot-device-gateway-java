package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 一条指令的完整记录，所有状态流转均替换新值对象并保留原因。
 */
public record CommandRecord(
        String commandId,
        String deviceId,
        String type,
        String payload,
        long createdAt,
        long expireAt,
        CommandStatus status,
        Long deliveredAt,
        Long ackedAt,
        String sessionId,
        String reason
) {
    public static CommandRecord queued(String commandId, String deviceId, String type, String payload,
                                       long createdAt, long expireAt) {
        return new CommandRecord(commandId, deviceId, type, payload, createdAt, expireAt,
                CommandStatus.QUEUED, null, null, null, null);
    }

    public CommandRecord withStatus(CommandStatus newStatus, long at, String newSessionId, String newReason) {
        Long delivered = this.deliveredAt;
        Long acked = this.ackedAt;
        if (newStatus == CommandStatus.DELIVERED) {
            delivered = at;
        } else if (newStatus == CommandStatus.ACKED) {
            acked = at;
        }
        return new CommandRecord(commandId, deviceId, type, payload, createdAt, expireAt,
                newStatus, delivered, acked, newSessionId != null ? newSessionId : sessionId, newReason);
    }
}
