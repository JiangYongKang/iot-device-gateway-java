package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 固件升级状态机：
 * IN_PROGRESS —— 已派发，等待设备结果（同设备同时只允许一个）
 * SUCCESS     —— 设备回报刷写成功
 * FAILED      —— 设备回报失败
 * ROLLED_BACK —— 失败/失联后按策略回滚完成
 * TIMED_OUT   —— 超时失联，已按策略处理
 */
public enum UpgradeStatus {
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    ROLLED_BACK,
    TIMED_OUT
}
