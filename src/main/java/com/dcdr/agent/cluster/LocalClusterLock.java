package com.dcdr.agent.cluster;

import java.time.Duration;

/**
 * V3.1 F-46 单实例租约锁(默认实现, 无集群协调)。
 * <p>
 * 恒返回 true —— 即"每个实例都可以执行", 与 V3.0 的单实例行为完全一致。
 * 存在的意义:
 * <ul>
 *   <li>保持单实例部署零配置、零行为变化(默认 {@code backend=memory});</li>
 *   <li>为定时任务代码提供统一的调用契约, 避免"单实例一套逻辑、多实例另一套"的分叉 ——
 *       切到多实例时只改配置, 代码不动。</li>
 * </ul>
 */
public class LocalClusterLock implements ClusterLock {

    @Override
    public boolean tryAcquire(String name, Duration ttl) {
        return true;
    }

    @Override
    public void release(String name) {
        // 无共享状态, 无需释放
    }

    @Override
    public String backend() {
        return "memory";
    }
}
