package com.dcdr.agent.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.1 F-46 进程内限流计数单元测试。
 *
 * <p>这是默认后端({@code agent.v3.cluster.backend=memory}), 也是单实例部署下唯一走到的实现,
 * 故必须把三件事锁死:
 * <ol>
 *   <li><b>边界是闭区间</b>: limit=3 时必须放行 3 次、第 4 次拒绝。写成 {@code < limit} 就会
 *       少放行一次 —— 这类 off-by-one 在限流里不会被任何人发现(只是"少了一点配额"),
 *       但会让文档与配置对不上。</li>
 *   <li><b>窗口翻转后重新计数</b>: 否则限流会变成"一次性上限", 服务从此不可用。</li>
 *   <li><b>惰性清扫真的收敛内存</b>: V3.0 的内联实现只翻转计数、从不移除条目,
 *       海量不同 IP 即可把 Map 撑爆(内存泄漏面)。这是本次新增的能力, 必须有测试兜住。</li>
 * </ol>
 */
class InMemoryRateLimitStoreTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    @Test
    @DisplayName("边界为闭区间: limit=3 时恰好放行 3 次, 第 4 次拒绝")
    void allowsExactlyLimitTimes() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        assertTrue(store.tryAcquire("k", 3, WINDOW).block());
        assertTrue(store.tryAcquire("k", 3, WINDOW).block());
        assertTrue(store.tryAcquire("k", 3, WINDOW).block());
        assertFalse(store.tryAcquire("k", 3, WINDOW).block(), "第 4 次应被拒绝");
        assertFalse(store.tryAcquire("k", 3, WINDOW).block(), "持续超限仍应被拒绝");
    }

    @Test
    @DisplayName("不同 key 各自独立计数(用户维度与 IP 维度不互相消耗)")
    void keysAreIndependent() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        assertTrue(store.tryAcquire("user:u1", 1, WINDOW).block());
        assertFalse(store.tryAcquire("user:u1", 1, WINDOW).block());
        // u1 用光了, 不应影响 u2 或匿名 IP
        assertTrue(store.tryAcquire("user:u2", 1, WINDOW).block());
        assertTrue(store.tryAcquire("ip:10.0.0.1", 1, WINDOW).block());
    }

    @Test
    @DisplayName("窗口过期后计数重置, 不会变成一次性上限")
    void resetsAfterWindow() throws Exception {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        Duration tiny = Duration.ofMillis(120);
        assertTrue(store.tryAcquire("k", 1, tiny).block());
        assertFalse(store.tryAcquire("k", 1, tiny).block());
        Thread.sleep(200);
        assertTrue(store.tryAcquire("k", 1, tiny).block(), "新窗口应重新放行");
    }

    @Test
    @DisplayName("惰性清扫: 海量一次性 key 不会让跟踪表无限增长")
    void sweepsIdleKeys() throws Exception {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        Duration tiny = Duration.ofMillis(20);

        // 制造远多于清扫间隔(1024)的"一次性 key", 模拟攻击者不断更换 IP
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < 5000; i++) {
            if (Boolean.TRUE.equals(store.tryAcquire("ip:10.0." + (i / 256) + "." + (i % 256), 1, tiny).block())) {
                allowed.incrementAndGet();
            }
        }
        assertEquals(5000, allowed.get(), "每个新 key 首次调用都应放行");

        // 等所有条目都超过"闲置 3 × 窗口", 再触发下一次清扫
        Thread.sleep(80);
        for (int i = 0; i < 2048; i++) {
            store.tryAcquire("ip:192.168." + (i / 256) + "." + (i % 256), 1, tiny).block();
        }

        int tracked = store.trackedKeyCount();
        assertTrue(tracked < 3000,
                "闲置条目应已被清扫, 实际仍跟踪 " + tracked + " 个 key(接近 7048 说明清扫失效)");
    }

    @Test
    @DisplayName("并发下放行总数恰好等于 limit(窗口翻转的 CAS 不丢配额、也不多放)")
    void concurrentAcquireIsExact() throws Exception {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        int limit = 50;
        int threads = 16;
        int perThread = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int ok = 0;
                    for (int i = 0; i < perThread; i++) {
                        if (Boolean.TRUE.equals(store.tryAcquire("hot-key", limit, WINDOW).block())) ok++;
                    }
                    return ok;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> f : futures) total += f.get(30, TimeUnit.SECONDS);
            assertEquals(limit, total, "并发下必须恰好放行 limit 次(少了=丢配额, 多了=限流失效)");
        } finally {
            pool.shutdownNow();
        }
    }
}
