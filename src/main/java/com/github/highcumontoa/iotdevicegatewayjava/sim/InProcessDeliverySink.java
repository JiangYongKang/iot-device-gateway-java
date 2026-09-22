package com.github.highcumontoa.iotdevicegatewayjava.sim;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandDeliverySink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地进程内投递通道：模拟设备侧接收。
 * 可通过 failOnce / failSession 模拟断链与失败，用于验证 FAILED 标记与不重复投递。
 */
@Component
public class InProcessDeliverySink implements CommandDeliverySink {

    private static final Logger log = LoggerFactory.getLogger(InProcessDeliverySink.class);

    private final Set<String> failSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> failOnceCommands = ConcurrentHashMap.newKeySet();
    private final Map<String, String> deliveredToSession = new ConcurrentHashMap<>();

    @Override
    public void deliver(String deviceId, String sessionId, String commandId, String payload) {
        String previous = deliveredToSession.putIfAbsent(commandId, sessionId);
        if (previous != null && !previous.equals(sessionId)) {
            throw new IllegalStateException("command " + commandId
                    + " 曾投递给会话 " + previous + "，拒绝重复投递到 " + sessionId);
        }
        if (failOnceCommands.remove(commandId) || failSessions.contains(sessionId)) {
            throw new RuntimeException("模拟投递失败 deviceId=" + deviceId
                    + " sessionId=" + sessionId + " commandId=" + commandId);
        }
        log.info("[模拟通道] 指令送达 deviceId={} sessionId={} commandId={} payload={}",
                deviceId, sessionId, commandId, payload);
    }

    public void failNext(String commandId) {
        failOnceCommands.add(commandId);
    }

    public void failSession(String sessionId) {
        failSessions.add(sessionId);
    }

    public void recoverSession(String sessionId) {
        failSessions.remove(sessionId);
    }

    public String deliveredSessionOf(String commandId) {
        return deliveredToSession.get(commandId);
    }
}
