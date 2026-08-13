package com.dcdr.agent.repository;

import com.dcdr.agent.entity.WorkflowDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, String> {

    /** 列出用户自己的工作流 (按更新时间倒序) */
    List<WorkflowDefinitionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /** 列出启用的工作流 (调度器/批量执行用) */
    List<WorkflowDefinitionEntity> findByEnabledTrue();
}
