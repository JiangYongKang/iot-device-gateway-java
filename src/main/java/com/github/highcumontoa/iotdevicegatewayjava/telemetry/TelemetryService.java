package com.github.highcumontoa.iotdevicegatewayjava.telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.github.highcumontoa.iotdevicegatewayjava.core.Clock;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.StripedLocks;
import com.github.highcumontoa.iotdevicegatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DeviceStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.MetricSchema;
import com.github.highcumontoa.iotdevicegatewayjava.domain.MetricType;
import com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryPoint;
import com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryResult;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 遥测写入服务。
 *
 * <p>处理顺序（整帧先校验后写入，杜绝部分写入）：</p>
 * <ol>
 *   <li>设备存在且 ACTIVE；</li>
 *   <li>schema 逐字段校验：缺失 / 类型不匹配 / 超范围各自给出可区分错误；</li>
 *   <li>序列号去重（重复上报 TELEMETRY_DUPLICATE）；</li>
 *   <li>时间戳判定：小幅倒退 = 乱序(STALE_TIMESTAMP)，大幅倒退 = 时钟回拨(CLOCK_ROLLBACK)，
 *       旧时间戳绝不覆盖新值。</li>
 * </ol>
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final Map<String, MetricSchema> schemas = new ConcurrentHashMap<>();
    private final Map<String, DeviceState> stateByDevice = new ConcurrentHashMap<>();
    private final StripedLocks deviceLocks = new StripedLocks(256);

    private final DeviceRegistry registry;
    private final Clock clock;
    private final GatewayProperties properties;

    public TelemetryService(DeviceRegistry registry, Clock clock, GatewayProperties properties) {
        this.registry = registry;
        this.clock = clock;
        this.properties = properties;
    }

    /** 注册/覆盖字段 schema（字段定义本身是运营权威配置）。 */
    public MetricSchema registerSchema(MetricSchema schema) {
        if (schema == null || schema.name() == null || schema.name().isBlank()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "字段名不能为空");
        }
        if (schema.type() == null) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "字段类型不能为空: " + schema.name());
        }
        if (schema.type() == MetricType.NUMBER && schema.min() != null && schema.max() != null
                && schema.min() > schema.max()) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED,
                    "字段范围非法: min > max: " + schema.name());
        }
        schemas.put(schema.name(), schema);
        return schema;
    }

    public List<MetricSchema> listSchemas() {
        List<MetricSchema> out = new ArrayList<>(schemas.values());
        out.sort(Comparator.comparing(MetricSchema::name));
        return out;
    }

    public TelemetryResult ingest(TelemetryPoint point) {
        if (point == null || point.deviceId() == null) {
            throw GatewayException.of(ErrorCode.VALIDATION_FAILED, "遥测帧缺少设备标识");
        }
        Device device = registry.get(point.deviceId());
        if (device.status() != DeviceStatus.ACTIVE) {
            throw GatewayException.of(ErrorCode.DEVICE_NOT_ACTIVATED, "设备未激活/已停用: " + point.deviceId());
        }
        if (point.metrics() == null || point.metrics().isEmpty()) {
            throw GatewayException.of(ErrorCode.TELEMETRY_FIELD_MISSING, "遥测帧不包含任何字段");
        }
        // 1) 先做整帧校验（任何字段失败都不写入任何状态）
        Map<String, Object> validated = validateAll(point.metrics());

        ReentrantLock lock = deviceLocks.forKey(point.deviceId());
        lock.lock();
        try {
            DeviceState state = stateByDevice.computeIfAbsent(point.deviceId(), k -> new DeviceState());

            // 2) 序列号去重（同一设备序列号重复 = 重复上报）
            if (point.sequence() != null && state.lastSequence != null
                    && point.sequence() <= state.lastSequence) {
                if (point.sequence().equals(state.lastSequence)) {
                    throw GatewayException.of(ErrorCode.TELEMETRY_DUPLICATE,
                            "重复遥测帧 device=" + point.deviceId() + " sequence=" + point.sequence());
                }
                throw GatewayException.of(ErrorCode.TELEMETRY_STALE_TIMESTAMP,
                        "序列号倒退，拒绝旧帧 device=" + point.deviceId()
                                + " last=" + state.lastSequence + " got=" + point.sequence());
            }

            // 3) 时间戳判定（以历史最大时间戳为基准，禁止以旧覆盖新）
            if (state.maxTimestamp != null && point.timestamp() < state.maxTimestamp) {
                long drift = state.maxTimestamp - point.timestamp();
                if (drift <= properties.getTelemetry().getClockRollbackToleranceMillis()) {
                    throw GatewayException.of(ErrorCode.TELEMETRY_STALE_TIMESTAMP,
                            "遥测时间戳乱序，拒绝旧帧 device=" + point.deviceId()
                                    + " maxTs=" + state.maxTimestamp + " gotTs=" + point.timestamp());
                }
                throw GatewayException.of(ErrorCode.TELEMETRY_CLOCK_ROLLBACK,
                        "检测到时钟回拨，拒绝可疑帧 device=" + point.deviceId()
                                + " maxTs=" + state.maxTimestamp + " gotTs=" + point.timestamp()
                                + " drift=" + drift + "ms");
            }

            // 4) 全部通过：原子提交
            long acceptedAt = clock.nowMillis();
            state.latest.putAll(validated);
            state.maxTimestamp = Math.max(state.maxTimestamp == null ? point.timestamp() : state.maxTimestamp,
                    point.timestamp());
            if (point.sequence() != null) {
                state.lastSequence = point.sequence();
            }
            TelemetryResult result = new TelemetryResult(point.deviceId(), point.timestamp(),
                    point.sequence(), new LinkedHashMap<>(validated), acceptedAt);
            state.history.addLast(result);
            while (state.history.size() > properties.getTelemetry().getHistoryPerDevice()) {
                state.history.pollFirst();
            }
            state.acceptedCount.incrementAndGet();
            log.info("telemetry accepted device={} seq={} ts={} fields={}",
                    point.deviceId(), point.sequence(), point.timestamp(), validated.keySet());
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 逐字段校验。任一字段不合法立即以可区分错误拒绝整帧，
     * 返回前不会修改任何设备状态。未知字段同样按缺失 schema 拒绝（防止静默写入脏字段）。
     */
    private Map<String, Object> validateAll(Map<String, Object> metrics) {
        // 必填字段齐全性
        for (MetricSchema schema : schemas.values()) {
            if (schema.required() && !metrics.containsKey(schema.name())) {
                throw GatewayException.of(ErrorCode.TELEMETRY_FIELD_MISSING,
                        "缺少必填字段: " + schema.name());
            }
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            MetricSchema schema = schemas.get(name);
            if (schema == null) {
                throw GatewayException.of(ErrorCode.TELEMETRY_FIELD_MISSING,
                        "字段未在 schema 中登记，拒绝写入未知字段: " + name);
            }
            validated.put(name, coerceAndCheck(schema, value));
        }
        return validated;
    }

    private Object coerceAndCheck(MetricSchema schema, Object raw) {
        if (raw == null) {
            if (schema.required()) {
                throw GatewayException.of(ErrorCode.TELEMETRY_FIELD_MISSING,
                        "必填字段为 null: " + schema.name());
            }
            return null;
        }
        return switch (schema.type()) {
            case NUMBER -> checkNumber(schema, raw);
            case BOOLEAN -> {
                if (!(raw instanceof Boolean)) {
                    throw mismatch(schema, raw, "BOOLEAN");
                }
                yield raw;
            }
            case STRING -> {
                if (!(raw instanceof String)) {
                    throw mismatch(schema, raw, "STRING");
                }
                yield raw;
            }
        };
    }

    private Double checkNumber(MetricSchema schema, Object raw) {
        double number;
        if (raw instanceof Number n) {
            number = n.doubleValue();
        } else if (raw instanceof String s) {
            try {
                number = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw mismatch(schema, raw, "NUMBER");
            }
        } else {
            throw mismatch(schema, raw, "NUMBER");
        }
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw GatewayException.of(ErrorCode.TELEMETRY_FIELD_OUT_OF_RANGE,
                    "字段值不是有限数值: " + schema.name());
        }
        if (schema.min() != null && number < schema.min()
                || schema.max() != null && number > schema.max()) {
            throw GatewayException.of(ErrorCode.TELEMETRY_FIELD_OUT_OF_RANGE,
                    "字段超出允许范围: " + schema.name() + " value=" + number
                            + " min=" + schema.min() + " max=" + schema.max());
        }
        return number;
    }

    private static GatewayException mismatch(MetricSchema schema, Object raw, String expected) {
        return GatewayException.of(ErrorCode.TELEMETRY_FIELD_TYPE_MISMATCH,
                "字段类型不匹配: " + schema.name() + " 期望=" + expected
                        + " 实际=" + raw.getClass().getSimpleName());
    }

    public Map<String, Object> latest(String deviceId) {
        registry.get(deviceId);
        DeviceState state = stateByDevice.get(deviceId);
        if (state == null) {
            return Map.of();
        }
        ReentrantLock lock = deviceLocks.forKey(deviceId);
        lock.lock();
        try {
            return new LinkedHashMap<>(state.latest);
        } finally {
            lock.unlock();
        }
    }

    public List<TelemetryResult> history(String deviceId, int limit) {
        registry.get(deviceId);
        DeviceState state = stateByDevice.get(deviceId);
        if (state == null) {
            return List.of();
        }
        ReentrantLock lock = deviceLocks.forKey(deviceId);
        lock.lock();
        try {
            List<TelemetryResult> all = new ArrayList<>(state.history);
            int from = Math.max(0, all.size() - Math.max(1, limit));
            return List.copyOf(all.subList(from, all.size()));
        } finally {
            lock.unlock();
        }
    }

    public long acceptedCount(String deviceId) {
        DeviceState state = stateByDevice.get(deviceId);
        return state == null ? 0 : state.acceptedCount.get();
    }

    /** 每设备遥测状态：最新值、最大时间戳、最新序列号、有界历史。 */
    private static final class DeviceState {
        private final Map<String, Object> latest = new LinkedHashMap<>();
        private Long maxTimestamp;
        private Long lastSequence;
        private final Deque<TelemetryResult> history = new ArrayDeque<>();
        private final java.util.concurrent.atomic.AtomicLong acceptedCount =
                new java.util.concurrent.atomic.AtomicLong();
    }
}
