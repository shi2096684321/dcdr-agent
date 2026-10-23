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

    /**
     * 一句话生成专家请求 (V3.2)。
     * <p>
     * 用户只需要说清"要一个做什么的专家", 其余配置(名称/角色设定/元数据/工具/温度)由模型补齐。
     */
    @Data
    @Schema(description = "AI 生成专家请求")
    public static class GenerateAgentRequest {
        @Schema(description = "需求说明 (一句话即可, 如: 帮我做一个审查劳动合同的专家)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String brief;
        @Schema(description = "生成所用的模型名称 (留空则用系统默认)")
        private String model;
    }

    /**
     * AI 生成的专家草稿。
     * <p>
     * <b>刻意不落库</b>: 生成结果是模型推断, 用户看一眼、改两笔再保存, 比"直接建出一个
     * 名字奇怪、工具配错的专家"要好收拾得多。前端拿它回填创建表单, 保存仍走
     * {@code POST /api/agents} —— 于是配额、工具白名单、版本快照这些既有约束一个都不用重写。
     */
    @Data
    @Schema(description = "AI 生成的专家草稿 (未落库, 供前端回填表单)")
    public static class AgentDraft {
        @Schema(description = "专家名称")
        private String name;
        @Schema(description = "一句话简介")
        private String description;
        @Schema(description = "专家卡片标题 (职业头衔)")
        private String profession;
        @Schema(description = "行业分类")
        private String categoryId;
        @Schema(description = "擅长领域标签")
        private List<String> tags;
        @Schema(description = "推荐问法 (首条兼作开场引导)")
        private List<String> quickPrompts;
        @Schema(description = "角色设定正文")
        private String persona;
        @Schema(description = "建议启用的工具 (已按当前用户权限与该模型可编造范围过滤)")
        private List<String> tools;
        @Schema(description = "建议温度 0~2")
        private BigDecimal temperature;
        @Schema(description = "实际执行生成的模型 (用于界面标注与排查)")
        private String generatedBy;
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
