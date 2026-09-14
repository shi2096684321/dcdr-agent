package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消息 DTO
 */
@Data
@Schema(description = "消息记录")
public class MessageDTO {

    @Schema(description = "消息 ID")
    private String id;
    @Schema(description = "所属会话 ID")
    private String sessionId;
    /** 会话内自增序号, 用于按 seq 截断历史(编辑/重试) */
    @Schema(description = "会话内序号（用于编辑/重试时截断历史）")
    private Integer seq;
    /** 是否为失败(错误)消息 */
    @Schema(description = "是否为失败消息（用于前端重试）")
    private Boolean error;
    /** 是否被用户手动中断(产生不完整的回复) */
    @Schema(description = "是否被用户手动中断（生成不完整）")
    private Boolean interrupted;
    /**
     * V3.1 F-48: 当前用户对该条回答的反馈状态。
     * <p>
     * up=点赞 / down=点踩 / null=未反馈。仅 assistant 消息有意义, 用于前端刷新后正确回显按钮选中态。
     */
    @Schema(description = "当前用户对该回答的反馈: up / down, null 表示未反馈",
            allowableValues = {"up", "down"})
    private String feedback;
    /** 角色: user / assistant / system */
    @Schema(description = "角色", allowableValues = {"user", "assistant", "system"}, example = "user")
    private String role;
    /** 消息类型: text / image / video */
    @Schema(description = "消息类型", allowableValues = {"text", "image", "video"}, example = "text")
    private String type;
    /** 文本内容 */
    @Schema(description = "文本内容")
    private String content;
    /** 推理内容(思维链, assistant消息) */
    @Schema(description = "推理内容（思维链，assistant 消息）")
    private String reasoningContent;
    /** 用户上传的图片URL列表(多模态) */
    @Schema(description = "用户上传的图片 URL 列表（多模态，user 消息）")
    private List<String> images;
    /** 使用的模型 */
    @Schema(description = "使用的模型（assistant 消息）")
    private String model;
    /** 使用的工具(JSON) */
    @Schema(description = "使用的工具（JSON 字符串）")
    private String toolsUsed;
    /** 工具调用明细(JSON 数组: id/name/arguments), 用于还原 agentic 工具往返 */
    @Schema(description = "工具调用明细（agentic 工具往返，role=assistant 时）")
    private List<Map<String, Object>> toolCalls;
    /** 本轮对话中模型调用的工具清单(聚合自被隐藏的工具往返, 供前端在回答下方折叠展示) */
    @Schema(description = "本轮调用的工具清单（name/arguments），供前端折叠展示")
    private List<Map<String, Object>> toolInvocations;
    /** tool 角色消息对应的 tool_call id */
    @Schema(description = "工具调用 id（role=tool 时，配对 assistant 的 tool_calls[].id）")
    private String toolCallId;
    /** tool 角色消息对应的工具名称 */
    @Schema(description = "工具名称（role=tool 时）")
    private String name;
    /** 引用来源(联网搜索等结果) */
    @Schema(description = "引用来源（联网搜索等结果，含 title/url/content）")
    private List<Map<String, Object>> sources;
    /** Token消耗 */
    @Schema(description = "Token 消耗")
    private Integer tokenUsage;
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
