package com.dcdr.agent.repository;

import com.dcdr.agent.entity.MessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findBySessionIdOrderBySeqAsc(String sessionId);

    Optional<MessageEntity> findTopBySessionIdOrderBySeqDesc(String sessionId);

    /** V3.1 F-48: 按会话内序号精确定位消息(反馈提交时需要读取被评价回答的模型/工具等上下文) */
    Optional<MessageEntity> findBySessionIdAndSeq(String sessionId, int seq);

    void deleteBySessionId(String sessionId);

    long countBySessionId(String sessionId);

    /**
     * 会话内指定角色的消息条数。
     * <p>
     * 用户消息数即「已发生的对话轮次」, 供自动抽取做「每 N 轮」门控。
     * <b>刻意按角色而不是按总数</b>: 助手消息是<b>异步</b>落库的(见 AgentOrchestrator 收尾段),
     * 按总数判定会与这次异步写竞争 —— 明明已经到第 4 轮, 读到的却还是落库前的计数,
     * 于是整段对话都可能错过触发点。而用户消息在阻塞的准备阶段同步落库, 计数是精确的。
     */
    long countBySessionIdAndRole(String sessionId, String role);

    /** 会话最近 N 条消息(按 seq 倒序); 供自动抽取回看"自上次抽取以来的几轮对话" */
    List<MessageEntity> findBySessionIdOrderBySeqDesc(String sessionId, Pageable pageable);
}
