package com.github.highcumontoa.iotdevicegatewayjava.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.Clock;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.StripedLocks;
import com.github.highcumontoa.iotdevicegatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceStatus;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import org.springframework.stereotype.Service;

/**
 * 设备登记处：设备档案与生命周期的权威存储。
 *
 * <p>并发模型：每设备一把分条锁保证同一设备的登记/状态变更串行；
 * 全局登记数量使用原子计数 + 全局容量锁保证超限拒绝时不残留部分写入。</p>
 */
@Service
public class DeviceRegistry {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final AtomicInteger registeredCount = new AtomicInteger();
    private final StripedLocks deviceLocks = new StripedLocks(256);
    /** 容量扣减必须与登记动作原子，单独一把全局锁，避免并发双超。 */
    private final ReentrantLock capacityLock = new ReentrantLock();

    private final Clock clock;
    private final GatewayProperties properties;
    private final CapacityMetrics metrics;

    public DeviceRegistry(Clock clock, GatewayProperties properties, CapacityMetrics metrics) {
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 登记设备（幂等）：未登记则创建 REGISTERED 档案；已登记则拒绝（DEVICE_ALREADY_REGISTERED）。
     * 超过全局登记上限时按 CAPACITY_EXCEEDED 拒绝，且不会留下任何档案。
     */
    public Device register(String deviceId, String model) {
        validateId(deviceId);
        if (model == null || model.isBlank()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "设备型号不能为空");
        }
        ReentrantLock lock = deviceLocks.forKey(deviceId);
        lock.lock();
        try {
            Device existing = devices.get(deviceId);
            if (existing != null) {
                throw GatewayException.of(ErrorCode.DEVICE_ALREADY_REGISTERED,
                        "设备已登记: " + deviceId);
            }
            capacityLock.lock();
            try {
                if (registeredCount.get() >= properties.getCapacity().getMaxRegisteredDevices()) {
                    metrics.incrementCapacityRejections();
                    throw GatewayException.of(ErrorCode.CAPACITY_EXCEEDED,
                            "已登记设备数达到上限: " + properties.getCapacity().getMaxRegisteredDevices());
                }
                Device device = new Device(deviceId, model, DeviceStatus.REGISTERED,
                        clock.nowMillis(), null, null);
                devices.put(deviceId, device);
                registeredCount.incrementAndGet();
                return device;
            } finally {
                capacityLock.unlock();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 激活设备：仅 REGISTERED 可激活。 */
    public Device activate(String deviceId) {
        return transition(deviceId, DeviceStatus.ACTIVE);
    }

    /** 停用设备：已停用再次停用按幂等返回当前档案。 */
    public Device disable(String deviceId) {
        return transition(deviceId, DeviceStatus.DISABLED);
    }

    private Device transition(String deviceId, DeviceStatus target) {
        validateId(deviceId);
        ReentrantLock lock = deviceLocks.forKey(deviceId);
        lock.lock();
        try {
            Device device = requireDevice(deviceId);
            if (device.status() == target) {
                return device; // 幂等
            }
            Long activatedAt = device.activatedAt();
            if (target == DeviceStatus.ACTIVE && activatedAt == null) {
                activatedAt = clock.nowMillis();
            }
            Device updated = device.withStatus(target, activatedAt);
            devices.put(deviceId, updated);
            return updated;
        } finally {
            lock.unlock();
        }
    }

    /** 获取档案；不存在按 DEVICE_NOT_FOUND 拒绝（与未激活可区分）。 */
    public Device get(String deviceId) {
        validateId(deviceId);
        return requireDevice(deviceId);
    }

    public int registeredCount() {
        return registeredCount.get();
    }

    /** 更新设备当前固件版本（固件升级流程成功后由固件服务调用）。 */
    public Device updateFirmwareVersion(String deviceId, String firmwareVersion) {
        validateId(deviceId);
        ReentrantLock lock = deviceLocks.forKey(deviceId);
        lock.lock();
        try {
            Device device = requireDevice(deviceId);
            Device updated = device.withFirmwareVersion(firmwareVersion);
            devices.put(deviceId, updated);
            return updated;
        } finally {
            lock.unlock();
        }
    }

    /** 供同包/协作组件复用的设备锁，避免跨组件加锁顺序不一致。 */
    public ReentrantLock lockFor(String deviceId) {
        return deviceLocks.forKey(deviceId);
    }

    private Device requireDevice(String deviceId) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw GatewayException.of(ErrorCode.DEVICE_NOT_FOUND, "设备未登记: " + deviceId);
        }
        return device;
    }

    private static void validateId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "设备 ID 不能为空");
        }
    }
}
