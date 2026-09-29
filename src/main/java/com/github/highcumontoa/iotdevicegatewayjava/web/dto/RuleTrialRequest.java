package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

import com.github.highcumontoa.iotdevicegatewayjava.model.EventRecord;

import java.util.List;

/**
 * 候选规则历史试跑请求：
 * candidateVersion 缺省(0)时使用该规则当前已准备但未启用的候选版本；
 * samples 为内联样本；eventIds 非空时改用已留存的历史事件，二选一。
 */
public record RuleTrialRequest(String ruleId, Integer candidateVersion,
                               List<String> eventIds, List<EventRecord> samples, String reason) {
}
