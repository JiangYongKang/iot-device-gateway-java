package com.github.highcumontoa.iotdevicegatewayjava.command;

/**
 * 指令投递下沉：真实系统里是网络写出，本地模拟由模拟器实现。
 * 抛出异常表示投递失败（例如模拟的断链），由 CommandService 标记 FAILED。
 */
@FunctionalInterface
public interface CommandDeliverySink {
    void deliver(String deviceId, String sessionId, String commandId, String payload) throws Exception;
}
