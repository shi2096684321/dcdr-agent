package com.dcdr.agent.cluster;

import java.time.Duration;

/**
 * V3.1 F-46 跨实例租约锁。
 * <p>
 * 解决的唯一问题: <b>多实例下"只允许一个实例做某件事"</b>。典型是定时任务 ——
 * {@code @Scheduled} 与用户自定义 cron 任务都注册在<b>每个实例</b>上, 多实例部署会各跑一遍
 * (重复触发 Agent、产生重复会话与重复费用)。
 * <p>
 * 为什么是<b>租约</b>(lease)而不是普通互斥锁: 持锁实例可能崩溃且来不及释放。
 * 租约带 TTL, 过期后其他实例可自然接管, 无需人工干预。
 * <ul>
 *   <li>{@link LocalClusterLock}: 单实例等价实现, 恒返回 true(默认)</li>
 *   <li>{@link RedisClusterLock}: 集中式, 多实例必需</li>
 * </ul>
 * <p>
 * 语义约定(实现方必须遵守):
 * <ul>
 *   <li>{@code tryAcquire} 返回 true 表示"本实例获得执行权", 调用方必须配 try/finally 调
 *       {@code release}(提前交还, 否则要等 TTL 过期);</li>
 *   <li>返回 false 表示"本次跳过执行" —— 这不是错误, 调用方应静默返回, 不要记 error/warn;</li>
 *   <li>因中间件故障导致的失败按 <b>fail-open</b> 处理(返回 true), 见各实现说明。</li>
 * </ul>
 */
public interface ClusterLock {

    /**
     * 尝试获取名为 {@code name} 的执行权。
     *
     * @param name 锁名(全局唯一, 建议用 业务域:资源标识 的格式)
     * @param ttl  租约时长: 超过该时长未释放则视为持有者已失效, 其他实例可接管
     * @return true=获得执行权; false=其他实例正在执行, 本次应跳过
     */
    boolean tryAcquire(String name, Duration ttl);

    /** 释放执行权(仅当本实例是持有者时才真正释放) */
    void release(String name);

    /** 后端名称, 用于启动日志与排查 */
    String backend();
}
