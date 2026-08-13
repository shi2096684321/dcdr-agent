package com.dcdr.agent.repository;

import com.dcdr.agent.entity.AgentDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentDefinitionRepository extends JpaRepository<AgentDefinitionEntity, String> {

    /**
     * 列出当前用户可见的 Agent: 自己创建的 + 公开的 + 预设模板 (V2.3)。
     * 模板对所有用户可见, 公开 Agent 可被其他用户使用。
     */
    @Query("SELECT a FROM AgentDefinitionEntity a WHERE a.userId = :userId OR a.isPublic = true OR a.isTemplate = true ORDER BY a.isTemplate DESC, a.updatedAt DESC")
    List<AgentDefinitionEntity> findVisible(@Param("userId") String userId);

    /** 仅预设模板 (V2.3 模板库) */
    List<AgentDefinitionEntity> findByIsTemplateTrueOrderByCreatedAtDesc();

    /** 兼容旧调用: 用户自己的 + 公开 */
    @Query("SELECT a FROM AgentDefinitionEntity a WHERE a.userId = :userId OR a.isPublic = true ORDER BY a.updatedAt DESC")
    List<AgentDefinitionEntity> findByUserIdOrIsPublicTrueOrderByUpdatedAtDesc(@Param("userId") String userId);
}
