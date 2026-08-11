package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成记录实体 (创作模式)
 */
@Data
@Entity
@Schema(description = "生成记录实体 (创作模式)")
@Table(name = "generations", indexes = {
        @Index(name = "idx_generations_project", columnList = "projectId")
})
public class GenerationEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属项目 ID")
    @Column(nullable = false, length = 64)
    private String projectId;

    @Schema(description = "类型: image / video")
    @Column(nullable = false, length = 16)
    private String type;

    @Schema(description = "生成提示词")
    @Column(length = 4000)
    private String prompt;

    @Schema(description = "生成尺寸 (如 1024x1024)")
    @Column(length = 32)
    private String size;

    @Schema(description = "参考图片 URL (图生图/视频生成场景)")
    @Column(length = 1024)
    private String referImageUrl;

    @Schema(description = "生成结果 URL")
    @Column(length = 1024)
    private String resultUrl;

    @Schema(description = "缩略图 URL")
    @Column(length = 1024)
    private String thumbnailUrl;

    @Schema(description = "使用的模型名称")
    @Column(length = 64)
    private String model;

    @Schema(description = "生成参数 JSON")
    @Column(length = 4000)
    private String paramsJson;

    @Schema(description = "本次生成消耗的积分")
    @Column(nullable = false)
    private int costCredits;

    @Schema(description = "状态: pending(排队中) / processing(渲染中) / completed / failed")
    @Column(nullable = false, length = 16)
    private String status;

    @Schema(description = "失败时的错误信息")
    @Column(length = 1000)
    private String errorMessage;

    @Schema(description = "生成耗时 (毫秒)")
    private long durationMs;

    @Schema(description = "后端真正开始生成(进入渲染线程)的时刻, 毫秒时间戳; 用于前端以真实生成起点计算耗时")
    private Long startedAt;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
