package com.github.highcumontoa.iotdevicegatewayjava.web;

import com.github.highcumontoa.iotdevicegatewayjava.domain.ConvergenceView;
import com.github.highcumontoa.iotdevicegatewayjava.domain.DesiredRevision;
import com.github.highcumontoa.iotdevicegatewayjava.domain.ReportedState;
import com.github.highcumontoa.iotdevicegatewayjava.state.StateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 期望状态下发、设备回报与收敛查询接口。
 */
@RestController
@RequestMapping("/api/state/{deviceId}")
public class StateController {

    private final StateService stateService;

    public StateController(StateService stateService) {
        this.stateService = stateService;
    }

    public record DesiredRequest(Map<String, Object> desired, Long expectedRevision, String note) {}
    public record ReportRequest(Map<String, Object> reported, java.util.Set<String> rejectedKeys,
                                Map<String, String> rejectionReasons) {}

    @PostMapping("/desired")
    public DesiredRevision applyDesired(@PathVariable String deviceId, @RequestBody DesiredRequest req) {
        long expected = req.expectedRevision() == null ? 0L : req.expectedRevision();
        return stateService.applyDesired(deviceId, req.desired(), expected, req.note());
    }

    @PostMapping("/report")
    public ConvergenceView report(@PathVariable String deviceId, @RequestBody ReportRequest req) {
        return stateService.reportState(new ReportedState(deviceId, req.reported(),
                req.rejectedKeys() == null ? java.util.Set.of() : req.rejectedKeys(),
                req.rejectionReasons() == null ? Map.of() : req.rejectionReasons(), 0L));
    }

    @GetMapping("/convergence")
    public ConvergenceView convergence(@PathVariable String deviceId) {
        return stateService.getConvergence(deviceId);
    }
}
