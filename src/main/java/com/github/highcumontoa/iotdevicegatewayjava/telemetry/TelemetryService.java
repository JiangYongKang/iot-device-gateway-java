package com.github.highcumontoa.iotdevicegatewayjava.telemetry;

import com.github.highcumontoa.iotdevicegatewayjava.config.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.concurrency.DeviceLockProvider;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryPoint;
import com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryRejectReason;
import com.github.highcumontoa.iotdevicegatewayjava.model.TelemetryRejection;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 遥测写入一致性规则（全部在设备锁内“读-判-写”，一步提交，无半写入）：
 *  1. 去重：(deviceId, seq) 唯一。seq <= 最近接受序号：
 *     - seq 已被接受/已拒绝记录过 -> TELEMETRY_DUPLICATE；
 *  2. 乱序/时钟回拨：seq 更大但 ts 早于已接受的最大 ts -> TELEMETRY_STALE，
 *     绝不以旧覆盖新，也不重复计数；
 *  3. 字段校验：缺失必填 MISSING_FIELD、类型不符/未知字段 TYPE_MISMATCH、
 *     超范围 OUT_OF_RANGE；校验先于任何状态变更，拒绝不留残留写入。
 *  4. 容量：每设备历史有界；超限拒绝 CAPACITY_EXCEEDED，不产生部分写入。
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private record DeviceState(
            Instant lastAcceptedTs,
            java.util.Set<Long> seenSeqs,
            Deque<TelemetryPoint> history,
            Deque<TelemetryRejection> rejections
    ) {
    }

    private final Map<String, DeviceState> states = new ConcurrentHashMap<>();

    private final SessionManager sessions;
    private final DeviceLockProvider locks;
    private final MetricSchema schema;
    private final GatewayProperties properties;
    private final Clock clock;

    /** 遥测成功写入后的回调（如产生权威事件进入规则引擎），在设备锁内调用。 */
    private volatile java.util.function.Consumer<TelemetryPoint> afterAccept;

    public TelemetryService(SessionManager sessions, DeviceLockProvider locks,
                            MetricSchema schema, GatewayProperties properties, Clock clock) {
        this.sessions = sessions;
        this.locks = locks;
        this.schema = schema;
        this.properties = properties;
        this.clock = clock;
    }

    public void setAfterAccept(java.util.function.Consumer<TelemetryPoint> afterAccept) {
        this.afterAccept = afterAccept;
    }

    public void ingest(String deviceId, String sessionId, TelemetryPoint point) {
        sessions.requireValid(deviceId, sessionId);
        validateShape(deviceId, point);

        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            DeviceState st = states.computeIfAbsent(deviceId, k -> new DeviceState(
                    null, java.util.concurrent.ConcurrentHashMap.newKeySet(),
                    new ArrayDeque<>(), new ArrayDeque<>()));

            // 规则1：精确去重（重复上报）。乱序到达的较小“但未见过”的 seq 不按重复处理。
            if (!st.seenSeqs().add(point.seq())) {
                log.info("遥测重复上报被拒 deviceId={} seq={}", deviceId, point.seq());
                throw new GatewayException(ErrorCode.TELEMETRY_DUPLICATE,
                        "遥测序号重复 seq=" + point.seq());
            }

            // 规则2：时间戳乱序 / 时钟回拨（ts 早于任何已接受数据）。
            // 为保证“不以旧覆盖新”，回退数据不进入历史；回滚其去重占位以便语义清晰。
            if (st.lastAcceptedTs() != null && point.ts().isBefore(st.lastAcceptedTs())) {
                st.seenSeqs().remove(point.seq());
                log.info("遥测时间戳乱序/回拨被拒 deviceId={} seq={} ts={} lastTs={}",
                        deviceId, point.seq(), point.ts(), st.lastAcceptedTs());
                throw new GatewayException(ErrorCode.TELEMETRY_STALE,
                        "遥测时间戳早于已接受数据 ts=" + point.ts()
                                + " < " + st.lastAcceptedTs());
            }

            // 字段级校验在锁内复核（schema 可能动态注册），先校验后写入。
            String violation = validateMetrics(deviceId, point);
            if (violation != null) {
                // 校验失败的序号不进入“已接受”窗口：允许客户端修正后用同 seq 重发，
                // 重发的是不同内容，不构成重复上报；但拒绝记录被留存可追溯。
                st.seenSeqs().remove(point.seq());
                TelemetryRejectReason reason = reasonOf(violation);
                recordRejection(st, new TelemetryRejection(deviceId, point.seq(), point.ts(),
                        reason, violation, Instant.now(clock)));
                log.info("遥测字段校验拒绝 deviceId={} seq={} reason={} detail={}",
                        deviceId, point.seq(), reason, violation);
                throw new GatewayException(ErrorCode.TELEMETRY_VALIDATION_FAILED,
                        reason + ": " + violation);
            }

            // 规则4：容量上限，拒绝时不修改任何既有状态（含去重窗口）。
            if (st.history().size() >= properties.getMaxTelemetryPerDevice()) {
                st.seenSeqs().remove(point.seq());
                log.warn("遥测历史超限拒绝 deviceId={} seq={} max={}",
                        deviceId, point.seq(), properties.getMaxTelemetryPerDevice());
                throw new GatewayException(ErrorCode.CAPACITY_EXCEEDED,
                        "设备遥测历史超过上限 " + properties.getMaxTelemetryPerDevice());
            }

            st.history().addLast(point);
            trim(st.history(), properties.getMaxTelemetryPerDevice());
            // 去重窗口有界：保留最近窗口内的 seq，防止内存无限增长。
            trimSeenSeqs(st.seenSeqs());
            // 整体推进：时间戳游标随写入更新（序号集合即权威），不会半更新。
            DeviceState updated = new DeviceState(point.ts(), st.seenSeqs(),
                    st.history(), st.rejections());
            states.put(deviceId, updated);
            log.info("遥测写入成功 deviceId={} seq={} ts={} metrics={}",
                    deviceId, point.seq(), point.ts(), point.metrics());
            if (afterAccept != null) {
                // 锁内触发：拒绝的遥测绝不会产生事件，保证规则只基于权威数据。
                afterAccept.accept(point);
            }
        } finally {
            lock.unlock();
        }
    }

    private void validateShape(String deviceId, TelemetryPoint point) {
        if (point == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "遥测体不能为空");
        }
        if (point.seq() <= 0) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "seq 必须为正数");
        }
        if (point.ts() == null) {
            rejectAndThrow(deviceId, point, TelemetryRejectReason.MISSING_FIELD, "ts 缺失");
        }
        if (point.metrics() == null || point.metrics().isEmpty()) {
            rejectAndThrow(deviceId, point, TelemetryRejectReason.MISSING_FIELD, "metrics 为空");
        }
    }

    private void rejectAndThrow(String deviceId, TelemetryPoint point,
                                TelemetryRejectReason reason, String detail) {
        log.info("遥测格式拒绝 deviceId={} seq={} reason={} detail={}",
                deviceId, point == null ? null : point.seq(), reason, detail);
        throw new GatewayException(ErrorCode.TELEMETRY_VALIDATION_FAILED, reason + ": " + detail);
    }

    /** 返回 null 表示通过；否则返回“原因:细节”，前缀编码原因类别。 */
    private String validateMetrics(String deviceId, TelemetryPoint point) {
        Map<String, MetricSpec> specs = schema.specsFor(deviceId);
        if (specs.isEmpty()) {
            return "TYPE_MISMATCH:设备型号未注册指标 schema";
        }
        for (MetricSpec spec : specs.values()) {
            if (!point.metrics().containsKey(spec.field())) {
                if (spec.required()) {
                    return "MISSING_FIELD:缺少必填字段 " + spec.field();
                }
                continue;
            }
            Object value = point.metrics().get(spec.field());
            if (!(value instanceof Number n)) {
                return "TYPE_MISMATCH:字段 " + spec.field() + " 应为数值，实际="
                        + (value == null ? "null" : value.getClass().getSimpleName());
            }
            double d = n.doubleValue();
            if (Double.isNaN(d) || d < spec.min() || d > spec.max()) {
                return "OUT_OF_RANGE:字段 " + spec.field() + "=" + d
                        + " 超出允许范围 [" + spec.min() + "," + spec.max() + "]";
            }
        }
        for (String field : point.metrics().keySet()) {
            if (!specs.containsKey(field)) {
                return "TYPE_MISMATCH:未定义字段 " + field;
            }
        }
        return null;
    }

    private TelemetryRejectReason reasonOf(String encoded) {
        String prefix = encoded.substring(0, encoded.indexOf(':'));
        return TelemetryRejectReason.valueOf(prefix);
    }

    private void recordRejection(DeviceState st, TelemetryRejection rejection) {
        st.rejections().addLast(rejection);
        trim(st.rejections(), Math.max(50, properties.getMaxTelemetryPerDevice() / 4));
    }

    private <T> void trim(Deque<T> deque, int max) {
        while (deque.size() > max) {
            deque.removeFirst();
        }
    }

    /** 去重窗口：与遥测历史容量对齐，只保留最近出现过的序号集合。 */
    private void trimSeenSeqs(java.util.Set<Long> seen) {
        int window = properties.getMaxTelemetryPerDevice() + 16;
        if (seen.size() <= window) {
            return;
        }
        // seq 在设备内单调递增，移除最小的多余元素即可。
        Long min = seen.stream().min(Long::compareTo).orElse(null);
        if (min != null) {
            seen.remove(min);
        }
    }

    public List<TelemetryPoint> history(String deviceId) {
        DeviceState st = states.get(deviceId);
        if (st == null) {
            return List.of();
        }
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            return List.copyOf(new ArrayList<>(st.history()));
        } finally {
            lock.unlock();
        }
    }

    public List<TelemetryRejection> rejections(String deviceId) {
        DeviceState st = states.get(deviceId);
        if (st == null) {
            return List.of();
        }
        ReentrantLock lock = locks.forDevice(deviceId);
        lock.lock();
        try {
            return List.copyOf(new ArrayList<>(st.rejections()));
        } finally {
            lock.unlock();
        }
    }
}
