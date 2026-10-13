package com.dcdr.agent.cluster;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V3.1 F-46 进程内限流计数(单实例等价实现, 默认)。
 * <p>
 * 计数语义与 V3.0 的 {@code RateLimitWebFilter} 内联实现一致(固定窗口 + 窗口翻转),
 * 但修掉了原实现的两个真实缺陷:
 * <ol>
 *   <li><b>计数自增非原子</b>(原为 {@code volatile int count} + {@code ++}): 并发下会丢增量,
 *       于是"实际放行数 &gt; 配置限额"。限流失效不会报错、只会静默多放行,
 *       属最不该靠人眼发现的一类问题。改为 {@link AtomicInteger}。</li>
 *   <li><b>计数器永不移除</b>: 原实现只翻转计数、从不删条目, 攻击者换海量不同 IP
 *       即可让 Map 无限增长(内存泄漏面)。新增每 {@value #SWEEP_INTERVAL} 次调用一次的
 *       惰性清扫, 移除闲置超过 {@value #IDLE_MULTIPLIER} × 窗口的条目,
 *       把内存上界收敛到"窗口内活跃 key 数"。</li>
 * </ol>
 * <p>
 * <b>多实例下不准确</b>: 每个实例各算各的, 实际总限额 = 配置值 × 实例数。
 * 多实例部署须用 {@link RedisRateLimitStore}(见 {@code agent.v3.cluster.backend})。
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    /** 条目闲置超过该倍数 × 窗口即被清扫 */
    private static final long IDLE_MULTIPLIER = 3L;
    /** 每 N 次 acquire 触发一次惰性清扫(摊薄开销, 不额外起定时任务) */
    private static final int SWEEP_INTERVAL = 1024;

    private final Map<String, Window> counters = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();

    @Override
    public Mono<Boolean> tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMs = Math.max(1L, window.toMillis());

        Window w = counters.computeIfAbsent(key, k -> new Window(now));
        if (now - w.windowStart.get() >= windowMs) {
            synchronized (w) {
                if (now - w.windowStart.get() >= windowMs) {
                    w.windowStart.set(now);
                    w.count.set(0);
                }
            }
        }
        w.lastAccess.set(now);
        // 原子自增: 并发下的放行总数才能恰好等于 limit(非原子自增会因丢增量而多放行)
        boolean allowed = w.count.incrementAndGet() <= limit;

        if (sinceSweep.incrementAndGet() >= SWEEP_INTERVAL) {
            sinceSweep.set(0);
            sweep(now, windowMs);
        }
        return Mono.just(allowed);
    }

    /** 移除长期闲置条目(仅本实例内存结构, 无并发正确性要求) */
    private void sweep(long now, long windowMs) {
        long idleLimit = windowMs * IDLE_MULTIPLIER;
        counters.entrySet().removeIf(e -> now - e.getValue().lastAccess.get() > idleLimit);
    }

    /**
     * 当前跟踪的 key 数量。
     * <p>
     * 包级可见, 供测试验证"惰性清扫确实收敛了内存上界"(这是相对 V3.0 内联实现新增的能力,
     * 没有它就只能靠肉眼读代码); 也可用于运行期观测。
     */
    int trackedKeyCount() {
        return counters.size();
    }

    @Override
    public String backend() {
        return "memory";
    }

    private static final class Window {
        private final AtomicLong windowStart;
        private final AtomicLong lastAccess;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long now) {
            this.windowStart = new AtomicLong(now);
            this.lastAccess = new AtomicLong(now);
        }
    }
}
