package com.dcdr.agent.agent.memory.longterm;

import com.dcdr.agent.cluster.ClusterLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * V3.1 数据一致性兜底: 把已过期的活跃记忆归档。
 * <p>
 * 为什么需要它: 此前"过期"只发生在召回那一刻的过滤里({@code notExpired}), 条目本身永远是活跃 ——
 * 于是记忆列表只增不减: 用户看到的是一堆早就没用的时点性记忆, 而系统每轮召回都要把它们捞出来
 * 再丢掉。归档后它们退出候选池, 但仍可在"显示已删除/归档"里查看与恢复。
 * <p>
 * <b>V3.1 F-46 口径</b>: {@code @Scheduled} 注册在<b>每个实例</b>上, 多实例部署会各跑一遍。
 * 故与 {@code WorkflowExecutionSweeper} 一样用租约锁串行化 —— 默认 {@code backend=memory}
 * 时锁恒放行(单实例行为不变), 切 {@code redis} 后同一时刻只有一个实例扫描。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryExpirySweeper {

    /** 租约锁名(全局唯一, 前缀区分"定时类"锁) */
    private static final String LOCK_NAME = "scheduled:memory-expiry-sweeper";

    private final LongTermMemoryService memoryService;
    private final ClusterLock clusterLock;

    /** 租约 TTL(秒): 实例崩溃时靠它自动过期, 由下一个扫描周期接管 */
    @Value("${agent.v3.cluster.lock-ttl-seconds:300}")
    private long lockTtlSeconds;

    /** 扫描间隔(毫秒), 默认 1 小时 —— 过期归档不是"必须立刻生效"的事 */
    @Scheduled(fixedDelayString = "${agent.memory.expiry-sweep-interval-ms:3600000}",
            initialDelayString = "120000")
    public void archiveExpiredMemories() {
        if (!clusterLock.tryAcquire(LOCK_NAME, Duration.ofSeconds(Math.max(1L, lockTtlSeconds)))) {
            // 另一实例正在扫描, 本次跳过 —— 属正常调度竞争, 不是错误
            log.debug("过期记忆归档: 其他实例持有租约, 本次跳过");
            return;
        }
        try {
            memoryService.archiveExpired();
        } catch (Exception e) {
            // 归档失败不影响对话链路(召回侧本就还有一次 notExpired 过滤), 记日志即可
            log.error("过期记忆归档失败", e);
        } finally {
            clusterLock.release(LOCK_NAME);
        }
    }
}
