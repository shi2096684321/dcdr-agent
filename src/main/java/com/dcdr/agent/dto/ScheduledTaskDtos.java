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
        @Schema(description = "执行完成后是否推送到通知渠道 (V3.3)")
        private Boolean notifyEnabled;
        @Schema(description = "通知渠道 code, 如 wecom_bot; 留空=使用全局默认")
        private String notifyChannel;
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

        // ===== V3.3 推送状态 =====
        @Schema(description = "执行完成后是否推送到通知渠道")
        private boolean notifyEnabled;
        @Schema(description = "通知渠道 code; 留空=使用全局默认")
        private String notifyChannel;
        @Schema(description = "最近一次推送状态: success / failed / skipped")
        private String notifyLastStatus;
        @Schema(description = "最近一次推送失败或跳过原因")
        private String notifyLastError;
        @Schema(description = "最近一次推送时间")
        private LocalDateTime notifyLastAt;
    }

    /** 手动触发一次执行 */
    @Data
    @Schema(description = "手动触发定时任务执行")
    public static class TriggerRequest {
        @Schema(description = "可选: 覆盖任务默认的输入消息")
        private String inputMessage;
    }

    /** 消息模板预览结果 (V3.3 F-50) */
    @Data
    @Schema(description = "消息模板预览（用示例正文渲染，未真正发送）")
    public static class NotifyPreview {
        private String channel;
        private String channelLabel;
        /** 成功消息渲染后的原始 markdown */
        private String successContent;
        /** 失败消息渲染后的原始 markdown */
        private String failureContent;
        /** 该渠道单条消息长度上限 */
        private int maxSize;
        /** 计长单位: BYTES / CHARS */
        private String unit;
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
