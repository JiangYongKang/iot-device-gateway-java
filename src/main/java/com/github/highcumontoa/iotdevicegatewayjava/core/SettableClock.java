package com.github.highcumontoa.iotdevicegatewayjava.core;

/**
 * 测试用可控时钟，可手动推进，避免真实 sleep 造成用例不稳定。
 */
public class SettableClock implements Clock {
    private volatile long now;

    public SettableClock() {
        this(System.currentTimeMillis());
    }

    public SettableClock(long startMillis) {
        this.now = startMillis;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void advanceMillis(long deltaMillis) {
        if (deltaMillis < 0) {
            throw new IllegalArgumentException("delta must be >= 0");
        }
        this.now += deltaMillis;
    }

    public void setMillis(long millis) {
        this.now = millis;
    }
}
