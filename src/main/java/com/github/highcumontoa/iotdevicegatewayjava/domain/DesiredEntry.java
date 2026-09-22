package com.github.highcumontoa.iotdevicegatewayjava.domain;

/**
 * 单个期望键的一个版本（版本化值对象）。
 */
public record DesiredEntry(
        String key,
        Object value,
        long revision,
        long updatedAt,
        DesiredStatus status,
        Long syncedAt,
        String reason
) {
    public static DesiredEntry pending(String key, Object value, long revision, long updatedAt) {
        return new DesiredEntry(key, value, revision, updatedAt, DesiredStatus.PENDING, null, null);
    }

    public DesiredEntry withConvergence(DesiredStatus newStatus, Long at, String newReason) {
        return new DesiredEntry(key, value, revision, updatedAt, newStatus, at, newReason);
    }
}
