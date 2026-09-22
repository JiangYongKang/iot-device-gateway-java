package com.github.highcumontoa.iotdevicegatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可配置容量与配额上限（骨架，填充阶段补默认值与绑定）。 */
@ConfigurationProperties(prefix = "iot.gateway")
public class GatewayProperties {
    private int maxCommandsPerDevice = 100;
    private int maxTelemetryPerDevice = 1000;
    private int ruleQueueCapacity = 200;
    private int maxEventsPerDevice = 500;
    private long commandTtlSeconds = 300;
    private int maxDevices = 1000;

    public int getMaxCommandsPerDevice() { return maxCommandsPerDevice; }
    public void setMaxCommandsPerDevice(int v) { this.maxCommandsPerDevice = v; }
    public int getMaxTelemetryPerDevice() { return maxTelemetryPerDevice; }
    public void setMaxTelemetryPerDevice(int v) { this.maxTelemetryPerDevice = v; }
    public int getRuleQueueCapacity() { return ruleQueueCapacity; }
    public void setRuleQueueCapacity(int v) { this.ruleQueueCapacity = v; }
    public int getMaxEventsPerDevice() { return maxEventsPerDevice; }
    public void setMaxEventsPerDevice(int v) { this.maxEventsPerDevice = v; }
    public long getCommandTtlSeconds() { return commandTtlSeconds; }
    public void setCommandTtlSeconds(long v) { this.commandTtlSeconds = v; }
    public int getMaxDevices() { return maxDevices; }
    public void setMaxDevices(int v) { this.maxDevices = v; }
}
