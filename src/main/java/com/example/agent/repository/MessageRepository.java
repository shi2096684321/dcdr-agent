package com.example.agent.repository;

import com.example.agent.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findBySessionIdOrderBySeqAsc(String sessionId);

    Optional<MessageEntity> findTopBySessionIdOrderBySeqDesc(String sessionId);

    void deleteBySessionId(String sessionId);

    long countBySessionId(String sessionId);
}
