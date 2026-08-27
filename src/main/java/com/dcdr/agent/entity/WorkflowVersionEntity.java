package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V3.0-beta 工作流版本历史实体 (F-34 治理与可观测性, 文档 §8.2)。
 * <p>
 * 工作流定义每次创建/修改生成新版本快照, 可回滚到任意历史版本。
 */
@Data
@Entity
@Schema(description = "V3.0-beta 工作流版本历史 (F-34)")
@Table(name = "workflow_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_wfver_workflow_version", columnNames = {"workflowId", "version"})
})
public class WorkflowVersionEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "工作流 ID")
    @Column(name = "workflow_id", length = 64, nullable = false)
    private String workflowId;

    @Schema(description = "版本号 (同一工作流内递增)")
    @Column(nullable = false)
    private int version;

    @Schema(description = "版本快照: 工作流名称")
    private String name;

    @Schema(description = "版本快照: 描述")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "版本快照: 编排模式")
    @Column(length = 32)
    private String mode;

    @Schema(description = "版本快照: 步骤定义 JSON")
    @Column(name = "steps_json", columnDefinition = "TEXT", nullable = false)
    private String stepsJson;

    @Schema(description = "版本快照: DAG 定义 JSON (F-32, 可空)")
    @Column(name = "dag_json", columnDefinition = "TEXT")
    private String dagJson;

    @Schema(description = "变更说明")
    @Column(length = 255)
    private String changeNote;

    @Schema(description = "创建人 (快照时的操作者)")
    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Schema(description = "创建时间")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
