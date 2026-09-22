package com.github.highcumontoa.iotdevicegatewayjava.registry;

import com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.Device;
import com.github.highcumontoa.iotdevicegatewayjava.model.DeviceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备登记 / 激活 / 停用。
 * 拒绝原因可区分：
 *  - DEVICE_NOT_FOUND   设备未登记
 *  - DEVICE_NOT_ACTIVATED 已登记但未激活（或已被停用）
 *  - QUOTA_EXCEEDED     登记数量超过配置上限
 */
@Service
public class DeviceRegistryService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistryService.class);

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final GatewayProperties properties;
    private final Clock clock;

    public DeviceRegistryService(GatewayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Device register(String id, String model, String hardwareVersion, String firmwareVersion) {
        if (id == null || id.isBlank()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "deviceId 不能为空");
        }
        synchronized (devices) {
            Device existing = devices.get(id);
            if (existing != null) {
                log.info("设备登记幂等命中 deviceId={} status={}", id, existing.status());
                return existing;
            }
            if (devices.size() >= properties.getMaxDevices()) {
                log.warn("设备登记被拒：超过容量上限 deviceId={} max={}", id, properties.getMaxDevices());
                throw new GatewayException(ErrorCode.QUOTA_EXCEEDED,
                        "设备数量超过上限 " + properties.getMaxDevices());
            }
            Device device = new Device(id, model, DeviceStatus.REGISTERED,
                    firmwareVersion, hardwareVersion, Instant.now(clock));
            devices.put(id, device);
            log.info("设备登记成功 deviceId={} model={}", id, model);
            return device;
        }
    }

    public Device activate(String id) {
        Device device = requireExisting(id);
        synchronized (devices) {
            if (device.status() == DeviceStatus.ACTIVATED) {
                return device;
            }
            Device activated = new Device(device.id(), device.model(), DeviceStatus.ACTIVATED,
                    device.firmwareVersion(), device.hardwareVersion(), device.registeredAt());
            devices.put(id, activated);
            log.info("设备激活成功 deviceId={}", id);
            return activated;
        }
    }

    public Device disable(String id) {
        Device device = requireExisting(id);
        synchronized (devices) {
            Device disabled = new Device(device.id(), device.model(), DeviceStatus.DISABLED,
                    device.firmwareVersion(), device.hardwareVersion(), device.registeredAt());
            devices.put(id, disabled);
            log.info("设备已停用 deviceId={}", id);
            return disabled;
        }
    }

    /** 接入鉴权：未登记 / 未激活 / 已停用必须给出可区分结论。 */
    public Device requireActivated(String id) {
        Device device = requireExisting(id);
        if (device.status() == DeviceStatus.REGISTERED) {
            log.info("接入被拒 deviceId={} 原因=未激活", id);
            throw new GatewayException(ErrorCode.DEVICE_NOT_ACTIVATED,
                    "设备 " + id + " 尚未激活");
        }
        if (device.status() == DeviceStatus.DISABLED) {
            log.info("接入被拒 deviceId={} 原因=已停用", id);
            throw new GatewayException(ErrorCode.DEVICE_NOT_ACTIVATED,
                    "设备 " + id + " 已被停用");
        }
        return device;
    }

    public Device get(String id) {
        return requireExisting(id);
    }

    /** 固件升级成功后更新设备当前版本（整体替换，避免半更新）。 */
    public Device updateFirmwareVersion(String id, String newVersion) {
        synchronized (devices) {
            Device device = requireExisting(id);
            Device updated = new Device(device.id(), device.model(), device.status(),
                    newVersion, device.hardwareVersion(), device.registeredAt());
            devices.put(id, updated);
            return updated;
        }
    }

    private Device requireExisting(String id) {
        Device device = devices.get(id);
        if (device == null) {
            log.info("设备不存在 deviceId={}", id);
            throw new GatewayException(ErrorCode.DEVICE_NOT_FOUND, "设备未登记: " + id);
        }
        return device;
    }
}
