package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 设备登记生命周期状态。
 * REGISTERED：已登记未激活，接入必须被拒绝（原因 DEVICE_NOT_ACTIVATED）；
 * ACTIVE：已激活，允许接入；
 * DISABLED：被停用，接入必须被拒绝（原因 DEVICE_NOT_ACTIVATED，语义为无权接入）。
 */
public enum DeviceStatus {
    REGISTERED,
    ACTIVE,
    DISABLED
}
