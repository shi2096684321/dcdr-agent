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

    /**
     * 续租 (V3.3): 把已持有租约的过期时间重新推后 {@code ttl}。
     * <p>
     * <b>为什么必须有</b>: 定时任务的实际执行时长不受控 —— 一次带工具循环的抓取任务实测跑到
     * 405s, 而租约 TTL 只有 300s。锁中途过期后, 其他实例会认为"持有者已死"并接管, 于是同一任务
     * 被并行执行两遍(重复会话、重复模型费用、重复推送)。TTL 调多大都不解决问题, 只有续租能。
     * <p>
     * 实现约定: <b>只在确实持有该锁时</b>才延长(按持有者标识比对, 与释放同一套判断);
     * 已失去锁时返回 false —— 调用方据此告警, 但不要抛出。
     * <p>
     * 默认实现返回 true(单实例无共享状态, 续不续都一样), 仅集群实现需要覆盖。
     *
     * @return true=续租成功(或无需续租); false=本实例已不再持有该租约
     */
    default boolean renew(String name, Duration ttl) {
        return true;
    }

    /** 释放执行权(仅当本实例是持有者时才真正释放) */
    void release(String name);

    /** 后端名称, 用于启动日志与排查 */
    String backend();
}
