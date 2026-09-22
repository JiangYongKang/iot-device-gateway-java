package com.github.highcumontoa.iotdevicegatewayjava.core;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 按 key 分条的锁集合：同一设备始终映射到同一把锁，
 * 不同设备可并行；避免全局粗锁成为并发瓶颈，也避免每条数据一把锁导致内存膨胀。
 */
public final class StripedLocks {

    private final ReentrantLock[] stripes;

    public StripedLocks(int stripeCount) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive");
        }
        this.stripes = new ReentrantLock[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    public ReentrantLock forKey(String key) {
        int index = (key.hashCode() & Integer.MAX_VALUE) % stripes.length;
        return stripes[index];
    }
}
