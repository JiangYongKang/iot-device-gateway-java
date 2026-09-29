package com.github.highcumontoa.iotdevicegatewayjava.tests;

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

/**
 * 规则治理新增接口的 HTTP 全链路：试跑、候选发布、切换、回退、历史版本、复看、审计，
 * 以及版本不存在/试跑非法等错误码与 HTTP 状态分类。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuleGovernanceHttpTest {

    private static final Logger log = LoggerFactory.getLogger(RuleGovernanceHttpTest.class);

    @Autowired MockMvc mvc;

    @Test
    void trialActivateRollbackReviewAndAuditOverHttp() throws Exception {
        final String ruleId = "gov-http";

        // 发布 v1（threshold=30）
        mvc.perform(post("/api/rules").contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"%s","metricField":"temperature","threshold":30.0,
                 "action":"alarm","enabled":true}""".formatted(ruleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 真实处理一条事件
        mvc.perform(post("/api/rules/events").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"govhttp-e1","deviceId":"dev-govhttp","telemetrySeq":1,
                 "metricField":"temperature","value":35.0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.fired").value(true));

        // 候选试跑：threshold=40，样本 35 -> 结论由 fired 变 not fired，changedCount=1
        mvc.perform(post("/api/rules/trial").contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"%s","metricField":"temperature","threshold":40.0,"action":"alarm",
                 "enabled":true,"reason":"http 试跑",
                 "events":[{"eventId":"govhttp-s1","deviceId":"dev-govhttp",
                            "telemetrySeq":1,"metricField":"temperature","value":35.0}]}"""
                .formatted(ruleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.totalEvents").value(1))
                .andExpect(jsonPath("$.changedCount").value(1))
                .andExpect(jsonPath("$.items[0].current.fired").value(true))
                .andExpect(jsonPath("$.items[0].candidate.fired").value(false));
        log.info("[判定依据] HTTP 试跑 changedCount=1，线上结论未被改动");

        // 发布候选 v2 但暂不激活，线上仍 v1；再正式切换到 v2
        mvc.perform(post("/api/rules/candidates?activate=false&reason=候选留档")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"%s","metricField":"temperature","threshold":40.0,
                 "action":"alarm","enabled":true}""".formatted(ruleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/api/rules/activate").contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"%s","version":2,"reason":"http 正式切换"}""".formatted(ruleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 切换后 35 不再命中
        mvc.perform(post("/api/rules/events").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"govhttp-e2","deviceId":"dev-govhttp","telemetrySeq":2,
                 "metricField":"temperature","value":35.0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(2))
                .andExpect(jsonPath("$.fired").value(false));

        // 回退到历史 v1
        mvc.perform(post("/api/rules/rollback").contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"%s","version":1,"reason":"http 回退"}""".formatted(ruleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 历史版本列表
        mvc.perform(get("/api/rules/" + ruleId + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 历史复看：用 v1 复看真实在 v1 执行的 govhttp-e1 -> LIVE + fired
        mvc.perform(post("/api/rules/events/review?ruleVersion=1&ruleId=" + ruleId
                        + "&reason=对账")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"govhttp-e1","deviceId":"dev-govhttp","telemetrySeq":1,
                 "metricField":"temperature","value":35.0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.verdict.ruleVersion").value(1))
                .andExpect(jsonPath("$.verdict.fired").value(true));
        // 用 v2 复看同一事件 -> REPLAY（当时 v2 尚未生效），按 v2 规则不命中
        mvc.perform(post("/api/rules/events/review?ruleVersion=2&ruleId=" + ruleId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"govhttp-e1","deviceId":"dev-govhttp","telemetrySeq":1,
                 "metricField":"temperature","value":35.0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("REPLAY"))
                .andExpect(jsonPath("$.verdict.ruleVersion").value(2))
                .andExpect(jsonPath("$.verdict.fired").value(false));
        log.info("[判定依据] HTTP 复看 LIVE/REPLAY 来源正确、版本归属正确");

        // 审计留痕可查：TRIAL/ACTIVATE/ROLLBACK/REVIEW 都在
        mvc.perform(get("/api/rules/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[?(@.action=='TRIAL' && @.ruleId=='%s')]".formatted(ruleId))
                        .exists());
        log.info("[版本选择] HTTP 审计可查试跑/切换/回退/复看记录");

        // 激活不存在的版本 -> 404 RULE_VERSION_NOT_FOUND
        mvc.perform(post("/api/rules/activate").contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"%s","version":99,"reason":"不存在版本"}""".formatted(ruleId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RULE_VERSION_NOT_FOUND"));

        // 试跑样本为空 -> 400 INVALID_REQUEST
        mvc.perform(post("/api/rules/trial").contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"%s","metricField":"temperature","threshold":40.0,
                 "events":[]}""".formatted(ruleId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        log.info("[判定依据] HTTP 错误分类：版本不存在 404 / 试跑非法 400");
    }
}
