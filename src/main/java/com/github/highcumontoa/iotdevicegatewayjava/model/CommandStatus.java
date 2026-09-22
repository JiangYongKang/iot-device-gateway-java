package com.github.highcumontoa.iotdevicegatewayjava.model;

/** 待下发指令的明确状态；过期/失败必须显式标记，禁止静默丢弃。 */
public enum CommandStatus {
    PENDING,      // 离线缓冲中，等待投递
    DELIVERED,    // 已投递给在线会话
    ACKED,        // 设备已确认
    EXPIRED,      // 超过有效期
    FAILED        // 投递失败（会话失效等）
}
