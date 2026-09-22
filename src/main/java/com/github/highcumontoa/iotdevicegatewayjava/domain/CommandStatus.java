package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 待下发指令状态机：
 * QUEUED    —— 设备离线，缓冲中
 * DELIVERED —— 已投递给当前在线会话（恰好一次）
 * ACKED     —— 设备确认执行成功
 * FAILED    —— 投递/执行失败（带原因，不静默丢弃）
 * EXPIRED   —— 超过 TTL，重新上线或扫描时判定过期（带原因）
 */
public enum CommandStatus {
    QUEUED,
    DELIVERED,
    ACKED,
    FAILED,
    EXPIRED
}
