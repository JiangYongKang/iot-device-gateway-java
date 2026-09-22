package com.github.highcumontoa.iotdevicegatewayjava.core;

/**
 * 可替换时钟：生产环境使用系统时钟，测试使用可控时钟。
 */
public interface Clock {
    long nowMillis();
}
