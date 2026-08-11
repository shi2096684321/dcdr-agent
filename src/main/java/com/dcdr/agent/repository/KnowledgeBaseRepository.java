package com.dcdr.agent.repository;

import com.dcdr.agent.entity.KnowledgeBaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {

    List<KnowledgeBaseEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
}
