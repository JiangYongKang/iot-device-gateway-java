package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandQueueService;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicegatewayjava.domain.SessionInfo;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceGateway;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备档案、接入生命周期与指令投递的 HTTP 接入接口。
 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceRegistry registry;
    private final DeviceGateway gateway;
    private final CommandQueueService commandQueue;

    public DeviceController(DeviceRegistry registry, DeviceGateway gateway,
                            CommandQueueService commandQueue) {
        this.registry = registry;
        this.gateway = gateway;
        this.commandQueue = commandQueue;
    }

    public record RegisterDeviceRequest(String deviceId, String model) {}
    public record ConnectRequest(String sessionId) {}
    public record DisconnectRequest(String sessionId) {}
    public record SendCommandRequest(String type, String payload, Long ttlMillis) {}
    public record AckCommandRequest(String sessionId, Boolean success, String reason) {}

    @PostMapping("/devices")
    public Device register(@RequestBody RegisterDeviceRequest req) {
        return registry.register(req.deviceId(), req.model());
    }

    @PostMapping("/devices/{deviceId}/activate")
    public Device activate(@PathVariable String deviceId) {
        return registry.activate(deviceId);
    }

    @PostMapping("/devices/{deviceId}/disable")
    public Device disable(@PathVariable String deviceId) {
        return registry.disable(deviceId);
    }

    @GetMapping("/devices/{deviceId}")
    public Device get(@PathVariable String deviceId) {
        return registry.get(deviceId);
    }

    @PostMapping("/devices/{deviceId}/connect")
    public SessionInfo connect(@PathVariable String deviceId, @RequestBody(required = false) ConnectRequest req) {
        String sessionId = req == null ? null : req.sessionId();
        return gateway.connect(deviceId, sessionId);
    }

    @PostMapping("/devices/{deviceId}/disconnect")
    public void disconnect(@PathVariable String deviceId, @RequestBody DisconnectRequest req) {
        gateway.disconnect(deviceId, req.sessionId());
    }

    @PostMapping("/devices/{deviceId}/commands")
    public CommandRecord sendCommand(@PathVariable String deviceId, @RequestBody SendCommandRequest req) {
        return gateway.sendCommand(deviceId, req.type(), req.payload(), req.ttlMillis());
    }

    @PostMapping("/devices/{deviceId}/commands/{commandId}/ack")
    public CommandRecord acknowledge(@PathVariable String deviceId, @PathVariable String commandId,
                                     @RequestBody AckCommandRequest req) {
        // 回执必须携带会话身份：旧会话回执被确定性拒绝，防止接管后误确认
        boolean success = req.success() == null || req.success();
        return commandQueue.acknowledge(commandId, req.sessionId(), success, req.reason());
    }

    @GetMapping("/devices/{deviceId}/commands")
    public List<CommandRecord> listCommands(@PathVariable String deviceId) {
        return commandQueue.listForDevice(deviceId);
    }

    @PostMapping("/devices/{deviceId}/commands/sweep-expired")
    public List<CommandRecord> sweepExpired(@PathVariable String deviceId) {
        return gateway.sweepExpired(deviceId);
    }
}
