package com.github.highcumontoa.iotdevicegatewayjava.core;

import org.springframework.stereotype.Component;

/**
 * 默认系统时钟实现。
 */
@Component
public class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
