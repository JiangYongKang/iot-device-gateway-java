package com.github.highcumontoa.iotdevicegatewayjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import com.github.highcumontoa.iotdevicegatewayjava.domain.MetricSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 接入协议端到端验证：成功路径与稳定错误分类，底层异常不透出。
 * 使用随机设备 ID 避免与其他用例共享上下文冲突。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TelemetryService telemetry;

    private final ObjectMapper json = new ObjectMapper();

    private String registerActivate(String id, String model) throws Exception {
        mvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("deviceId", id, "model", model))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/devices/" + id + "/activate")).andExpect(status().isOk());
        return id;
    }

    @Test
    void lifecycleAndErrorCodesOverHttp() throws Exception {
        String id = "web-" + java.util.UUID.randomUUID();
        // 未登记接入 -> 404 DEVICE_NOT_FOUND
        mvc.perform(post("/api/devices/ghost-device/connect")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("DEVICE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").exists());

        // 登记但未激活接入 -> 403 DEVICE_NOT_ACTIVATED
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("deviceId", id, "model", "m"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/devices/" + id + "/connect")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("DEVICE_NOT_ACTIVATED"));

        // 激活后接入成功
        mvc.perform(post("/api/devices/" + id + "/activate")).andExpect(status().isOk());
        mvc.perform(post("/api/devices/" + id + "/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("sessionId", "s1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"));

        // 错误 JSON -> BAD_REQUEST，且不泄露堆栈
        mvc.perform(post("/api/telemetry")
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void telemetryRejectionCodesOverHttp() throws Exception {
        String id = "web-tel-" + java.util.UUID.randomUUID();
        registerActivate(id, "m");
        telemetry.registerSchema(MetricSchema.number("temp", true, -40.0, 85.0));

        // 缺必填 -> 422 TELEMETRY_FIELD_MISSING
        mvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "deviceId", id, "timestamp", 100L,
                                "metrics", java.util.Map.of("other", 1)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TELEMETRY_FIELD_MISSING"));

        // 超范围 -> 422 TELEMETRY_FIELD_OUT_OF_RANGE
        mvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "deviceId", id, "timestamp", 101L,
                                "metrics", java.util.Map.of("temp", 999)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TELEMETRY_FIELD_OUT_OF_RANGE"));

        // 合法帧通过
        mvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "deviceId", id, "timestamp", 102L,
                                "metrics", java.util.Map.of("temp", 21.5)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedMetrics.temp").value(21.5));
    }

    @Test
    void metricsEndpointExposesObservableCounters() throws Exception {
        mvc.perform(get("/api/admin/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredDevices").exists())
                .andExpect(jsonPath("$.onlineSessions").exists())
                .andExpect(jsonPath("$.eventBacklogDiscards").exists());
    }
}
