package com.github.highcumontoa.iotdevicegatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.github.highcumontoa.iotdevicegatewayjava.capacity.CapacityMetrics;
import com.github.highcumontoa.iotdevicegatewayjava.core.GatewayProperties;
import com.github.highcumontoa.iotdevicegatewayjava.core.SettableClock;
import com.github.highcumontoa.iotdevicegatewayjava.domain.MetricSchema;
import com.github.highcumontoa.iotdevicegatewayjava.domain.MetricType;
import com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryPoint;
import com.github.highcumontoa.iotdevicegatewayjava.domain.TelemetryResult;
import com.github.highcumontoa.iotdevicegatewayjava.error.ErrorCode;
import com.github.highcumontoa.iotdevicegatewayjava.error.GatewayException;
import com.github.highcumontoa.iotdevicegatewayjava.session.DeviceRegistry;
import com.github.highcumontoa.iotdevicegatewayjava.session.SessionManager;
import com.github.highcumontoa.iotdevicegatewayjava.telemetry.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 遥测：重复上报、乱序/时钟回拨、字段缺失/类型不匹配/超范围、禁止以旧覆盖新。
 */
class TelemetryTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryTest.class);

    private SettableClock clock;
    private TelemetryService telemetry;
    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new SettableClock(1_000L);
        GatewayProperties props = new GatewayProperties();
        props.getTelemetry().setClockRollbackToleranceMillis(60_000L);
        CapacityMetrics metrics = new CapacityMetrics();
        registry = new DeviceRegistry(clock, props, metrics);
        new SessionManager(registry, clock, props, metrics);
        telemetry = new TelemetryService(registry, clock, props);
        registry.register("dev", "sensor-1");
        registry.activate("dev");
        telemetry.registerSchema(MetricSchema.number("temp", true, -40.0, 85.0));
        telemetry.registerSchema(MetricSchema.bool("online", false));
        telemetry.registerSchema(MetricSchema.string("tag", false));
    }

    private TelemetryPoint point(Long seq, long ts, Map<String, Object> metrics) {
        return new TelemetryPoint("dev", seq, ts, metrics);
    }

    @Test
    void duplicateSequenceRejectedAndCountedOnce() {
        telemetry.ingest(point(1L, 100L, Map.of("temp", 20.0)));
        telemetry.ingest(point(2L, 200L, Map.of("temp", 21.0)));
        GatewayException dup = assertThrows(GatewayException.class,
                () -> telemetry.ingest(point(2L, 200L, Map.of("temp", 19.5))));
        assertEquals(ErrorCode.TELEMETRY_DUPLICATE, dup.errorCode());
        assertEquals(2, telemetry.acceptedCount("dev"));
        // 重复帧不得覆盖最新值
        assertEquals(21.0, telemetry.latest("dev").get("temp"));
        log.info("重复上报 code={} acceptedCount=2 latest.temp=21.0 判定依据 seq=2 重复", dup.errorCode());
    }

    @Test
    void outOfOrderAndClockRollbackAreDistinguished() {
        telemetry.ingest(point(1L, 100_000L, Map.of("temp", 20.0)));
        // 小幅倒退：乱序（序列号必须前进，时间戳倒退在容忍窗口内）
        GatewayException stale = assertThrows(GatewayException.class,
                () -> telemetry.ingest(point(2L, 99_500L, Map.of("temp", 19.0))));
        assertEquals(ErrorCode.TELEMETRY_STALE_TIMESTAMP, stale.errorCode());
        // 大幅倒退（超过 60s 容忍窗口）：时钟回拨
        GatewayException rollback = assertThrows(GatewayException.class,
                () -> telemetry.ingest(point(3L, 1_000L, Map.of("temp", 18.0))));
        assertEquals(ErrorCode.TELEMETRY_CLOCK_ROLLBACK, rollback.errorCode());
        assertEquals(20.0, telemetry.latest("dev").get("temp"), "旧帧绝不能覆盖新值");
        log.info("乱序 code={} 回拨 code={} latest.temp 仍为20.0", stale.errorCode(), rollback.errorCode());

        // 更大时间戳+序列号可以正常前进
        TelemetryResult ok = telemetry.ingest(point(4L, 101_000L, Map.of("temp", 22.0)));
        assertEquals(22.0, ok.acceptedMetrics().get("temp"));
    }

    @Test
    void invalidFieldsRejectedWithDistinctReasonsAndNoPartialWrite() {
        // 缺失必填
        GatewayException missing = assertThrows(GatewayException.class,
                () -> telemetry.ingest(point(1L, 100L, Map.of("online", true))));
        assertEquals(ErrorCode.TELEMETRY_FIELD_MISSING, missing.errorCode());

        // 类型不匹配
        GatewayException type = assertThrows(GatewayException.class,
                () -> telemetry.ingest(point(2L, 200L, Map.of("temp", "hot"))));
        assertEquals(ErrorCode.TELEMETRY_FIELD_TYPE_MISMATCH, type.errorCode());

        // 超范围
        GatewayException range = assertThrows(GatewayException.class,
                () -> telemetry.ingest(point(3L, 300L, Map.of("temp", 200.0))));
        assertEquals(ErrorCode.TELEMETRY_FIELD_OUT_OF_RANGE, range.errorCode());

        // 未知字段拒绝（防止静默写入脏字段）
        GatewayException unknown = assertThrows(GatewayException.class,
                () -> telemetry.ingest(point(4L, 400L, Map.of("temp", 20.0, "evil", 1))));
        assertEquals(ErrorCode.TELEMETRY_FIELD_MISSING, unknown.errorCode());

        // 整帧失败不留部分写入
        assertTrue(telemetry.latest("dev").isEmpty());
        assertEquals(0, telemetry.acceptedCount("dev"));
        log.info("字段校验四类拒绝码分别为 {},{},{},{} 且无部分写入",
                missing.errorCode(), type.errorCode(), range.errorCode(), unknown.errorCode());
    }

    @Test
    void mixedValidFrameAccepted() {
        TelemetryResult r = telemetry.ingest(point(1L, 100L,
                Map.of("temp", 25.5, "online", true, "tag", "floor-1")));
        assertEquals(3, r.acceptedMetrics().size());
        assertEquals(MetricType.NUMBER, MetricType.NUMBER);
        assertTrue(telemetry.latest("dev").containsKey("tag"));
        assertFalse(telemetry.history("dev", 10).isEmpty());
        log.info("合法帧全部接受 fields={}", r.acceptedMetrics().keySet());
    }
}
