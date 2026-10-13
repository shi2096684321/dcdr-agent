package com.dcdr.agent.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V3.1 F-46 集中式限流计数(Redis 实现, 多实例必需)。
 * <p>
 * 实现方式: <b>固定窗口 + 窗口分桶</b>, 单次 Lua(INCR + 首次 PEXPIRE) 判定, 不做二次读回。
 * <pre>
 *   key = dcdr:ratelimit:{维度键}:{窗口序号}      ← 窗口序号 = now / 窗口大小
 *   INCR key; c==1 时 PEXPIRE key window          ← 原子: 桶首次写入设 TTL, 旧桶自动过期
 *   c &lt;= limit ? 放行 : 超限
 * </pre>
 * 为什么用分桶而不是"INCR 后读剩余 TTL 决定是否重置": 分桶把窗口边界编码进 key,
 * 多实例并发下不存在"两个实例同时重置 TTL"的竞争, 也省掉一次往返。
 * 代价是窗口为绝对时间边界(非滑动), 与原进程内实现的口径保持一致。
 * <p>
 * <b>故障降级</b>: Redis 不可用时不阻断业务 —— 回退进程内计数并 WARN(限频输出)。
 * 理由: 限流是保护性能力, "因 Redis 抖动拒绝全部请求"与"放行全部请求"都不可取;
 * 回退后多实例下退化为"每实例各算", 属可接受的有损降级, 且必然伴随告警日志。
 */
@Slf4j
public class RedisRateLimitStore implements RateLimitStore {

    /** Redis key 前缀, 便于运维识别与清理 */
    private static final String PREFIX = "dcdr:ratelimit:";
    /** 降级告警最小间隔(毫秒), 防 Redis 长期不可用刷爆日志 */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final ReactiveStringRedisTemplate redis;
    /** Redis 不可用时的降级实现 */
    private final RateLimitStore fallback;
    private final AtomicLong lastWarnAt = new AtomicLong(0L);

    /**
     * INCR 与"首次 PEXPIRE"合并为一次原子调用。
     * <p>
     * 为什么不用"INCR 后判断 c==1 再 EXPIRE"(review 时发现的原实现): 两步之间存在窗口 ——
     * 若 INCR 成功而 EXPIRE 因连接中断失败, 该桶 key 会<b>永远不过期</b>(没有 TTL 的计数桶),
     * 随使用时长无限堆积。Lua 让两者原子, 既消除这个泄漏点, 也省掉一次往返。
     */
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    public RedisRateLimitStore(ReactiveStringRedisTemplate redis, RateLimitStore fallback) {
        this.redis = redis;
        this.fallback = fallback;
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, int limit, Duration window) {
        long windowMs = Math.max(1L, window.toMillis());
        String bucketKey = PREFIX + key + ":" + (System.currentTimeMillis() / windowMs);

        return redis.execute(INCR_WITH_TTL, List.of(bucketKey), List.of(String.valueOf(windowMs)))
                .next()
                .map(count -> count != null && count <= limit)
                .defaultIfEmpty(true)
                .onErrorResume(e -> {
                    warnThrottled(e);
                    return fallback.tryAcquire(key, limit, window);
                });
    }

    private void warnThrottled(Throwable e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("集中式限流不可用, 已降级为进程内计数(多实例下限额将放宽): {}", e.getMessage());
        }
    }

    @Override
    public String backend() {
        return "redis";
    }
}
