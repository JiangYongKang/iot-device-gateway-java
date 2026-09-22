package com.github.highcumontoa.iotdevicegatewayjava.model;

/**
 * 接入结果：
 *  - reused=true  同一 sessionId 幂等重入，结论确定且不产生第二个会话
 *  - takenOver=true 新会话接管了旧会话，旧会话立即失效
 */
public record ConnectResult(DeviceSession session, boolean reused, boolean takenOver) {
}
