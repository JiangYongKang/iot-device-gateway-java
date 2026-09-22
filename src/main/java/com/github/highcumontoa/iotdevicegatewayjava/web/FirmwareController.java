package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.domain.FirmwareInfo;
import com.github.highcumontoa.iotdevicegatewayjava.domain.UpgradeRecord;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 固件目录维护、单设备升级、分批升级与升级结果回报接口。
 */
@RestController
@RequestMapping("/api/firmware")
public class FirmwareController {

    private final FirmwareService firmwareService;

    public FirmwareController(FirmwareService firmwareService) {
        this.firmwareService = firmwareService;
    }

    public record FirmwareRequest(String version, String model, List<String> compatibleFrom, Boolean blocked) {}
    public record UpgradeRequest(String targetVersion, String idempotencyKey) {}
    public record BatchUpgradeRequest(List<String> deviceIds, String targetVersion, String idempotencyKey) {}
    public record UpgradeResultRequest(String upgradeId, Boolean success, String reason) {}

    @PostMapping
    public FirmwareInfo add(@RequestBody FirmwareRequest req) {
        java.util.Set<String> compatible = req.compatibleFrom() == null
                ? null : new java.util.HashSet<>(req.compatibleFrom());
        return firmwareService.addFirmware(new FirmwareInfo(req.version(), req.model(), compatible,
                Boolean.TRUE.equals(req.blocked())));
    }

    @GetMapping("/{model}")
    public List<FirmwareInfo> list(@PathVariable String model) {
        return firmwareService.listFirmware(model);
    }

    @PostMapping("/devices/{deviceId}/upgrades")
    public UpgradeRecord upgrade(@PathVariable String deviceId, @RequestBody UpgradeRequest req) {
        return firmwareService.startUpgrade(deviceId, req.targetVersion(), req.idempotencyKey());
    }

    @PostMapping("/batch-upgrades")
    public List<FirmwareService.UpgradeResult> batch(@RequestBody BatchUpgradeRequest req) {
        return firmwareService.startBatch(req.deviceIds(), req.targetVersion(), req.idempotencyKey());
    }

    @PostMapping("/upgrades/{upgradeId}/result")
    public UpgradeRecord result(@PathVariable String upgradeId,
                                @RequestParam(required = false) Boolean success,
                                @RequestParam(required = false) String reason,
                                @RequestBody(required = false) UpgradeResultRequest body) {
        boolean ok = success != null ? success : (body != null && Boolean.TRUE.equals(body.success()));
        String why = reason != null ? reason : (body == null ? null : body.reason());
        return firmwareService.completeUpgrade(upgradeId, ok, why);
    }

    @PostMapping("/upgrades/sweep-timeouts")
    public List<UpgradeRecord> sweepTimeouts() {
        return firmwareService.sweepTimeouts();
    }

    @GetMapping("/devices/{deviceId}/upgrades")
    public List<UpgradeRecord> upgrades(@PathVariable String deviceId) {
        return firmwareService.listUpgrades(deviceId);
    }
}
