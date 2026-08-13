package com.dcdr.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * V2.3 自定义 Agent 相关 DTO (F-24)。
 */
public class AgentDtos {

    private AgentDtos() {}

    /** 创建/更新 Agent 请求 */
    @Data
    @Schema(description = "创建/更新自定义 Agent 请求")
    public static class SaveAgentRequest {
        @Schema(description = "Agent 名称", requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;
        @Schema(description = "描述")
        private String description;
        @Schema(description = "头像 URL")
        private String avatar;
        @Schema(description = "系统提示词 (定义 Agent 行为)")
        private String systemPrompt;
        @Schema(description = "使用的模型名称 (留空则用系统默认)")
        private String model;
        @Schema(description = "启用的工具名称列表 (留空表示不启用工具)")
        private List<String> tools;
        @Schema(description = "关联的知识库 ID 列表")
        private List<String> kbIds;
        @Schema(description = "温度参数 0~2")
        private BigDecimal temperature;
        @Schema(description = "是否公开 (公开可被其他用户使用)")
        @JsonProperty("isPublic")
        private Boolean isPublic;
    }

    /** Agent 列表项 / 详情视图 */
    @Data
    @Schema(description = "Agent 视图")
    public static class AgentView {
        private String id;
        private String userId;
        private String name;
        private String description;
        private String avatar;
        private String systemPrompt;
        private String model;
        private List<String> tools;
        private List<String> kbIds;
        private BigDecimal temperature;
        @JsonProperty("isPublic")
        private boolean isPublic;
        @JsonProperty("isTemplate")
        private boolean isTemplate;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        /** 是否可被当前用户编辑 (创建者本人; 模板和他人公开 Agent 仅可克隆) */
        private boolean editable;
    }
}
