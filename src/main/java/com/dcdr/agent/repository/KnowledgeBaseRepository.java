package com.dcdr.agent.repository;

import com.dcdr.agent.entity.KnowledgeBaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {

    List<KnowledgeBaseEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /** 查询用户级知识库(visibility=USER), 按更新时间倒序 */
    List<KnowledgeBaseEntity> findByVisibilityAndUserIdOrderByUpdatedAtDesc(String visibility, String userId);

    /** 查询平台级知识库(visibility=PLATFORM), 按更新时间倒序 */
    List<KnowledgeBaseEntity> findByVisibilityOrderByUpdatedAtDesc(String visibility);
}
