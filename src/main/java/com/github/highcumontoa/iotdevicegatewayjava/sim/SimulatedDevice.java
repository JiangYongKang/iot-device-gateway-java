package com.github.highcumontoa.iotdevicegatewayjava.sim;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandService;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import com.github.highcumontoa.iotdevicegatewayjava.model.Command;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConnectResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryPoint;
import com.github.highcumontoa.iotdevicegatewayjava.model.UpgradeRecord;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateService;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * 本地模拟设备：以“设备端”身份调用网关服务，模拟连接、断线、遥测、状态回报、
 * 升级进度回报。持有自己的 sessionId，用于制造接管/旧会话等并发场景。
 */
public class SimulatedDevice {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDevice.class);

    private final String deviceId;
    private final SessionManager sessions;
    private final TelemetryService telemetry;
    private final CommandService commands;
    private final StateService state;
    private final FirmwareService firmware;

    private String sessionId;
    private long seq = 0;

    public SimulatedDevice(String deviceId, SessionManager sessions, TelemetryService telemetry,
                           CommandService commands, StateService state,
                           FirmwareService firmware) {
        this.deviceId = deviceId;
        this.sessions = sessions;
        this.telemetry = telemetry;
        this.commands = commands;
        this.state = state;
        this.firmware = firmware;
    }

    public ConnectResult connect(String sessionId) {
        this.sessionId = sessionId;
        ConnectResult result = sessions.connect(deviceId, sessionId);
        log.info("[设备{}] 连接 session={} reused={} takenOver={}",
                deviceId, sessionId, result.reused(), result.takenOver());
        return result;
    }

    public void disconnect() {
        sessions.disconnect(deviceId, sessionId);
        log.info("[设备{}] 断开 session={}", deviceId, sessionId);
    }

    public void sendTelemetry(Instant ts, Map<String, Object> metrics) {
        long s = ++seq;
        log.info("[设备{}] 上报遥测 seq={} ts={} metrics={}", deviceId, s, ts, metrics);
        telemetry.ingest(deviceId, sessionId, new TelemetryPoint(deviceId, s, ts, metrics));
    }

    /** 允许测试直接指定 seq，构造重复/乱序场景。 */
    public void sendTelemetryRaw(long rawSeq, Instant ts, Map<String, Object> metrics) {
        telemetry.ingest(deviceId, sessionId, new TelemetryPoint(deviceId, rawSeq, ts, metrics));
    }

    public Command ack(String commandId) {
        return commands.ack(deviceId, sessionId, commandId);
    }

    public void reportState(Map<String, Object> reported) {
        state.reportState(deviceId, sessionId, reported, Map.of());
    }

    public void reportStateRejected(Map<String, String> rejected) {
        state.reportState(deviceId, sessionId, Map.of(), rejected);
    }

    public UpgradeRecord reportUpgrade(String upgradeId, String status, String detail) {
        return firmware.reportProgress(deviceId, sessionId, upgradeId, status, detail);
    }

    public String deviceId() {
        return deviceId;
    }

    public String sessionId() {
        return sessionId;
    }

    public long nextSeq() {
        return ++seq;
    }
}
