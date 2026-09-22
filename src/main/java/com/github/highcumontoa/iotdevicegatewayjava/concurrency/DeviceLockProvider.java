package com.github.highcumontoa.iotdevicegatewayjava.concurrency;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 按设备 id 分片的锁集合：所有“同一设备”上的会话/遥测/指令/状态/升级交叉操作
 * 都在同一把锁内完成读-判-写，保证结论确定、无半更新。
 * 锁数组固定大小，用 id 哈希取模，避免无限创建锁对象（资源不泄漏）。
 */
@Component
public class DeviceLockProvider {

    private static final int STRIPES = 64;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    public DeviceLockProvider() {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    public ReentrantLock forDevice(String deviceId) {
        int index = Math.floorMod(deviceId.hashCode(), STRIPES);
        return stripes[index];
    }
}
