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
        @Schema(description = "Agent 角色设定 (定义 Agent 的角色与行为; 由平台包装后转述给模型, 非系统提示词)")
        private String persona;
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
        @Schema(description = "是否为预设模板 (仅 admin 可设置; 模板对所有用户可见, 普通用户仅可克隆)")
        @JsonProperty("isTemplate")
        private Boolean isTemplate;
        @Schema(description = "变更说明 (V3.1 F-44.1, 更新时存入版本历史)")
        private String changeNote;
        @Schema(description = "职业头衔 (V3.1 专家元数据: 专家卡片标题)")
        private String profession;
        @Schema(description = "行业分类 (V3.1 专家元数据: 市场分类筛选)")
        private String categoryId;
        @Schema(description = "擅长领域标签 (V3.1 专家元数据)")
        private List<String> tags;
        @Schema(description = "推荐问法 (V3.1 专家元数据: 首条兼作开场引导)")
        private List<String> quickPrompts;
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
        @Schema(description = "Agent 角色设定 (由平台包装后转述给模型, 非系统提示词)")
        private String persona;
        private String model;
        private List<String> tools;
        private List<String> kbIds;
        private BigDecimal temperature;
        @JsonProperty("isPublic")
        private boolean isPublic;
        @JsonProperty("isTemplate")
        private boolean isTemplate;
        @Schema(description = "职业头衔 (V3.1 专家元数据)")
        private String profession;
        @Schema(description = "行业分类 (V3.1 专家元数据)")
        private String categoryId;
        @Schema(description = "擅长领域标签 (V3.1 专家元数据)")
        private List<String> tags;
        @Schema(description = "推荐问法 (V3.1 专家元数据)")
        private List<String> quickPrompts;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        /**
         * 是否可被当前用户编辑。
         * <p>
         * 口径: 管理员恒为 true(可编辑所有用户的 Agent, 含预设模板);
         * 普通用户仅当自己是创建者且该 Agent 非预设模板时为 true(模板与他人公开 Agent 仅可克隆)。
         */
        private boolean editable;
    }

    // ===== V3.1 F-44.1 版本管理视图 =====

    /** Agent 版本历史视图 */
    @Data
    @Schema(description = "Agent 版本历史视图 (F-44.1)")
    public static class VersionView {
        private String id;
        private String agentId;
        @Schema(description = "版本号 (同一 Agent 内递增)")
        private int version;
        @Schema(description = "版本快照: 名称")
        private String name;
        @Schema(description = "版本快照: 描述")
        private String description;
        @Schema(description = "变更说明")
        private String changeNote;
        @Schema(description = "创建人 (快照时的操作者)")
        private String createdBy;
        private LocalDateTime createdAt;
    }
}
