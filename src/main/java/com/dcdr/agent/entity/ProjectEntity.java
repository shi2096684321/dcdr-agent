package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创作项目实体 (创作模式)
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "创作项目实体 (创作模式)")
@Table(name = "projects")
public class ProjectEntity implements OwnedEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属用户 ID (V3.1 F-41 补齐归属校验; 可空以兼容存量数据)")
    @Column(length = 64)
    private String userId;

    @Schema(description = "项目名称")
    @Column(nullable = false)
    private String name;

    @Schema(description = "子模式: image / video")
    @Column(nullable = false, length = 16)
    private String mode;

    @Schema(description = "默认使用的模型名称")
    @Column(length = 64)
    private String defaultModel;

    @Schema(description = "项目内生成记录数量")
    @Column(nullable = false)
    private int generationCount;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
