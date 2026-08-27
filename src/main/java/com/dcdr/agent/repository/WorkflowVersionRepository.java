package com.dcdr.agent.repository;

import com.dcdr.agent.entity.WorkflowVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersionEntity, String> {

    /** 按工作流列出版本历史 (新版本在前) */
    List<WorkflowVersionEntity> findByWorkflowIdOrderByVersionDesc(String workflowId);

    /** 取指定版本 */
    Optional<WorkflowVersionEntity> findByWorkflowIdAndVersion(String workflowId, int version);

    /** 最大版本号 (无版本时返回空) */
    Optional<WorkflowVersionEntity> findFirstByWorkflowIdOrderByVersionDesc(String workflowId);
}
