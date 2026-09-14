package com.dcdr.agent.repository;

import com.dcdr.agent.entity.MessageEntity;
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
}
