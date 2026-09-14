package com.dcdr.agent.repository;

import com.dcdr.agent.entity.MessageFeedbackEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * V3.1 回答反馈仓储 (F-48)。
 */
@Repository
public interface MessageFeedbackRepository extends JpaRepository<MessageFeedbackEntity, Long> {

    /** 定位某用户对某条回答的反馈 (重复提交时更新) */
    Optional<MessageFeedbackEntity> findBySessionIdAndMessageSeqAndUserId(String sessionId, int messageSeq, String userId);

    /** 一次取出某用户在某会话内的全部反馈, 用于回填各条回答的反馈状态 */
    List<MessageFeedbackEntity> findBySessionIdAndUserId(String sessionId, String userId);

    /** 会话删除时清理 */
    void deleteBySessionId(String sessionId);

    /** 会话历史截断 (编辑/重试) 时清理被删消息上的反馈, 避免残留脏数据 */
    void deleteBySessionIdAndMessageSeqGreaterThanEqual(String sessionId, int messageSeq);

    /** 统计窗口内的反馈 (全局) */
    List<MessageFeedbackEntity> findByCreatedAtAfter(LocalDateTime since);

    /** 统计窗口内的反馈 (仅某用户) */
    List<MessageFeedbackEntity> findByUserIdAndCreatedAtAfter(String userId, LocalDateTime since);

    /** 明细分页 (全局) */
    Page<MessageFeedbackEntity> findByRating(String rating, Pageable pageable);

    /** 明细分页 (仅某用户) */
    Page<MessageFeedbackEntity> findByUserId(String userId, Pageable pageable);

    /** 明细分页 (仅某用户 + 评价类型) */
    Page<MessageFeedbackEntity> findByUserIdAndRating(String userId, String rating, Pageable pageable);
}
