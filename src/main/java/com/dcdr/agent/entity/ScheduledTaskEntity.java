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
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "V2.3 定时任务实体 (F-26 定时触发 Agent)")
@Table(name = "scheduled_tasks", indexes = {
        @Index(name = "idx_sched_user", columnList = "userId"),
        @Index(name = "idx_sched_enabled", columnList = "enabled"),
        @Index(name = "idx_sched_next", columnList = "nextRunAt")
})
public class ScheduledTaskEntity implements OwnedEntity {

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

    /**
     * 执行模型 (可选)。留空表示"跟随 Agent 配置或平台默认模型"。
     * <p>显式指定时优先级最高: 即使是 Agent 上配了别的模型也不覆盖, 见
     * {@code ChatRequest#modelLocked}。
     */
    @Schema(description = "执行模型名; 留空=跟随 Agent 配置/平台默认")
    @Column(name = "model", length = 128)
    private String model;

    @Schema(description = "Cron 表达式 (Spring CronExpression 格式, 6 字段: 秒 分 时 日 月 周)")
    @Column(nullable = false)
    private String cron;

    @Schema(description = "提示词: 每次触发时发给模型的输入")
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

    // ===== V3.3 执行结果推送 (agent.notify.*) =====

    /**
     * 执行完成后是否推送到通知渠道。
     * <p>注意这里<b>不加</b> {@code nullable=false}: ddl-auto=update 给存量表加非空列会失败,
     * 且 Java 侧默认 false 已经表达了语义, 非空约束交给 DDL 脚本声明。
     */
    @Schema(description = "执行结果是否推送到通知渠道")
    @Column(name = "notify_enabled")
    private boolean notifyEnabled = false;

    /** 任务级渠道覆盖 (如 wecom_bot); 留空使用全局默认 agent.notify.channel */
    @Schema(description = "通知渠道 code; 留空=使用全局默认")
    @Column(name = "notify_channel", length = 32)
    private String notifyChannel;

    /** 最近一次推送状态: success / failed / skipped */
    @Schema(description = "最近一次推送状态: success / failed / skipped")
    @Column(name = "notify_last_status", length = 16)
    private String notifyLastStatus;

    /** 最近一次推送的失败/跳过原因 */
    @Schema(description = "最近一次推送失败或跳过原因")
    @Column(name = "notify_last_error", columnDefinition = "TEXT")
    private String notifyLastError;

    /** 最近一次推送时间 */
    @Schema(description = "最近一次推送时间")
    @Column(name = "notify_last_at")
    private LocalDateTime notifyLastAt;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
