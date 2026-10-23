package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 调用链日志实体。
 * <p>
 * 每一次对话请求同步为一条记录, 保存该次请求触发的一整条 LLM 调用链:
 * 路由判断 / 各生成轮(round-N) / 工具执行步骤(工具名与耗时) / token 累计 / 总耗时 / 结束原因。
 * 供前端"调用链日志"独立页面分页查看, 复盘一次回答背后的模型调用次数与耗时分布。
 * 与 {@code routing_logs}(仅路由单次判断)、{@code messages.trace_steps}(随消息、已弃用)正交。
 */
@Data
@Entity
@Schema(description = "LLM 调用链日志(一次请求一条)")
@Table(name = "llm_call_logs", indexes = {
        @Index(name = "idx_llmcall_user_time", columnList = "userId, createdAt")
})
public class LlmCallLogEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "本轮追踪短 ID(turnId)")
    @Column(length = 16)
    private String turnId;

    @Schema(description = "用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "会话 ID")
    @Column(length = 64)
    private String sessionId;

    @Schema(description = "用户消息(截取前 200 字符)")
    @Column(columnDefinition = "TEXT")
    private String userMessage;

    @Schema(description = "结束信号: ON_COMPLETE / ON_ERROR / ON_CANCEL")
    @Column(length = 32)
    private String endSignal;

    @Schema(description = "LLM 调用次数")
    @Column
    private Integer llmCalls = 0;

    @Schema(description = "工具执行步数")
    @Column
    private Integer toolSteps = 0;

    @Schema(description = "输入 token 累计")
    @Column
    private Long totalInTokens = 0L;

    @Schema(description = "输出 token 累计")
    @Column
    private Long totalOutTokens = 0L;

    @Schema(description = "总耗时(毫秒)")
    @Column
    private Long totalDurationMs = 0L;

    @Schema(description = "链路步骤名(如 routing → round-1 → tool[image_generation] → round-2)")
    @Column(columnDefinition = "TEXT")
    private String chain;

    @Schema(description = "步骤明细(JSON 数组: kind=llm/tool 及 purpose/model/tokens/costMs)")
    @Column(columnDefinition = "TEXT")
    private String stepsJson;

    @Schema(description = "失败所在的步骤(如「第 2 轮生成」「工具 web_search」); 正常结束为 null")
    @Column(length = 64)
    private String failedStep;

    @Schema(description = "首个错误信息; 正常结束为 null")
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}