package com.dcdr.agent.cluster;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V3.1 F-46 集群协调集成测试(真实 Redis)。
 *
 * <p><b>为什么必须有这一层</b>: 单元测试用的是 {@link InMemoryRateLimitStore} /
 * {@link LocalClusterLock} —— 而这两个恰恰是"不解决多实例问题"的实现。真正要验证的两条核心能力
 * <b>只有连上真实 Redis 才验得了</b>:
 * <ul>
 *   <li><b>计数真的共享</b>: 两个 {@link RedisRateLimitStore} 实例(模拟 app-1 / app-2)
 *       交替调用, 放行总数必须恰好等于 limit —— 若各自计数就会变成 {@code limit × 2}
 *       (这正是多实例最典型的"限流静默失效")。</li>
 *   <li><b>租约真的互斥且能自愈</b>: 同一租约同一时刻只能被一个实例持有;
 *       持锁实例崩溃(不释放)后, 必须靠 TTL 过期让其他实例接管 —— 否则"租约"退化成"死锁"。</li>
 * </ul>
 * 另覆盖两条<b>故障降级</b>行为: Redis 不可用时, 锁必须 fail-open(否则定时任务彻底停摆),
 * 限流必须"既不阻塞也不全放行"。
 *
 * <p><b>运行方式</b>: 类名以 {@code IT} 结尾 → 由 failsafe 在 {@code mvn verify} 阶段执行,
 * 不拖慢 {@code mvn test} 的秒级离线体验:
 * <pre>mvn verify -Dit.test=RedisClusterCoordinationIT</pre>
 * 需本机(localhost:6379)有可用 Redis; 连不上则整类跳过(而非失败) —— 与
 * {@code RagVectorStoreIT} 的 {@code disabledWithoutDocker} 同一思路。
 * 可用 {@code -Dredis.host= / -Dredis.port=} 指向别处。
 *
 * <p><b>数据卫生</b>: 直接连本机开发 Redis, 故所有 key 都带 {@code it-<随机后缀>} 前缀且必带 TTL,
 * 不与业务 key({@code dcdr:ratelimit:user:*}) 冲突, 也不会残留。
 */
class RedisClusterCoordinationIT {

    private static final String HOST = System.getProperty("redis.host", "localhost");
    private static final int PORT = Integer.getInteger("redis.port", 6379);

    private static LettuceConnectionFactory factory;
    private static ReactiveStringRedisTemplate redis;
    /** 本次运行的唯一后缀: 避免与上次运行残留的 key 相互干扰 */
    private static String runId;

    @BeforeAll
    static void setUp() {
        factory = newConnectionFactory(HOST, PORT, Duration.ofSeconds(2));
        redis = new ReactiveStringRedisTemplate(factory);

        boolean reachable;
        try {
            Boolean ok = redis.opsForValue()
                    .set("dcdr:it:ping", "1", Duration.ofSeconds(5))
                    .block(Duration.ofSeconds(3));
            reachable = Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            reachable = false;
        }
        assumeTrue(reachable, "本机 " + HOST + ":" + PORT + " 无可用 Redis, 跳过集群协调集成测试");
        runId = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    // ==================== 限流: 计数共享 ====================

    @Test
    @DisplayName("两个节点共享同一计数: 放行总数恰好 = limit(而非 limit × 2)")
    void rateLimitCounterIsSharedAcrossNodes() {
        RedisRateLimitStore node1 = new RedisRateLimitStore(redis, new InMemoryRateLimitStore());
        RedisRateLimitStore node2 = new RedisRateLimitStore(redis, new InMemoryRateLimitStore());
        String key = "it-shared-" + runId;

        int limit = 5;
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            RedisRateLimitStore node = (i % 2 == 0) ? node1 : node2;
            if (Boolean.TRUE.equals(node.tryAcquire(key, limit, Duration.ofSeconds(60)).block())) {
                allowed++;
            }
        }
        assertEquals(limit, allowed,
                "两节点必须共享计数; 得到 " + allowed + " 说明每个节点各算各的(多实例下限额被放大)");
    }

    @Test
    @DisplayName("计数桶带 TTL: 窗口结束后自动消失, 不会无限堆积")
    void rateLimitBucketCarriesTtl() {
        RedisRateLimitStore node = new RedisRateLimitStore(redis, new InMemoryRateLimitStore());
        String key = "it-ttl-" + runId;
        assertEquals(Boolean.TRUE, node.tryAcquire(key, 10, Duration.ofSeconds(30)).block());

        List<String> buckets = redis.keys("dcdr:ratelimit:" + key + ":*").collectList().block();
        assertNotNull(buckets, "读取桶 key 失败");
        assertFalse(buckets.isEmpty(), "未找到计数桶 key(前缀: dcdr:ratelimit:" + key + ":*)");

        // 注意 API 细节: 响应式 getExpire(K) 返回 Mono<Duration>(不是 Mono<Long>);
        // 无过期时 Redis 返回 -1, Spring 原样映射为负 Duration —— 正好用来区分"有没有 TTL"
        Duration ttl = redis.getExpire(buckets.get(0)).block();
        assertNotNull(ttl);
        assertTrue(!ttl.isNegative() && !ttl.isZero(), "桶必须带 TTL, 实际 ttl=" + ttl);
        assertTrue(ttl.compareTo(Duration.ofSeconds(30)) <= 0,
                "桶 TTL 不应超过窗口长度, 实际 ttl=" + ttl);
    }

    // ==================== 租约锁: 互斥与自愈 ====================

    @Test
    @DisplayName("互斥: 同一租约同一时刻只能被一个实例持有")
    void lockIsMutuallyExclusive() {
        RedisClusterLock nodeA = new RedisClusterLock(redis, "it-a-" + runId);
        RedisClusterLock nodeB = new RedisClusterLock(redis, "it-b-" + runId);
        String name = "it-lock-" + runId;

        assertTrue(nodeA.tryAcquire(name, Duration.ofSeconds(30)), "A 首次获取应成功");
        assertFalse(nodeB.tryAcquire(name, Duration.ofSeconds(30)), "A 持有期间 B 必须拿不到");
        nodeA.release(name);
    }

    @Test
    @DisplayName("解锁只能由持有者进行: B 不能释放 A 的租约(否则会出现两个实例同时执行)")
    void releaseOnlyWorksForOwner() {
        RedisClusterLock nodeA = new RedisClusterLock(redis, "it-a-" + runId);
        RedisClusterLock nodeB = new RedisClusterLock(redis, "it-b-" + runId);
        String name = "it-lock-owner-" + runId;

        assertTrue(nodeA.tryAcquire(name, Duration.ofSeconds(30)));
        nodeB.release(name); // 越权释放, 必须无效
        assertFalse(nodeB.tryAcquire(name, Duration.ofSeconds(30)),
                "B 释放掉了 A 的租约 —— Lua 比对持有者的逻辑失效");

        nodeA.release(name);
        assertTrue(nodeB.tryAcquire(name, Duration.ofSeconds(30)), "A 正常释放后 B 应能接管");
    }

    @Test
    @DisplayName("租约到期自愈: 持有者崩溃不释放, 其他实例靠 TTL 过期接管")
    void leaseExpiresAndCanBeTakenOver() throws Exception {
        RedisClusterLock nodeA = new RedisClusterLock(redis, "it-a-" + runId);
        RedisClusterLock nodeB = new RedisClusterLock(redis, "it-b-" + runId);
        String name = "it-lock-ttl-" + runId;

        assertTrue(nodeA.tryAcquire(name, Duration.ofSeconds(1)));
        assertFalse(nodeB.tryAcquire(name, Duration.ofSeconds(30)), "租约未过期时 B 应拿不到");

        Thread.sleep(1500); // 模拟 A 进程崩溃, 永远不会调用 release

        assertTrue(nodeB.tryAcquire(name, Duration.ofSeconds(30)),
                "租约过期后 B 必须能接管 —— 否则租约退化成死锁, 定时任务从此不再执行");
        nodeB.release(name);
    }

    // ==================== 故障降级 ====================

    @Test
    @DisplayName("Redis 不可用时租约锁 fail-open: 放行且不长时间阻塞(否则定时任务彻底停摆)")
    void lockFailsOpenWhenRedisUnreachable() {
        LettuceConnectionFactory dead = newConnectionFactory("localhost", 6391, Duration.ofMillis(300));
        try {
            RedisClusterLock lock = new RedisClusterLock(new ReactiveStringRedisTemplate(dead), "it-dead");

            long start = System.currentTimeMillis();
            boolean acquired = lock.tryAcquire("it-unreachable-" + runId, Duration.ofSeconds(5));
            long cost = System.currentTimeMillis() - start;

            assertTrue(acquired, "Redis 不可用时应 fail-open(返回 true), 否则所有定时任务停摆");
            assertTrue(cost < 8000, "fail-open 不应长时间阻塞定时任务线程, 实际耗时 " + cost + "ms");
        } finally {
            dead.destroy();
        }
    }

    @Test
    @DisplayName("Redis 不可用时限流降级为进程内计数: 既不抛错也不全放行")
    void rateLimitDegradesToInMemoryWhenRedisUnreachable() {
        LettuceConnectionFactory dead = newConnectionFactory("localhost", 6391, Duration.ofMillis(300));
        try {
            RedisRateLimitStore store = new RedisRateLimitStore(
                    new ReactiveStringRedisTemplate(dead), new InMemoryRateLimitStore());
            String key = "it-degrade-" + runId;

            Boolean first = store.tryAcquire(key, 1, Duration.ofSeconds(60)).block(Duration.ofSeconds(5));
            Boolean second = store.tryAcquire(key, 1, Duration.ofSeconds(60)).block(Duration.ofSeconds(5));

            assertEquals(Boolean.TRUE, first, "降级后首次应放行(不能因 Redis 故障拒绝所有请求)");
            assertEquals(Boolean.FALSE, second, "降级后仍须限流(不能退化为全放行)");
        } finally {
            dead.destroy();
        }
    }

    // ==================== 工具 ====================

    private static LettuceConnectionFactory newConnectionFactory(String host, int port, Duration commandTimeout) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .shutdownTimeout(Duration.ofMillis(100))
                .build();
        LettuceConnectionFactory cf = new LettuceConnectionFactory(standalone, client);
        cf.afterPropertiesSet();
        return cf;
    }
}
