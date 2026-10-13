package com.dcdr.agent.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V3.1 F-46 集中式租约锁(Redis 实现, 多实例必需)。
 * <p>
 * 加锁: {@code SET key <instanceId> NX PX <ttl>} —— 原子获取 + 自带过期, 是 Redis 做租约锁的标准写法。
 * <p>
 * 解锁: <b>Lua 比对再删</b>。为什么不能直接 {@code DEL}: 若本实例的租约已因超时被其他实例接管,
 * 直接 DEL 会误删"别人正在持有的锁", 导致同一时刻有两个实例在执行。
 * 用 Lua 保证 "GET 与 DEL" 的原子性, 只删自己持有的锁。
 * <p>
 * <b>故障策略: fail-open(返回 true)</b>。
 * 理由: 这个锁保护的是"定时任务别重复执行", 属于优化而非正确性红线。Redis 不可用时若 fail-closed,
 * 后果是<b>所有定时任务彻底停摆</b>(比偶尔重复执行严重得多); fail-open 的后果只是退化为
 * V3.0 的单实例语义(每实例各跑一次), 且必然打出告警日志。
 * 若将来出现"重复执行不可接受"的任务, 应在任务自身做幂等, 而不是把锁改成 fail-closed。
 * <p>
 * 线程模型: 本类的方法为<b>阻塞</b>实现({@code block}), 只允许在非 reactor 线程调用 ——
 * 当前全部调用点都在定时任务线程 / 启动期主线, 均满足。热路径(限流)走的是
 * {@link RateLimitStore} 的响应式接口, 不经过本类。
 */
@Slf4j
public class RedisClusterLock implements ClusterLock {

    private static final String PREFIX = "dcdr:lock:";
    /** 比对持有者后再删除(原子), 避免误删已被他人接管的租约 */
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final ReactiveStringRedisTemplate redis;
    private final String instanceId;
    private final AtomicLong lastWarnAt = new AtomicLong(0L);

    public RedisClusterLock(ReactiveStringRedisTemplate redis, String instanceId) {
        this.redis = redis;
        this.instanceId = instanceId;
    }

    @Override
    public boolean tryAcquire(String name, Duration ttl) {
        String key = PREFIX + name;
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(key, instanceId, ttl)
                    .block(ttl.plusSeconds(2));
            if (acquired == null) {
                // 防御(review 发现): setIfAbsent 理论上恒有应答, 但若出现空流, 上面的
                // Boolean.TRUE.equals(null) 会返回 false —— 即静默 fail-closed,
                // 与本类声明的"故障一律 fail-open"矛盾。故显式按故障处理。
                warnThrottled("获取租约未收到应答, 本次按 fail-open 放行", name,
                        new IllegalStateException("setIfAbsent completed empty"));
                return true;
            }
            return acquired;
        } catch (Exception e) {
            // block 超时会抛 IllegalStateException, 也走这里 → fail-open
            warnThrottled("获取租约失败, 本次按 fail-open 放行", name, e);
            return true;
        }
    }

    @Override
    public void release(String name) {
        String key = PREFIX + name;
        try {
            // 注意: 响应式 execute 的第三个参数是 List<?> 而非可变参数, 必须显式包一层
            Long deleted = redis.execute(RELEASE_SCRIPT, List.of(key), List.of(instanceId))
                    .blockFirst(Duration.ofSeconds(2));
            if (deleted != null && deleted > 0L) {
                log.debug("已释放租约: {} (holder={})", name, instanceId);
            }
        } catch (Exception e) {
            // 释放失败不阻断: 租约会在 TTL 后自动过期, 只是接管会晚一点
            warnThrottled("释放租约失败(等待 TTL 自动过期)", name, e);
        }
    }

    private void warnThrottled(String action, String name, Exception e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("集群租约 {}: lock={}, err={}", action, name, e.getMessage());
        }
    }

    @Override
    public String backend() {
        return "redis";
    }
}
