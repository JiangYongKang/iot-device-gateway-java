package com.github.highcumontoa.iotdevicegatewayjava.model;

import java.time.Instant;

/** 可追溯的升级记录（不可变，状态迁移时整体替换）。 */
public record UpgradeRecord(
        String upgradeId,
        String deviceId,
        String targetVersion,
        String fromVersion,
        UpgradeStatus status,
        String detail,
        Instant startedAt,
        Instant finishedAt
) {
}
