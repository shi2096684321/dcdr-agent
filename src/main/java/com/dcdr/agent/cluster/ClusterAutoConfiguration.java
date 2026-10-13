package com.dcdr.agent.cluster;

import com.dcdr.agent.config.V3Properties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.Locale;

/**
 * V3.1 F-46 集群协调装配。
 * <p>
 * 按 {@code agent.v3.cluster.backend} 选择实现:
 * <table border="1">
 *   <tr><th>backend</th><th>限流存储</th><th>租约锁</th><th>适用</th></tr>
 *   <tr><td>memory(默认)</td><td>进程内计数</td><td>恒放行</td><td>单实例(行为同 V3.0)</td></tr>
 *   <tr><td>redis</td><td>集中式 INCR</td><td>SET NX PX + Lua 解锁</td><td>多实例</td></tr>
 * </table>
 * <p>
 * 装配是<b>静态</b>的(启动时定), 运行期不切换; Redis 不可用时的降级在各实现内部处理
 * (见 {@link RedisRateLimitStore} / {@link RedisClusterLock})。
 * <p>
 * 配了 {@code backend=redis} 却没引 Redis 依赖时降级为 memory 实现并打 WARN —— 避免
 * "配置写了 redis 但依赖漏引"直接变成启动失败(那类问题更难排查)。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ClusterAutoConfiguration {

    private final V3Properties v3Properties;

    /** 实例标识(PostConstruct 解析, 保证全应用只有一个值) */
    private ClusterIdentity identity;

    /** 配置期望的后端名 */
    private String configured = "memory";

    /** 依赖是否就位 + 实际生效的共享状态能力 */
    private Boolean redisAvailable;

    @PostConstruct
    void init() {
        this.identity = ClusterIdentity.resolve(v3Properties.getCluster().getInstanceId());
        String b = v3Properties.getCluster().getBackend();
        this.configured = (b == null || b.isBlank()) ? "memory" : b.trim().toLowerCase(Locale.ROOT);
    }

    @Bean
    public ClusterIdentity clusterIdentity() {
        return identity;
    }

    @Bean
    public RateLimitStore rateLimitStore(ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        InMemoryRateLimitStore memory = new InMemoryRateLimitStore();
        if (!isRedisConfigured()) {
            redisAvailable = Boolean.FALSE;
            return memory;
        }
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        redisAvailable = redis != null;
        if (redis == null) {
            log.warn("agent.v3.cluster.backend=redis 但未找到 ReactiveStringRedisTemplate "
                    + "(是否漏引 spring-boot-starter-data-redis-reactive?), 已降级为进程内限流");
            return memory;
        }
        return new RedisRateLimitStore(redis, memory);
    }

    @Bean
    public ClusterLock clusterLock(ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
                                   ClusterIdentity clusterIdentity) {
        if (!isRedisConfigured()) {
            return new LocalClusterLock();
        }
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return new LocalClusterLock();
        }
        return new RedisClusterLock(redis, clusterIdentity.id());
    }

    /** 全部 Bean 就绪后输出最终生效状态 —— 启动日志里一眼可见"到底走没走集群协调" */
    @EventListener(ApplicationReadyEvent.class)
    void logSummary() {
        boolean cluster = isRedisConfigured() && Boolean.TRUE.equals(redisAvailable);
        log.info("V3.1 F-46 集群协调最终状态: instance={}, 期望backend={}, 实际={} | 限流={} | 定时任务互斥={}",
                identity.id(), configured, cluster ? "redis" : "memory",
                cluster ? "集中式计数" : "进程内计数(多实例下总限额会放大到 N 倍)",
                cluster ? "开启(租约锁)" : "关闭(每个实例各执行一次)");
    }

    private boolean isRedisConfigured() {
        return "redis".equals(configured);
    }
}
