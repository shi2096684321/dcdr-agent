package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V3.0-beta 模板市场分享记录实体 (F-35 模板市场, 文档 §9.4)。
 * <p>
 * 用户将自己的 Agent / 工作流分享到模板市场, 其他用户可搜索、克隆。
 * 评分采用累加式: rating_sum / rating_count = 平均分, 避免存储逐条评分。
 */
@Data
@Entity
@Schema(description = "V3.0-beta 模板市场分享记录 (F-35)")
@Table(name = "template_shares", uniqueConstraints = {
        @UniqueConstraint(name = "uk_tplshare_type_target", columnNames = {"type", "targetId"})
}, indexes = {
        @Index(name = "idx_tplshare_category", columnList = "category")
})
public class TemplateShareEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "分享者用户 ID")
    @Column(name = "user_id", length = 64)
    private String userId;

    @Schema(description = "类型: agent / workflow")
    @Column(nullable = false)
    private String type;

    @Schema(description = "目标 ID (Agent ID 或工作流 ID)")
    @Column(name = "target_id", length = 64, nullable = false)
    private String targetId;

    @Schema(description = "市场展示标题")
    private String title;

    @Schema(description = "市场展示描述")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "分类 (翻译/写作/编程/分析/客服等)")
    @Column(length = 64)
    private String category;

    @Schema(description = "标签 (逗号分隔)")
    @Column(columnDefinition = "TEXT")
    private String tags;

    @Schema(description = "克隆次数")
    @Column(name = "clone_count", nullable = false)
    private int cloneCount = 0;

    @Schema(description = "评分总和 (平均分 = ratingSum / ratingCount)")
    @Column(name = "rating_sum", nullable = false)
    private int ratingSum = 0;

    @Schema(description = "评分次数")
    @Column(name = "rating_count", nullable = false)
    private int ratingCount = 0;

    @Schema(description = "是否上架")
    @Column(nullable = false)
    private boolean enabled = true;

    @Schema(description = "分享时间")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
