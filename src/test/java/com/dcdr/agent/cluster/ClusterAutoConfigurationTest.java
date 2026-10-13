package com.dcdr.agent.cluster;

import com.dcdr.agent.config.V3Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V3.1 F-46 集群协调装配单元测试。
 *
 * <p>锁住的是一条<b>失败模式</b>: 用户把 {@code agent.v3.cluster.backend} 改成 {@code redis},
 * 但环境里没有 Redis 依赖(或 Bean 缺失)。此时有两条路:
 * <ul>
 *   <li>让它启动失败 —— 问题变成"应用起不来", 排查成本高(配置看起来"只是改了个值");</li>
 *   <li>降级为内存实现 + WARN —— 应用照常可用, 日志里明说"多实例限额会放大"。</li>
 * </ul>
 * 本项目选后者, 本测试就是这条约定的防线。
 *
 * <p>同时锁定"默认零行为变化"这条兼容性承诺: {@code backend=memory}(或未配置)时
 * 必须装配成 {@link InMemoryRateLimitStore} / {@link LocalClusterLock},
 * 即与 V3.0 的单实例语义逐字一致。
 *
 * <p>注意: 这里只是<b>构造</b> {@code ReactiveStringRedisTemplate}, 不会建立连接
 * (Lettuce 连接工厂是惰性的), 故本测试无外部依赖、可归 {@code mvn test}。
 */
class ClusterAutoConfigurationTest {

    @Test
    @DisplayName("默认(memory): 装配进程内实现, 行为与 V3.0 一致")
    void defaultBackendIsInMemory() {
        ClusterAutoConfiguration config = newConfig("memory", null);
        ObjectProvider<ReactiveStringRedisTemplate> noRedis = provider(null);

        assertInstanceOf(InMemoryRateLimitStore.class, config.rateLimitStore(noRedis));
        assertInstanceOf(LocalClusterLock.class, config.clusterLock(noRedis, config.clusterIdentity()));
        assertEquals("memory", config.clusterLock(noRedis, config.clusterIdentity()).backend());
    }

    @Test
    @DisplayName("未配置 backend 时等同于 memory(不因缺配置而误开 redis 分支)")
    void blankBackendFallsBackToMemory() {
        ClusterAutoConfiguration config = newConfig("  ", null);
        ObjectProvider<ReactiveStringRedisTemplate> noRedis = provider(null);
        assertInstanceOf(InMemoryRateLimitStore.class, config.rateLimitStore(noRedis));
    }

    @Test
    @DisplayName("backend=redis 但 Redis 依赖缺失: 降级为进程内实现, 不抛异常")
    void redisWithoutTemplateDegradesGracefully() {
        ClusterAutoConfiguration config = newConfig("redis", null);
        ObjectProvider<ReactiveStringRedisTemplate> noRedis = provider(null);

        // 关键: 这里不能抛 NPE / IllegalStateException, 否则"改一个配置值"会变成"应用起不来"
        assertInstanceOf(InMemoryRateLimitStore.class, config.rateLimitStore(noRedis));
        assertInstanceOf(LocalClusterLock.class, config.clusterLock(noRedis, config.clusterIdentity()));
    }

    @Test
    @DisplayName("backend=redis 且依赖就位: 装配集中式实现(含大小写/空白容错)")
    void redisBackendIsWired() {
        ClusterAutoConfiguration config = newConfig("  REDIS ", null);
        ObjectProvider<ReactiveStringRedisTemplate> withRedis = provider(newReactiveTemplate());

        RateLimitStore store = config.rateLimitStore(withRedis);
        ClusterLock lock = config.clusterLock(withRedis, config.clusterIdentity());

        assertInstanceOf(RedisRateLimitStore.class, store);
        assertInstanceOf(RedisClusterLock.class, lock);
        assertEquals("redis", store.backend());
        assertEquals("redis", lock.backend());
    }

    @Test
    @DisplayName("instance-id 配置生效: 用于区分租约持有者与日志")
    void configuredInstanceIdWins() {
        ClusterAutoConfiguration config = newConfig("redis", "app-2");
        assertEquals("app-2", config.clusterIdentity().id());
    }

    @Test
    @DisplayName("instance-id 留空则自动生成(非空, 且带主机名前缀)")
    void blankInstanceIdIsGenerated() {
        ClusterAutoConfiguration config = newConfig("memory", "   ");
        String id = config.clusterIdentity().id();
        assertTrue(id != null && !id.isBlank(), "实例标识不能为空 —— 租约锁要靠它区分持有者");
    }

    // ==================== 工具 ====================

    private static ClusterAutoConfiguration newConfig(String backend, String instanceId) {
        V3Properties props = new V3Properties();
        props.getCluster().setBackend(backend);
        if (instanceId != null) {
            props.getCluster().setInstanceId(instanceId);
        }
        ClusterAutoConfiguration config = new ClusterAutoConfiguration(props);
        config.init(); // @PostConstruct 的等价调用(包级可见)
        return config;
    }

    /**
     * 用 Mockito 造一个只回答 {@code getIfAvailable()} 的 ObjectProvider。
     * <p>
     * 手写匿名实现要覆盖 {@code getObject()} / {@code getObject(Object...)} /
     * {@code getIfAvailable()} / {@code getIfUnique()} 四个抽象方法, 且后续 Spring 版本新增抽象方法
     * 就会编译失败 —— 用 mock 既短又不会随 API 演进失效。
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveStringRedisTemplate> provider(ReactiveStringRedisTemplate template) {
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    /** 构造模板不会建立连接(连接工厂惰性), 故本测试无需真实 Redis */
    private static ReactiveStringRedisTemplate newReactiveTemplate() {
        LettuceConnectionFactory cf = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 6379));
        cf.afterPropertiesSet();
        return new ReactiveStringRedisTemplate(cf);
    }
}
