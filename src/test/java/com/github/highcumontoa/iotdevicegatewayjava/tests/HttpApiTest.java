package com.github.highcumontoa.iotdevicegatewayjava.tests;

import com.github.highcumontoa.iotdevicegatewayjava.sim.SimulatorService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HttpApiTest {

    private static final Logger log = LoggerFactory.getLogger(HttpApiTest.class);

    @Autowired MockMvc mvc;
    @Autowired SimulatorService simulator;

    @BeforeAll
    static void note() {
        log.info("[验证方式] HTTP 层断言错误码与 HTTP 状态分类稳定可区分");
    }

    @Test
    void errorCategoriesAreStableOverHttp() throws Exception {
        simulator.installModelSchema();
        simulator.provisionDevice("dev-http-active", "1.0.0", true);
        simulator.provisionDevice("dev-http-inactive", "1.0.0", false);

        // 未登记 -> 404 DEVICE_NOT_FOUND
        mvc.perform(post("/api/devices/ghost/connect")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"sessionId":"s1"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
        log.info("[判定依据] 未登记设备接入 HTTP=404 code=DEVICE_NOT_FOUND");

        // 未激活 -> 403 DEVICE_NOT_ACTIVATED
        mvc.perform(post("/api/devices/dev-http-inactive/connect")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"sessionId":"s1"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVATED"));
        log.info("[判定依据] 未激活设备接入 HTTP=403 code=DEVICE_NOT_ACTIVATED");

        // 请求体非法 -> 400 INVALID_REQUEST（JSON 解析错误也被归一化）
        mvc.perform(post("/api/devices/dev-http-active/connect")
                        .contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().is4xxClientError());
        log.info("[判定依据] 非法 JSON 被归一为 4xx，底层解析异常不透出");
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        // 登记 + 激活
        mvc.perform(post("/api/admin/devices")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"deviceId":"dev-http-e2e","model":"sensor-thermo-v1",
                                 "hardwareVersion":"HW-1.0","firmwareVersion":"1.0.0"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));
        mvc.perform(post("/api/admin/devices/dev-http-e2e/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));

        // 接入
        mvc.perform(post("/api/devices/dev-http-e2e/connect")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"sessionId":"sess-e2e"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.takenOver").value(false));

        // 遥测成功（temperature 合法）
        mvc.perform(post("/api/devices/dev-http-e2e/telemetry")
                        .header("X-Session-Id", "sess-e2e")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"seq":1,"ts":"2026-09-22T00:00:00Z","metrics":{"temperature":23.5}}"""))
                .andExpect(status().isOk());

        // 遥测非法 -> 422 TELEMETRY_VALIDATION_FAILED
        mvc.perform(post("/api/devices/dev-http-e2e/telemetry")
                        .header("X-Session-Id", "sess-e2e")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"seq":2,"ts":"2026-09-22T00:00:01Z","metrics":{"temperature":999}}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TELEMETRY_VALIDATION_FAILED"));
        log.info("[判定依据] 超范围遥测 HTTP=422 code=TELEMETRY_VALIDATION_FAILED");

        // 下发期望状态并查询收敛
        mvc.perform(post("/api/devices/dev-http-e2e/state/desired")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedVersion":0,"desired":{"targetTemp":24.0}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desiredVersion").value(1));
        mvc.perform(get("/api/devices/dev-http-e2e/state/convergence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("PENDING"));
        log.info("[判定依据] 期望下发后收敛查询 overall=PENDING");
    }
}
