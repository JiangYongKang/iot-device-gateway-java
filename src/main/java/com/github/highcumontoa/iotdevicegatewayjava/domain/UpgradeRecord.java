package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 一次固件升级的可追溯记录（追加写，永不物理删除）。
 */
public record UpgradeRecord(
        String upgradeId,
        String deviceId,
        String fromVersion,
        String toVersion,
        UpgradeStatus status,
        long startedAt,
        Long finishedAt,
        String reason,
        String rolledBackToVersion
) {
    public static UpgradeRecord inProgress(String upgradeId, String deviceId, String fromVersion,
                                           String toVersion, long startedAt) {
        return new UpgradeRecord(upgradeId, deviceId, fromVersion, toVersion,
                UpgradeStatus.IN_PROGRESS, startedAt, null, null, null);
    }

    public UpgradeRecord finish(UpgradeStatus newStatus, Long finishedAt, String newReason,
                                String rollbackVersion) {
        return new UpgradeRecord(upgradeId, deviceId, fromVersion, toVersion, newStatus,
                startedAt, finishedAt, newReason, rollbackVersion);
    }
}
