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

    /**
     * V2.3 自定义 Agent ID (可选)。
     * <p>非空时, 后端会加载该 Agent 定义, 把其 systemPrompt / model / tools / kbIds / temperature
     * 应用到本次对话(覆盖请求中对应字段), 实现角色化对话体验。
     */
    @Schema(description = "自定义 Agent ID（V2.3, 可选；指定后应用该 Agent 的角色设定/模型/工具/知识库）")
    private String agentId;

    /**
     * LLM 结构化输出约束 (可选, OpenAI 兼容 response_format 协议)。
     * <p>取值:
     * <ul>
     *   <li>{@code "json_object"}: 强制模型输出合法 JSON (兼容性最好, Qwen/DeepSeek/GLM/OpenAI 均支持)</li>
     *   <li>{@code "json_schema"}: 强制模型按指定 JSON Schema 输出 (需模型支持 strict 模式, 如 gpt-4o)</li>
     *   <li>{@code null}: 不约束, 模型自由输出文本</li>
     * </ul>
     * 启用 {@code json_object} 时, 须在 prompt 中说明期望的 JSON 结构。
     * 启用 {@code json_schema} 时, 须同时设置 {@link #jsonSchemaName} 与 {@link #jsonSchema}。
     */
    @Schema(description = "LLM 结构化输出约束（可选）：json_object 强制输出合法JSON / json_schema 按Schema输出 / 留空不约束", hidden = true)
    private String responseFormat;

    /**
     * JSON Schema 名称 (仅 {@code responseFormat="json_schema"} 时生效)。
     * <p>OpenAI strict 模式要求 schema 必须命名, 用于服务端标识。
     */
    @Schema(description = "JSON Schema 名称（仅 responseFormat=json_schema 时生效）", hidden = true)
    private String jsonSchemaName;

    /**
     * JSON Schema 定义 (仅 {@code responseFormat="json_schema"} 时生效)。
     * <p>标准 JSON Schema 对象, strict 模式下要求:
     * <ul>
     *   <li>所有字段声明在 {@code required} 中 (无可选字段)</li>
     *   <li>{@code additionalProperties: false}</li>
     *   <li>可空字段用 {@code "type":["string","null"]} 表示</li>
     * </ul>
     */
    @Schema(description = "JSON Schema 定义（仅 responseFormat=json_schema 时生效）", hidden = true)
    private Map<String, Object> jsonSchema;
}
