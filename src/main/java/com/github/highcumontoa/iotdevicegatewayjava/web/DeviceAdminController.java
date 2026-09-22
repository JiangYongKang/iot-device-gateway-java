package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.model.Device;
import com.github.highcumontoa.iotdevicegatewayjava.registry.DeviceRegistryService;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateSchema;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.MetricSchema;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.RegisterDeviceRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设备登记 / 激活 / 停用管理。 */
@RestController
@RequestMapping("/api/admin/devices")
public class DeviceAdminController {

    private final DeviceRegistryService registry;
    private final MetricSchema metricSchema;
    private final StateSchema stateSchema;

    public DeviceAdminController(DeviceRegistryService registry,
                                 MetricSchema metricSchema, StateSchema stateSchema) {
        this.registry = registry;
        this.metricSchema = metricSchema;
        this.stateSchema = stateSchema;
    }

    @PostMapping
    public Device register(@RequestBody RegisterDeviceRequest req) {
        Device device = registry.register(req.deviceId(), req.model(),
                req.hardwareVersion(), req.firmwareVersion());
        if (req.model() != null) {
            metricSchema.bindDevice(req.deviceId(), req.model());
            stateSchema.bindDevice(req.deviceId(), req.model());
        }
        return device;
    }

    @PostMapping("/{deviceId}/activate")
    public Device activate(@PathVariable String deviceId) {
        return registry.activate(deviceId);
    }

    @PostMapping("/{deviceId}/disable")
    public Device disable(@PathVariable String deviceId) {
        return registry.disable(deviceId);
    }
}
