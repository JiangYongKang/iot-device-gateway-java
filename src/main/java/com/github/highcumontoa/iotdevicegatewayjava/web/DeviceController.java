package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.command.CommandService;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.Command;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConnectResult;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConvergenceView;
import com.github.highcumontoa.iotdevicegatewayjava.model.StateVersion;
import com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryPoint;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateService;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.AckRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.CommandRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.ConnectRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.DesiredRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.ReportedRequest;
import com.github.highcumontoa.iotdevicegatewayjava.web.dto.TelemetryRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 设备接入、遥测、指令、期望/上报状态接口。 */
@RestController
@RequestMapping("/api/devices/{deviceId}")
public class DeviceController {

    private final SessionManager sessions;
    private final TelemetryService telemetry;
    private final CommandService commands;
    private final StateService state;

    public DeviceController(SessionManager sessions, TelemetryService telemetry,
                            CommandService commands, StateService state) {
        this.sessions = sessions;
        this.telemetry = telemetry;
        this.commands = commands;
        this.state = state;
    }

    @PostMapping("/connect")
    public ConnectResult connect(@PathVariable String deviceId,
                                 @RequestBody ConnectRequest req) {
        if (req == null || req.sessionId() == null || req.sessionId().isBlank()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "sessionId 必填");
        }
        return sessions.connect(deviceId, req.sessionId());
    }

    @PostMapping("/disconnect")
    public String disconnect(@PathVariable String deviceId,
                             @RequestHeader("X-Session-Id") String sessionId) {
        sessions.disconnect(deviceId, sessionId);
        return "ok";
    }

    @PostMapping("/telemetry")
    public String telemetry(@PathVariable String deviceId,
                            @RequestHeader("X-Session-Id") String sessionId,
                            @RequestBody TelemetryRequest req) {
        telemetry.ingest(deviceId, sessionId,
                new TelemetryPoint(deviceId, req.seq(), req.ts(), req.metrics()));
        return "accepted";
    }

    @PostMapping("/commands")
    public Command enqueue(@PathVariable String deviceId,
                           @RequestBody CommandRequest req) {
        return commands.enqueue(deviceId, req.payload(), req.ttlSeconds());
    }

    @GetMapping("/commands")
    public List<Command> commands(@PathVariable String deviceId) {
        return commands.list(deviceId);
    }

    @PostMapping("/commands/ack")
    public Command ack(@PathVariable String deviceId, @RequestBody AckRequest req) {
        return commands.ack(deviceId, req.sessionId(), req.commandId());
    }

    @PostMapping("/state/desired")
    public StateVersion desired(@PathVariable String deviceId,
                                @RequestBody DesiredRequest req) {
        return state.updateDesired(deviceId, req.expectedVersion(), req.desired());
    }

    @PostMapping("/state/reported")
    public StateVersion reported(@PathVariable String deviceId,
                                 @RequestBody ReportedRequest req) {
        return state.reportState(deviceId, req.sessionId(), req.reported(), req.rejected());
    }

    @GetMapping("/state")
    public StateVersion snapshot(@PathVariable String deviceId) {
        return state.snapshot(deviceId);
    }

    @GetMapping("/state/convergence")
    public ConvergenceView convergence(@PathVariable String deviceId) {
        return state.convergence(deviceId);
    }
}
