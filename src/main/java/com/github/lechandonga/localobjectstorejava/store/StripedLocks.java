package com.github.lechandonga.localobjectstorejava.store;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 key 哈希的分段锁：同一 key 的写操作互斥，不同 key 之间尽量并行。
 * 锁只在单个 JVM 内生效；跨进程的一致性由原子文件操作保证。
 */
final class StripedLocks {

    private final ReentrantLock[] stripes;

    StripedLocks(int stripeCount) {
        this.stripes = new ReentrantLock[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    ReentrantLock lockFor(String key) {
        int index = Math.floorMod(key.hashCode(), stripes.length);
        return stripes[index];
    }
}
