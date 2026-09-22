package com.github.highcumontoa.iotdevicegatewayjava.domain;

import java.util.Set;

/**
 * 固件目录中的一个固件版本。
 *
 * @param version         版本号
 * @param model           适用设备型号
 * @param compatibleFrom  允许从哪些旧版本升级；null 表示允许任意旧版本
 * @param blocked         是否被运营禁止下发（禁止下发 FIRMWARE_VERSION_NOT_ALLOWED）
 */
public record FirmwareInfo(
        String version,
        String model,
        Set<String> compatibleFrom,
        boolean blocked
) {
    public boolean allowsUpgradeFrom(String currentVersion) {
        if (blocked) {
            return false;
        }
        if (compatibleFrom == null) {
            return true;
        }
        return compatibleFrom.contains(currentVersion);
    }
}
