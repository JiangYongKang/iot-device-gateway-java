package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 设备档案。不可变值对象，状态变更由登记处原子替换。
 *
 * @param deviceId        设备唯一标识
 * @param model           设备型号（用于固件兼容判定）
 * @param status          生命周期状态
 * @param registeredAt    登记时间
 * @param activatedAt     激活时间（未激活为 null）
 * @param firmwareVersion 当前固件版本（未上报为 null）
 */
public record Device(
        String deviceId,
        String model,
        DeviceStatus status,
        long registeredAt,
        Long activatedAt,
        String firmwareVersion
) {
    public Device withStatus(DeviceStatus newStatus, Long newActivatedAt) {
        return new Device(deviceId, model, newStatus, registeredAt, newActivatedAt, firmwareVersion);
    }

    public Device withFirmwareVersion(String version) {
        return new Device(deviceId, model, status, registeredAt, activatedAt, version);
    }
}
