package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 单个期望键的收敛状态：
 * PENDING   —— 已写入期望，等待设备回报
 * SYNCED    —— 设备回报与期望一致，已收敛
 * REJECTED  —— 设备明确回报无法满足（带原因）
 * SUPERSEDED—— 已被更新版本的期望取代（历史结论可追溯）
 */
public enum DesiredStatus {
    PENDING,
    SYNCED,
    REJECTED,
    SUPERSEDED
}
