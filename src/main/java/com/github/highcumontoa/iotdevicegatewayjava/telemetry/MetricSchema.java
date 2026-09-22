package com.github.highcumontoa.iotdevicegatewayjava.telemetry;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备型号 -> 指标约束的注册表（本地权威定义）。
 * 未知字段（未在该型号 schema 中声明）按 TYPE_MISMATCH 拒绝，
 * 与必填缺失 MISSING_FIELD、超范围 OUT_OF_RANGE 形成三类可区分原因。
 */
@Component
public class MetricSchema {

    private final Map<String, Map<String, MetricSpec>> byModel = new ConcurrentHashMap<>();
    private final Map<String, String> deviceModel = new ConcurrentHashMap<>();

    public void registerModel(String model, Set<MetricSpec> specs) {
        Map<String, MetricSpec> map = new HashMap<>();
        for (MetricSpec spec : specs) {
            map.put(spec.field(), spec);
        }
        byModel.put(model, Map.copyOf(map));
    }

    public void bindDevice(String deviceId, String model) {
        deviceModel.put(deviceId, model);
    }

    public Map<String, MetricSpec> specsFor(String deviceId) {
        String model = deviceModel.get(deviceId);
        if (model == null) {
            return Map.of();
        }
        return byModel.getOrDefault(model, Map.of());
    }
}
