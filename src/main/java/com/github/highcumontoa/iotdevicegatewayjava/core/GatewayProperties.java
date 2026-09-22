package com.github.highcumontoa.iotdevicegatewayjava.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关全部容量、配额与策略配置，均支持 application.properties 覆盖。
 * 前缀：iot.gateway
 */
@Component
@ConfigurationProperties(prefix = "iot.gateway")
public class GatewayProperties {

    private final Session session = new Session();
    private final Command command = new Command();
    private final Telemetry telemetry = new Telemetry();
    private final State state = new State();
    private final Firmware firmware = new Firmware();
    private final Rules rules = new Rules();
    private final Capacity capacity = new Capacity();

    public Session getSession() { return session; }
    public Command getCommand() { return command; }
    public Telemetry getTelemetry() { return telemetry; }
    public State getState() { return state; }
    public Firmware getFirmware() { return firmware; }
    public Rules getRules() { return rules; }
    public Capacity getCapacity() { return capacity; }

    /** 会话相关配置 */
    public static class Session {
        /** 全局同时在线会话上限 */
        private int maxConnections = 10_000;

        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
    }

    /** 离线指令缓冲配置 */
    public static class Command {
        /** 单设备离线队列长度上限，超出按拒绝处理 */
        private int queueCapacityPerDevice = 100;
        /** 待下发指令默认有效期（毫秒） */
        private long defaultTtlMillis = 5 * 60_000L;

        public int getQueueCapacityPerDevice() { return queueCapacityPerDevice; }
        public void setQueueCapacityPerDevice(int queueCapacityPerDevice) {
            this.queueCapacityPerDevice = queueCapacityPerDevice;
        }
        public long getDefaultTtlMillis() { return defaultTtlMillis; }
        public void setDefaultTtlMillis(long defaultTtlMillis) { this.defaultTtlMillis = defaultTtlMillis; }
    }

    /** 遥测配置 */
    public static class Telemetry {
        /**
         * 时钟回拨容忍窗口（毫秒）：小于该幅度的倒退视为乱序重复(STALE)，
         * 超过该窗口视为时钟回拨(CLOCK_ROLLBACK)。
         */
        private long clockRollbackToleranceMillis = 60_000L;
        /** 每设备保留的遥测历史条数（供查询/排障） */
        private int historyPerDevice = 200;

        public long getClockRollbackToleranceMillis() { return clockRollbackToleranceMillis; }
        public void setClockRollbackToleranceMillis(long v) { this.clockRollbackToleranceMillis = v; }
        public int getHistoryPerDevice() { return historyPerDevice; }
        public void setHistoryPerDevice(int historyPerDevice) { this.historyPerDevice = historyPerDevice; }
    }

    /** 期望状态配置 */
    public static class State {
        /** 单设备期望版本保留数量，超过后淘汰最旧版本（保留收敛结论可追溯） */
        private int retainedRevisionsPerDevice = 50;

        public int getRetainedRevisionsPerDevice() { return retainedRevisionsPerDevice; }
        public void setRetainedRevisionsPerDevice(int v) { this.retainedRevisionsPerDevice = v; }
    }

    /** 固件升级配置 */
    public static class Firmware {
        /** 升级进入进行中后多久未完成判定为设备失联（毫秒） */
        private long upgradeTimeoutMillis = 2 * 60_000L;
        /** 设备失联或失败时是否自动回滚到升级前版本 */
        private boolean rollbackOnFailure = true;

        public long getUpgradeTimeoutMillis() { return upgradeTimeoutMillis; }
        public void setUpgradeTimeoutMillis(long upgradeTimeoutMillis) {
            this.upgradeTimeoutMillis = upgradeTimeoutMillis;
        }
        public boolean isRollbackOnFailure() { return rollbackOnFailure; }
        public void setRollbackOnFailure(boolean rollbackOnFailure) {
            this.rollbackOnFailure = rollbackOnFailure;
        }
    }

    /** 规则引擎配置 */
    public static class Rules {
        /** 事件积压队列容量 */
        private int backlogCapacity = 2_000;
        /** 队列满时策略：BLOCK / DISCARD_NEWEST / DISCARD_OLDEST */
        private String backlogPolicy = "DISCARD_OLDEST";
        /** 处理线程数（单线程可保证严格顺序，默认 1） */
        private int workerThreads = 1;

        public int getBacklogCapacity() { return backlogCapacity; }
        public void setBacklogCapacity(int backlogCapacity) { this.backlogCapacity = backlogCapacity; }
        public String getBacklogPolicy() { return backlogPolicy; }
        public void setBacklogPolicy(String backlogPolicy) { this.backlogPolicy = backlogPolicy; }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    }

    /** 全局容量与配额 */
    public static class Capacity {
        /** 已登记设备总数上限 */
        private int maxRegisteredDevices = 100_000;

        public int getMaxRegisteredDevices() { return maxRegisteredDevices; }
        public void setMaxRegisteredDevices(int maxRegisteredDevices) {
            this.maxRegisteredDevices = maxRegisteredDevices;
        }
    }
}
