package com.github.highcumontoa.iotdevicegatewayjava.sim;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandService;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import com.github.highcumontoa.iotdevicegatewayjava.model.Device;
import com.github.highcumontoa.iotdevicegatewayjava.model.FirmwareRecord;
import com.github.highcumontoa.iotdevicegatewayjava.registry.DeviceRegistryService;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateFieldSpec;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateSchema;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.MetricSchema;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.MetricSpec;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.VersionCompare;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地模拟编排：登记 schema、设备、固件、规则，并提供模拟设备工厂。
 * 不启动后台线程：所有交互都是可复现的同步步骤，便于单测断言。
 */
@Component
public class SimulatorService {

    private static final Logger log = LoggerFactory.getLogger(SimulatorService.class);

    public static final String MODEL = "sensor-thermo-v1";

    private final DeviceRegistryService registry;
    private final SessionManager sessions;
    private final TelemetryService telemetry;
    private final CommandService commands;
    private final StateService state;
    private final FirmwareService firmware;
    private final MetricSchema metricSchema;
    private final StateSchema stateSchema;
    private final Map<String, SimulatedDevice> devices = new ConcurrentHashMap<>();

    public SimulatorService(DeviceRegistryService registry, SessionManager sessions,
                            TelemetryService telemetry, CommandService commands,
                            StateService state, FirmwareService firmware,
                            MetricSchema metricSchema, StateSchema stateSchema) {
        this.registry = registry;
        this.sessions = sessions;
        this.telemetry = telemetry;
        this.commands = commands;
        this.state = state;
        this.firmware = firmware;
        this.metricSchema = metricSchema;
        this.stateSchema = stateSchema;
    }

    /** 注册内置型号 schema（温度/湿度指标；目标温度可写、序列号只读）。 */
    public void installModelSchema() {
        metricSchema.registerModel(MODEL, Set.of(
                new MetricSpec("temperature", true, -40.0, 85.0),
                new MetricSpec("humidity", false, 0.0, 100.0)
        ));
        stateSchema.registerModel(MODEL, Set.of(
                new StateFieldSpec("targetTemp", true, -20.0, 60.0),
                new StateFieldSpec("serialNo", false, 0, 0)
        ));
        log.info("内置型号 schema 安装完成 model={}", MODEL);
    }

    public Device provisionDevice(String deviceId, String firmwareVersion, boolean activate) {
        Device device = registry.register(deviceId, MODEL, "HW-1.0", firmwareVersion);
        metricSchema.bindDevice(deviceId, MODEL);
        stateSchema.bindDevice(deviceId, MODEL);
        if (activate) {
            device = registry.activate(deviceId);
        }
        log.info("模拟设备就绪 deviceId={} fw={} activated={}",
                deviceId, firmwareVersion, activate);
        return device;
    }

    public FirmwareRecord publishFirmware(String version, String fromMin, String fromMax) {
        return firmware.publish(new FirmwareRecord(version, fromMin, fromMax,
                MODEL, false, Instant.now()));
    }

    public SimulatedDevice device(String deviceId) {
        return devices.computeIfAbsent(deviceId, k -> new SimulatedDevice(
                k, sessions, telemetry, commands, state, firmware));
    }

    public String newSessionId() {
        return "sess-" + UUID.randomUUID();
    }

    // 避免未使用导入告警（VersionCompare 供场景脚本使用）
    static int cmp(String a, String b) {
        return VersionCompare.compare(a, b);
    }
}
