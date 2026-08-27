package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * V3.0-beta LLM 调用 token 记录实体 (F-34 成本追踪 + F-39 配额管理, 文档 §8.3)。
 * <p>
 * 每次 LLM 调用记录 token 消耗, 供成本统计 (MonitorController) 与
 * 用户级配额检查 (QuotaChecker) 使用。
 */
@Data
@Entity
@Schema(description = "V3.0-beta LLM 调用 token 记录 (F-34 成本追踪 / F-39 配额)")
@Table(name = "llm_usage_logs", indexes = {
        @Index(name = "idx_llmusage_user", columnList = "userId"),
        @Index(name = "idx_llmusage_time", columnList = "createdAt"),
        @Index(name = "idx_llmusage_model", columnList = "model"),
        @Index(name = "idx_llmusage_userday", columnList = "userId, createdAt")
})
public class LlmUsageLogEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "用户 ID")
    @Column(name = "user_id", length = 64)
    private String userId;

    @Schema(description = "会话 ID")
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Schema(description = "关联的工作流执行 ID (工作流步骤调用时)")
    @Column(name = "workflow_execution_id", length = 64)
    private String workflowExecutionId;

    @Schema(description = "调用来源: chat / workflow / routing")
    @Column(nullable = false)
    private String source;

    @Schema(description = "模型名称")
    @Column(length = 128)
    private String model;

    @Schema(description = "输入 token 数")
    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Schema(description = "输出 token 数")
    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Schema(description = "总 token 数")
    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Schema(description = "成本金额 (可选, 按模型单价计算)")
    @Column(name = "cost_amount", precision = 10, scale = 6)
    private BigDecimal costAmount;

    @Schema(description = "创建时间")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
