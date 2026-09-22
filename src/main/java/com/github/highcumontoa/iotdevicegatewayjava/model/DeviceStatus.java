package com.github.highcumontoa.iotdevicegatewayjava.model;

/** 设备登记生命周期状态。 */
public enum DeviceStatus {
    REGISTERED,   // 已登记未激活
    ACTIVATED,    // 已激活可接入
    DISABLED      // 被停用
}
