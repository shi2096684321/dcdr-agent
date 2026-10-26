package com.dcdr.agent.entity;

import com.dcdr.agent.workspace.WorkspaceAccess;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区实体(V3.3 F-49) —— 一个被用户显式授权给模型的目录 + 一组访问策略。
 * <p>
 * 实现 {@link OwnedEntity} 以复用 V3.1 F-41 的归属框架(insert 前自动填充 userId)。
 * <p>
 * <b>{@code rootPath} 存的是 {@code toRealPath()} 解析后的真实绝对路径</b>, 不是用户输入的原始字符串:
 * 创建时算一次, 后续每次访问只做一次比对, 不必反复归一化; 也避免用户输入
 * {@code D:/work/../work} 这类等价但字面不同的路径导致去重失效。
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "工作区实体")
@Table(name = "workspaces", indexes = {
        @Index(name = "idx_workspace_user", columnList = "userId")
})
public class WorkspaceEntity implements OwnedEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "工作区名称(展示用, 默认取目录名)")
    @Column(nullable = false, length = 128)
    private String name;

    @Schema(description = "解析后的真实绝对路径")
    @Column(nullable = false, length = 1024)
    private String rootPath;

    @Schema(description = "授权档位: READ / WRITE / FULL")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WorkspaceAccess accessLevel = WorkspaceAccess.READ;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
