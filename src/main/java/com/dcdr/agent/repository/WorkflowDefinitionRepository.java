package com.dcdr.agent.repository;

import com.dcdr.agent.entity.WorkflowDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, String> {

    /** 列出用户自己的工作流 (按更新时间倒序, 不含模板) */
    List<WorkflowDefinitionEntity> findByUserIdAndIsTemplateFalseOrderByUpdatedAtDesc(String userId);

    /** 列出启用的工作流 (调度器/批量执行用) */
    List<WorkflowDefinitionEntity> findByEnabledTrue();

    /** 列出全部预设模板 (按创建时间倒序) */
    List<WorkflowDefinitionEntity> findByIsTemplateTrueOrderByCreatedAtDesc();

    /**
     * 列出当前用户可见的工作流: 自己创建的 + 全部预设模板。
     * 用于前端列表展示 (用户能看到模板并能克隆)。
     */
    @Query("SELECT w FROM WorkflowDefinitionEntity w WHERE w.userId = :userId OR w.isTemplate = true ORDER BY w.updatedAt DESC")
    List<WorkflowDefinitionEntity> findVisible(String userId);

    /** V3.0-rc F-36: 按用户集合统计非模板工作流数 (租户配额用) */
    @Query("SELECT COUNT(w) FROM WorkflowDefinitionEntity w WHERE w.userId IN :userIds AND w.isTemplate = false")
    long countByUserIds(@Param("userIds") List<String> userIds);
}
