package com.github.highcumontoa.iotdevicegatewayjava.model;

/** 固件升级生命周期。 */
public enum UpgradeStatus {
    PENDING,      // 已创建，等待下发
    DOWNLOADING,
    INSTALLING,
    SUCCEEDED,
    FAILED,
    ROLLED_BACK,  // 失败/失联后按策略回滚完成
    ROLLED_BACK_FAILED
}
