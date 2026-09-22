package com.github.highcumontoa.iotdevicegatewayjava.state;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 设备型号 -> 可期望字段约束。 */
@Component
public class StateSchema {

    private final Map<String, Map<String, StateFieldSpec>> byModel = new ConcurrentHashMap<>();
    private final Map<String, String> deviceModel = new ConcurrentHashMap<>();

    public void registerModel(String model, Set<StateFieldSpec> specs) {
        Map<String, StateFieldSpec> map = new HashMap<>();
        for (StateFieldSpec spec : specs) {
            map.put(spec.field(), spec);
        }
        byModel.put(model, Map.copyOf(map));
    }

    public void bindDevice(String deviceId, String model) {
        deviceModel.put(deviceId, model);
    }

    public Map<String, StateFieldSpec> specsFor(String deviceId) {
        String model = deviceModel.get(deviceId);
        if (model == null) {
            return Map.of();
        }
        return byModel.getOrDefault(model, Map.of());
    }
}
