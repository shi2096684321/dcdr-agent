package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V2.3 定时任务相关 DTO (F-26 定时触发 Agent)。
 */
public class ScheduledTaskDtos {

    private ScheduledTaskDtos() {}

    /** 创建/更新定时任务请求 */
    @Data
    @Schema(description = "创建/更新定时任务请求")
    public static class SaveScheduledTaskRequest {
        @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;
        @Schema(description = "关联的 Agent ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private String agentId;
        @Schema(description = "Cron 表达式 (Spring 6 字段: 秒 分 时 日 月 周)", example = "0 0 9 * * *")
        private String cron;
        @Schema(description = "触发时喂给 Agent 的输入消息")
        private String inputMessage;
        @Schema(description = "是否启用")
        private Boolean enabled;
    }

    /** 定时任务视图 */
    @Data
    @Schema(description = "定时任务视图")
    public static class ScheduledTaskView {
        private String id;
        private String userId;
        private String name;
        private String agentId;
        /** 关联 Agent 名称 (前端展示用) */
        private String agentName;
        private String cron;
        private String inputMessage;
        private boolean enabled;
        private LocalDateTime lastRunAt;
        private String lastStatus;
        private String lastError;
        private LocalDateTime nextRunAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** 手动触发一次执行 */
    @Data
    @Schema(description = "手动触发定时任务执行")
    public static class TriggerRequest {
        @Schema(description = "可选: 覆盖任务默认的输入消息")
        private String inputMessage;
    }

    /** 触发结果 */
    @Data
    @Schema(description = "触发执行结果")
    public static class TriggerResult {
        @Schema(description = "Agent 返回的内容")
        private String output;
        @Schema(description = "状态: success / failed")
        private String status;
        @Schema(description = "失败原因")
        private String error;
    }
}
