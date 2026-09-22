package com.github.highcumontoa.iotdevicegatewayjava.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandQueueService;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandRecord;
import com.github.highcumontoa.iotdevicegatewayjava.domain.CommandStatus;
import com.github.highcumontoa.iotdevicegatewayjava.domain.ReportedState;
import com.github.highcumontoa.iotdevicegatewayjava.domain.SessionInfo;
import com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryPoint;
import com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryResult;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.firmware.FirmwareService;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceGateway;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateService;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 本地模拟设备：用进程内确定性调用代替真实硬件。
 *
 * <p>它持有设备当前生效会话的引用，所有"设备侧"动作（收指令、回执、上报、刷写结果）
 * 都以该会话身份发起；因此可以真实地演示会话接管后旧会话操作被拒绝、指令不重复投递等结论。</p>
 */
@Service
public class DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);

    private final DeviceGateway gateway;
    private final SessionManager sessions;
    private final CommandQueueService commands;
    private final TelemetryService telemetry;
    private final StateService state;
    private final FirmwareService firmware;

    public DeviceSimulator(DeviceGateway gateway, SessionManager sessions, CommandQueueService commands,
                           TelemetryService telemetry, StateService state, FirmwareService firmware) {
        this.gateway = gateway;
        this.sessions = sessions;
        this.commands = commands;
        this.telemetry = telemetry;
        this.state = state;
        this.firmware = firmware;
    }

    /** 模拟设备以给定（或自动生成）会话 ID 接入。 */
    public String connect(String deviceId, String requestedSessionId) {
        SessionInfo info = gateway.connect(deviceId, requestedSessionId);
        log.info("[simulator] device={} connected session={} takeover={}",
                deviceId, info.sessionId(), info.takeover());
        return info.sessionId();
    }

    public void disconnect(String deviceId) {
        String sessionId = currentSession(deviceId);
        gateway.disconnect(deviceId, sessionId);
        log.info("[simulator] device={} disconnected session={}", deviceId, sessionId);
    }

    /** 模拟设备拉取自己已收到（DELIVERED）的指令视图。 */
    public List<DeliveredCommand> pendingCommands(String deviceId) {
        String sessionId = currentSession(deviceId);
        List<DeliveredCommand> out = new ArrayList<>();
        for (CommandRecord record : commands.listForDevice(deviceId)) {
            if (record.status() == CommandStatus.DELIVERED
                    && sessionId.equals(record.sessionId())) {
                out.add(new DeliveredCommand(record.commandId(), record.type(),
                        record.payload(), record.sessionId()));
            }
        }
        return out;
    }

    /** 模拟设备以当前会话身份对指令给出执行结果。 */
    public void acknowledgeCommand(String deviceId, String commandId, boolean success, String reason) {
        String sessionId = currentSession(deviceId);
        commands.acknowledge(commandId, sessionId, success, reason);
        log.info("[simulator] device={} ack command={} success={}", deviceId, commandId, success);
    }

    /** 模拟设备上报遥测。 */
    public TelemetryResult publishTelemetry(String deviceId, Long sequence, long timestamp,
                                            Map<String, Object> metrics) {
        return telemetry.ingest(new TelemetryPoint(deviceId, sequence, timestamp, metrics));
    }

    /** 模拟设备回报状态。 */
    public void reportState(String deviceId, Map<String, Object> reported,
                            java.util.Set<String> rejectedKeys, Map<String, String> reasons,
                            long reportedAt) {
        state.reportState(new ReportedState(deviceId, reported, rejectedKeys, reasons, reportedAt));
        log.info("[simulator] device={} state reported keys={}", deviceId, reported.keySet());
    }

    /** 模拟设备固件刷写结果（成功/失败），由固件服务按策略处理回滚。 */
    public void reportUpgradeResult(String upgradeId, boolean success, String reason) {
        firmware.completeUpgrade(upgradeId, success, reason);
        log.info("[simulator] upgrade={} reported success={} reason={}", upgradeId, success, reason);
    }

    /** 返回设备当前生效会话 ID；离线则按 DEVICE_OFFLINE 拒绝（原因可区分）。 */
    public String currentSession(String deviceId) {
        SessionInfo info = sessions.getActiveSession(deviceId);
        if (info == null) {
            throw notOnline(deviceId);
        }
        return info.sessionId();
    }

    public String newSessionId(String deviceId) {
        return "sim-" + deviceId + "-" + UUID.randomUUID();
    }

    /** 模拟设备侧已收到的指令视图。 */
    public record DeliveredCommand(String commandId, String type, String payload, String sessionId) {
    }

    private static GatewayException notOnline(String deviceId) {
        return GatewayException.of(ErrorCode.DEVICE_OFFLINE, "模拟设备当前不在线: " + deviceId);
    }
}
