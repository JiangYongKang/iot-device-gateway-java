package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * 分批升级结果：每个设备单独判定、原子生效。
 * accepted 为成功创建升级的 upgradeId；rejected 为 deviceId -> 原因，
 * 单台不兼容不影响其余设备，但每台设备自身不会出现半升级。
 */
public record BatchUpgradeResult(
        String targetVersion,
        Map<String, String> accepted,
        Map<String, String> rejected,
        List<UpgradeRecord> records
) {
}
