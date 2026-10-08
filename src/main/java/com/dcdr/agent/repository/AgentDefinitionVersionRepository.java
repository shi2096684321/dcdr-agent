package com.dcdr.agent.repository;

import com.dcdr.agent.entity.AgentDefinitionVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * V3.1 F-44.1 Agent 版本快照仓库 (对应 {@code WorkflowVersionRepository})。
 */
@Repository
public interface AgentDefinitionVersionRepository extends JpaRepository<AgentDefinitionVersionEntity, String> {

    /** 按 Agent 列出版本历史 (新版本在前) */
    List<AgentDefinitionVersionEntity> findByAgentIdOrderByVersionDesc(String agentId);

    /** 取指定版本 */
    Optional<AgentDefinitionVersionEntity> findByAgentIdAndVersion(String agentId, int version);

    /** 最大版本号 (无版本时返回空) */
    Optional<AgentDefinitionVersionEntity> findFirstByAgentIdOrderByVersionDesc(String agentId);
}
