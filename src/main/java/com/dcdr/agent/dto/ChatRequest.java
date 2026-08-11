package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话请求
 */
@Data
@Schema(description = "对话请求")
public class ChatRequest {

    /** 会话ID */
    @Schema(description = "会话 ID", example = "a1b2c3d4", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sessionId;

    /** 用户消息内容 */
    @Schema(description = "用户消息内容", example = "你好", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    /** 指定模型(可选,不传则用会话默认或系统默认) */
    @Schema(description = "指定模型名称（可选，不传则用会话默认或系统默认）", example = "openai-chat")
    private String model;

    /** 图片URL列表(多模态输入,可选) */
    @Schema(description = "图片 URL 列表（多模态输入，可选）")
    private List<String> images = new ArrayList<>();

    /** 温度参数(可选) */
    @Schema(description = "温度参数（可选）", example = "0.7")
    private Double temperature;

    /** 是否流式(默认true) */
    @Schema(description = "是否流式输出（默认 true）", defaultValue = "true")
    private boolean stream = true;

    /** 函数调用工具定义列表(OpenAI function calling 格式), 由后端按需注入 */
    @Schema(description = "函数调用工具定义列表(OpenAI function calling 格式)", hidden = true)
    private List<Map<String, Object>> tools;

    /** 是否开启联网搜索(用户意图, 影响是否提示模型优先使用 web_search) */
    @Schema(description = "是否开启联网搜索（用户意图，影响是否优先使用 web_search 工具）", defaultValue = "false")
    private boolean webSearch = false;

    /**
     * 知识库 ID 列表(RAG 检索增强, 可选)。
     * <p>非空时, 后端在调用模型前会从这些知识库检索与用户问题相关的文档片段,
     * 拼装为 system prompt 上下文注入, 使模型基于知识库内容作答。
     */
    @Schema(description = "知识库 ID 列表（RAG 检索增强，可选；非空时从对应知识库检索相关片段注入上下文）")
    private List<String> knowledgeBaseIds;
}
