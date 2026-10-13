package com.dcdr.agent.cluster;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * V3.1 F-46 限流计数存储抽象。
 * <p>
 * 抽象出存储的原因: 计数状态放"进程内"还是"集中式", 决定了多实例下的限额是否准确 ——
 * 进程内计数在 N 个实例下会把总限额放大到 N 倍。
 * <ul>
 *   <li>{@link InMemoryRateLimitStore}: 单实例等价实现, 默认</li>
 *   <li>{@link RedisRateLimitStore}: 集中式, 多实例必需</li>
 * </ul>
 * <p>
 * 返回 {@link Mono} 而非 boolean: 限流发生在 WebFlux 过滤器热路径上,
 * 集中式实现必须非阻塞(不能占住 reactor 事件循环线程)。
 */
public interface RateLimitStore {

    /**
     * 尝试消费一次配额。
     *
     * @param key    维度键(如 {@code user:u_1} / {@code ip:10.0.0.1})
     * @param limit  窗口内允许的请求数上限
     * @param window 窗口大小
     * @return true=放行, false=超限
     */
    Mono<Boolean> tryAcquire(String key, int limit, Duration window);

    /** 后端名称, 用于启动日志与排查 */
    String backend();
}
