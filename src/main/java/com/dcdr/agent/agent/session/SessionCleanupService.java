package com.dcdr.agent.agent.session;

import com.dcdr.agent.agent.feedback.FeedbackService;
import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.repository.LlmCallLogRepository;
import com.dcdr.agent.repository.MessageRepository;
import com.dcdr.agent.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话级联删除 (V3.3)。
 * <p>
 * <b>为什么单独成一个 bean, 而不是留在 {@code SessionManager} 里</b>:
 * {@code SessionManager} 是 {@code @Component}, 内部方法互调<b>不经过 Spring 代理</b>,
 * {@code @Transactional} 形同注释。会话收敛 {@code trimAutoSessions} 正是这种调用 ——
 * 它按任务一次删 N 个会话, 每个会话又要删 sessions / messages / feedback 三张表,
 * 一旦中途失败就留下"会话没了、消息还在"的孤儿数据, 而下次清理再也不会碰到它们
 * (会话行已经不存在, 自然查不出来)。
 * <p>
 * 抽出来之后: 调用方跨 bean 调用 → 代理生效 → 一次删除的全部语句在<b>同一个事务</b>里,
 * 要么都成要么都不成。内存缓存 {@code ChatMemory} 的清理放在事务里是刻意的 ——
 * 它不在事务范围内, 但顺序必须与 DB 一致; 若 DB 回滚而内存已清, 后续读写会拿到空历史。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCleanupService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final FeedbackService feedbackService;
    private final LlmCallLogRepository llmCallLogRepository;
    private final ChatMemory memory;

    /**
     * 删除会话及其全部从属数据。
     * <p>
     * 调用链日志只<b>解除引用</b>不删行, 理由见
     * {@link LlmCallLogRepository#detachSession(String)}。
     */
    @Transactional
    public void deleteCascade(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        sessionRepository.deleteById(sessionId);
        messageRepository.deleteBySessionId(sessionId);
        feedbackService.deleteBySession(sessionId);
        int detached = llmCallLogRepository.detachSession(sessionId);
        memory.clear(sessionId);
        log.info("删除会话: {} (解除调用链引用 {} 条)", sessionId, detached);
    }
}
