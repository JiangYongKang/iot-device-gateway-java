package com.github.highcumontoa.iotdevicegatewayjava.domain;

import java.util.Map;

/**
 * 一次期望状态下发的完整版本快照（用于并发版本控制与历史追溯）。
 */
public record DesiredRevision(
        long revision,
        Map<String, Object> desired,
        long createdAt,
        String note
) {
}
