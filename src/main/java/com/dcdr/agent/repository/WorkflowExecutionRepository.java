package com.dcdr.agent.repository;

import com.dcdr.agent.entity.WorkflowExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecutionEntity, String> {

    /** 按用户列出执行记录 (最近优先) */
    List<WorkflowExecutionEntity> findByUserIdOrderByStartedAtDesc(String userId);

    /** 按工作流列出执行记录 */
    List<WorkflowExecutionEntity> findByWorkflowIdOrderByStartedAtDesc(String workflowId);
}
