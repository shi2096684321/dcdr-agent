package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.entity.ContextCompactionEntity;
import com.dcdr.agent.repository.ContextCompactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 上下文压缩事件的落库器(V3.5)。
 *
 * <p><b>两条硬约束</b>:
 * <ol>
 *   <li><b>不许影响对话</b>: 这段代码跑在每轮对话的准备阶段。记录只是可观测性,
 *       写失败必须降级为一条 warn, 绝不能让"日志没写进去"变成"消息发不出去"。</li>
 *   <li><b>独立事务</b>: 调用点(策略 / 摘要存储)常常自己带着事务。若共用,
 *       事件写入失败会把业务事务一起标记回滚; 反过来, 业务回滚也会抹掉"本来发生过"的记录。
 *       用 {@code REQUIRES_NEW} 手动开短事务, 两者互不牵连 —— 低频写入(每十几轮一次),
 *       多占一个连接没有代价。</li>
 * </ol>
 *
 * <p><b>为什么不直接 {@code @Transactional} + try/catch</b>: Hibernate 的写失败会把当前事务
 * 标记为 rollback-only, 捕获异常也拦不住代理在提交时抛 {@code UnexpectedRollbackException},
 * 结果是"吞了异常还是把对话搞挂了"。手动事务模板能把失败的边界圈在这一条记录上。
 */
@Slf4j
@Component
public class ContextCompactionRecorder {

    private final ContextCompactionRepository repository;
    private final TransactionTemplate tx;

    public ContextCompactionRecorder(ContextCompactionRepository repository,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 压缩成功: 记录压缩前后的规模, 用于回答"这一压省了多少" */
    public void compacted(String sessionId, String userId, int triggerTokens,
                          int beforeTokens, int afterTokens, int summarizedCount, int keptCount,
                          int summaryChars, int boundarySeq, String model, long durationMs) {
        ContextCompactionEntity e = base(sessionId, userId, CompactionEvent.COMPACTED, null);
        e.setTriggerTokens(triggerTokens);
        e.setBeforeTokens(beforeTokens);
        e.setAfterTokens(afterTokens);
        e.setSummarizedCount(summarizedCount);
        e.setKeptCount(keptCount);
        e.setSummaryChars(summaryChars);
        e.setBoundarySeq(boundarySeq);
        e.setModel(model);
        e.setDurationMs(durationMs);
        saveQuietly(e);
    }

    /**
     * 该压却没压成, 本轮退回滑动窗口。
     * <p>失败原因必须落下来: "摘要永远不生效"这类问题, 现场只有一句 warn 时几乎无从定位,
     * 而原因往往很具体(模型名填错 / 超时 / 返回为空 / 保留条数不足)。
     */
    public void degraded(String sessionId, String userId, String reason, int triggerTokens,
                         int beforeTokens, int summarizedCount, int keptCount) {
        ContextCompactionEntity e = base(sessionId, userId, CompactionEvent.DEGRADED, reason);
        e.setTriggerTokens(triggerTokens);
        e.setBeforeTokens(beforeTokens);
        e.setSummarizedCount(summarizedCount);
        e.setKeptCount(keptCount);
        saveQuietly(e);
    }

    /** 摘要边界消息已不存在, 整份摘要被作废 */
    public void invalidated(String sessionId, String userId, String reason) {
        saveQuietly(base(sessionId, userId, CompactionEvent.INVALIDATED, reason));
    }

    /** 用户主动清除摘要: 与"边界失效"分开记, 前者是用户意图, 后者是自愈 */
    public void cleared(String sessionId, String userId, int previousChars) {
        saveQuietly(base(sessionId, userId, CompactionEvent.CLEARED,
                previousChars > 0 ? "用户清除摘要(原 " + previousChars + " 字)" : "用户清除摘要"));
    }

    /** 用户手工改写摘要正文 */
    public void edited(String sessionId, String userId, int beforeChars, int afterChars) {
        saveQuietly(base(sessionId, userId, CompactionEvent.EDITED,
                "用户改写摘要: " + beforeChars + " 字 → " + afterChars + " 字"));
    }

    private ContextCompactionEntity base(String sessionId, String userId,
                                         CompactionEvent event, String reason) {
        ContextCompactionEntity e = new ContextCompactionEntity();
        e.setId(UUID.randomUUID().toString());
        e.setSessionId(sessionId);
        e.setUserId(userId);
        e.setEventType(event.code());
        e.setReason(reason);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private void saveQuietly(ContextCompactionEntity e) {
        try {
            tx.executeWithoutResult(status -> repository.save(e));
        } catch (Exception ex) {
            log.warn("上下文压缩事件落库失败(不影响对话): event={}, session={}, err={}",
                    e.getEventType(), e.getSessionId(), ex.toString());
        }
    }
}
