package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import com.github.highcumontoa.iotdevicegatewayjava.model.BatchUpgradeResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.FirmwareRecord;
import com.github.highcumontoa.iotdevicegatewayjava.model.UpgradeRecord;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.FirmwarePublishRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.UpgradeProgressRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.UpgradeRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.BatchUpgradeRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 固件发布、单台/分批升级、进度回报、升级记录。 */
@RestController
@RequestMapping("/api/firmware")
public class FirmwareController {

    private final FirmwareService firmware;

    public FirmwareController(FirmwareService firmware) {
        this.firmware = firmware;
    }

    @PostMapping("/publish")
    public FirmwareRecord publish(@RequestBody FirmwarePublishRequest req) {
        return firmware.publish(new FirmwareRecord(req.version(), req.fromMinInclusive(),
                req.fromMaxExclusive(), req.compatibleModel(),
                Boolean.TRUE.equals(req.yanked()), Instant.now()));
    }

    @PostMapping("/devices/{deviceId}/upgrade")
    public UpgradeRecord upgrade(@PathVariable String deviceId,
                                 @RequestBody UpgradeRequest req) {
        return firmware.startUpgrade(deviceId, req.targetVersion(), req.idempotencyKey());
    }

    @PostMapping("/batch-upgrade")
    public BatchUpgradeResult batch(@RequestBody BatchUpgradeRequest req) {
        return firmware.startBatch(req.deviceIds(), req.targetVersion(),
                req.idempotencyKeyPrefix());
    }

    @PostMapping("/devices/{deviceId}/progress")
    public UpgradeRecord progress(@PathVariable String deviceId,
                                  @RequestBody UpgradeProgressRequest req) {
        return firmware.reportProgress(deviceId, req.sessionId(), req.upgradeId(),
                req.status(), req.detail());
    }

    @GetMapping("/devices/{deviceId}/history")
    public List<UpgradeRecord> history(@PathVariable String deviceId) {
        return firmware.history(deviceId);
    }
}
