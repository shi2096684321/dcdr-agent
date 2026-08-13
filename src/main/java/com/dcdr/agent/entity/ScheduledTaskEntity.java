package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V2.3 定时任务实体 (F-26 定时触发 Agent 执行任务)。
 * <p>
 * 由 {@code ScheduledTaskService} 在启动时加载全部 enabled 任务,
 * 用 Spring {@code TaskScheduler} 按 cron 表达式调度; 到点后调用关联 Agent
 * 异步执行 {@code inputMessage}, 记录运行结果与下次触发时间。
 */
@Data
@Entity
@Schema(description = "V2.3 定时任务实体 (F-26 定时触发 Agent)")
@Table(name = "scheduled_tasks", indexes = {
        @Index(name = "idx_sched_user", columnList = "userId"),
        @Index(name = "idx_sched_enabled", columnList = "enabled"),
        @Index(name = "idx_sched_next", columnList = "nextRunAt")
})
public class ScheduledTaskEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "任务名称")
    @Column(nullable = false)
    private String name;

    @Schema(description = "关联的 Agent ID")
    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Schema(description = "Cron 表达式 (Spring CronExpression 格式, 6 字段: 秒 分 时 日 月 周)")
    @Column(nullable = false)
    private String cron;

    @Schema(description = "触发时喂给 Agent 的输入消息")
    @Column(name = "input_message", columnDefinition = "TEXT")
    private String inputMessage;

    @Schema(description = "是否启用")
    @Column(nullable = false)
    private boolean enabled = true;

    @Schema(description = "上次运行时间")
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Schema(description = "上次运行状态: success / failed / running")
    @Column(name = "last_status", length = 16)
    private String lastStatus;

    @Schema(description = "上次运行错误信息")
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Schema(description = "下次运行时间")
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
