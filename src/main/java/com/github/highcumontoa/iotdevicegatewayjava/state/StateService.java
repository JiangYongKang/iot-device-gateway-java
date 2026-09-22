package com.github.highcumontoa.iotdevicegatewayjava.state;

import com.github.highcumontoa.iotdevicegatewayjava.concurrency.DeviceLockProvider;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConvergenceStatus;
import com.github.highcumontoa.iotdevicegatewayjava.model.ConvergenceView;
import com.github.highcumontoa.iotdevicegatewayjava.model.DesiredRejectReason;
import com.github.highcumontoa.iotdevicegatewayjava.model.StateVersion;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 期望状态 / 上报状态收敛：
 *  - updateDesired 采用乐观版本号 CAS（expectedVersion）：并发下发冲突时
 *    后到者收到 STATE_VERSION_CONFLICT，不发生丢失更新；调用方读取新版本后重试。
 *  - 期望字段必须通过 schema（可写/范围/已知），整批原子校验：任一字段非法则
 *    整批拒绝 DESIRED_REJECTED，不产生半更新、不留越权（只读）状态。
 *  - reportState 由当前有效会话上报；reportedVersion 单调推进。
 *    设备可对部分字段回报 rejected（原因），这些字段收敛结论为 REJECTED。
 *  - convergence 逐字段比较 desired/reported，给出 MATCHED/PENDING/REJECTED 与依据。
 */
@Service
public class StateService {

    private static final Logger log = LoggerFactory.getLogger(StateService.class);

    private record MutableState(
            long desiredVersion,
            long reportedVersion,
            Map<String, Object> desired,
            Map<String, Object> reported,
            // field -> 设备最近一次拒绝原因
            Map<String, String> rejectedByDevice
    ) {
    }

    private final Map<String, MutableState> states = new ConcurrentHashMap<>();

    private final SessionManager sessions;
    private final DeviceLockProvider locks;
    private final StateSchema schema;
    private final Clock clock;

    public StateService(SessionManager sessions, DeviceLockProvider locks,
                        StateSchema schema, Clock clock) {
        this.sessions = sessions;
        this.locks = locks;
        this.schema = schema;
        this.clock = clock;
    }

    public StateVersion updateDesired(String deviceId, long expectedVersion,
                                      Map<String, Object> desired) {
        if (desired == null || desired.isEmpty()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "desired 不能为空");
        }
        validateDesired(deviceId, desired);

        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            MutableState st = getOrInit(deviceId);
            if (st.desiredVersion() != expectedVersion) {
                log.info("期望版本冲突 deviceId={} expected={} actual={}",
                        deviceId, expectedVersion, st.desiredVersion());
                throw new GatewayException(ErrorCode.STATE_VERSION_CONFLICT,
                        "期望版本冲突 expected=" + expectedVersion
                                + " actual=" + st.desiredVersion());
            }
            // 整体合并并整体替换，避免半更新；新版本单调。
            Map<String, Object> merged = new LinkedHashMap<>(st.desired());
            merged.putAll(desired);
            MutableState updated = new MutableState(st.desiredVersion() + 1, st.reportedVersion(),
                    Map.copyOf(merged), st.reported(), new LinkedHashMap<>(st.rejectedByDevice()));
            // 新下发期望后，相关字段既往的设备拒绝结论清空（等待新一轮收敛）。
            for (String field : desired.keySet()) {
                updated.rejectedByDevice().remove(field);
            }
            states.put(deviceId, updated);
            log.info("期望状态更新 deviceId={} newVersion={} fields={}",
                    deviceId, updated.desiredVersion(), desired.keySet());
            return toView(deviceId, updated);
        } finally {
            lock.unlock();
        }
    }

    private void validateDesired(String deviceId, Map<String, Object> desired) {
        Map<String, StateFieldSpec> specs = schema.specsFor(deviceId);
        if (specs.isEmpty()) {
            throw new GatewayException(ErrorCode.DESIRED_REJECTED,
                    DesiredRejectReason.UNSUPPORTED_FIELD + ":设备型号未注册状态 schema");
        }
        // 先收集全部问题再拒绝：调用方可一次性看到全部不可下发字段，且不写入任何内容。
        Map<String, String> problems = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : desired.entrySet()) {
            StateFieldSpec spec = specs.get(e.getKey());
            if (spec == null) {
                problems.put(e.getKey(), DesiredRejectReason.UNSUPPORTED_FIELD
                        + ":未定义字段");
                continue;
            }
            if (!spec.writable()) {
                problems.put(e.getKey(), DesiredRejectReason.READ_ONLY + ":只读字段");
                continue;
            }
            if (!(e.getValue() instanceof Number n)) {
                problems.put(e.getKey(), DesiredRejectReason.VALUE_OUT_OF_RANGE
                        + ":期望值必须为数值");
                continue;
            }
            double d = n.doubleValue();
            if (Double.isNaN(d) || d < spec.min() || d > spec.max()) {
                problems.put(e.getKey(), DesiredRejectReason.VALUE_OUT_OF_RANGE
                        + ":" + d + " 超出 [" + spec.min() + "," + spec.max() + "]");
            }
        }
        if (!problems.isEmpty()) {
            log.info("期望状态整批拒绝 deviceId={} problems={}", deviceId, problems);
            throw new GatewayException(ErrorCode.DESIRED_REJECTED,
                    DesiredRejectReason.UNSUPPORTED_FIELD + " 等: " + problems);
        }
    }

    public StateVersion reportState(String deviceId, String sessionId,
                                    Map<String, Object> reported,
                                    Map<String, String> rejected) {
        sessions.requireValid(deviceId, sessionId);
        if (reported == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "reported 不能为空");
        }
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            MutableState st = getOrInit(deviceId);
            Map<String, Object> merged = new LinkedHashMap<>(st.reported());
            merged.putAll(reported);
            Map<String, String> rejectedMap = new LinkedHashMap<>(st.rejectedByDevice());
            if (rejected != null) {
                rejectedMap.putAll(rejected);
            }
            MutableState updated = new MutableState(st.desiredVersion(),
                    st.reportedVersion() + 1, st.desired(),
                    Map.copyOf(merged), Map.copyOf(rejectedMap));
            states.put(deviceId, updated);
            log.info("上报状态更新 deviceId={} reportedVersion={} fields={} rejected={}",
                    deviceId, updated.reportedVersion(), reported.keySet(),
                    rejected == null ? "{}" : rejected.keySet());
            return toView(deviceId, updated);
        } finally {
            lock.unlock();
        }
    }

    public ConvergenceView convergence(String deviceId) {
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            MutableState st = getOrInit(deviceId);
            Map<String, String> reasons = new LinkedHashMap<>();
            boolean anyPending = false;
            boolean anyRejected = false;
            for (Map.Entry<String, Object> e : st.desired().entrySet()) {
                String field = e.getKey();
                String deviceReject = st.rejectedByDevice().get(field);
                if (deviceReject != null) {
                    reasons.put(field, "REJECTED:" + deviceReject);
                    anyRejected = true;
                } else if (!st.reported().containsKey(field)) {
                    reasons.put(field, "PENDING:设备尚未回报该字段");
                    anyPending = true;
                } else if (!Objects.equals(e.getValue(), st.reported().get(field))) {
                    reasons.put(field, "PENDING:期望=" + e.getValue()
                            + " 上报=" + st.reported().get(field));
                    anyPending = true;
                } else {
                    reasons.put(field, "MATCHED:期望与上报一致 " + e.getValue());
                }
            }
            ConvergenceStatus overall = anyRejected ? ConvergenceStatus.REJECTED
                    : anyPending ? ConvergenceStatus.PENDING
                    : ConvergenceStatus.MATCHED;
            log.info("收敛查询 deviceId={} overall={} details={}", deviceId, overall, reasons);
            return new ConvergenceView(deviceId, overall, Map.copyOf(reasons));
        } finally {
            lock.unlock();
        }
    }

    public StateVersion snapshot(String deviceId) {
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            return toView(deviceId, getOrInit(deviceId));
        } finally {
            lock.unlock();
        }
    }

    private MutableState getOrInit(String deviceId) {
        return states.computeIfAbsent(deviceId, k -> new MutableState(
                0, 0, Map.of(), Map.of(), Map.of()));
    }

    private StateVersion toView(String deviceId, MutableState st) {
        return new StateVersion(deviceId, st.desiredVersion(), st.reportedVersion(),
                new HashMap<>(st.desired()), new HashMap<>(st.reported()), Instant.now(clock));
    }
}
