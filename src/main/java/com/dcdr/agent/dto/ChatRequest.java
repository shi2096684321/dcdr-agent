package com.dcdr.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * 模型是否由调用方"锁定"(服务端内部字段, 不参与 JSON 传输)。
     * <p>
     * 默认 {@code null/false}: 指定了 Agent 时, Agent 配置的模型覆盖请求模型(前端选模型 + 选专家
     * 的既有语义)。置为 {@code true} 时 Agent 不再覆盖模型 —— 定时任务里用户显式选的执行模型
     * 必须落地, 否则"选了模型却跑了另一个"是不可解释的行为。
     */
    @JsonIgnore
    @Schema(hidden = true)
    private Boolean modelLocked;

    /** 图片URL列表(多模态输入,可选) */
    @Schema(description = "图片 URL 列表（多模态输入，可选）")
    private List<String> images = new ArrayList<>();

    /**
     * 用户对「上一条提问卡片」的结构化回答(可选)。
     * <p>
     * 形如 {@code {"q1":["docker"],"q2":["a","b"]}} —— key 是提问块里下发的 questionId,
     * value 是选中的选项值列表(单选也放数组, 前端不必分两种形状)。
     * 也兼容 {@code {"questions":[{"id":"q1","answers":["docker"]}]}}。
     * <p>
     * 为什么不直接拿 {@link #message} 当答案: 一句话在多问题场景下看不出回答的是哪一题。
     * 但本字段<b>可为空</b> —— 用户不点选项、直接在输入框打字是完全合法的回答方式,
     * 此时后端退化成用 message 原文作答。
     */
    @Schema(description = "对提问卡片的结构化回答（questionId → 选中值列表），可空")
    private Map<String, Object> questionAnswer;

    /** 温度参数(可选) */
    @Schema(description = "温度参数（可选）", example = "0.7")
    private Double temperature;

    /** 是否流式(默认true) */
    @Schema(description = "是否流式输出（默认 true）", defaultValue = "true")
    private boolean stream = true;

    /** 函数调用工具定义列表(OpenAI function calling 格式), 由后端按需注入 */
    @Schema(description = "函数调用工具定义列表(OpenAI function calling 格式)", hidden = true)
    private List<Map<String, Object>> tools;

    /**
     * 函数调用工具定义列表(框架原生 ToolSpecification, V3.0)。
     * <p>
     * 由 ToolRegistry 注册期缓存直供, LangChain4j Provider 优先消费(零 Map 往返转换);
     * legacy 手写 Provider 回退消费 {@link #tools}。仅服务端内部传递, 不参与 JSON 序列化。
     */
    @JsonIgnore
    @Schema(hidden = true)
    private transient List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications;

    /** 是否开启联网搜索(用户意图, 影响是否提示模型优先使用 web_search) */
    @Schema(description = "是否开启联网搜索（用户意图，影响是否优先使用 web_search 工具）", defaultValue = "false")
    private boolean webSearch = false;

    /**
     * 是否开启深度思考(思维链) (可选, 仅思考型模型生效, 如 Qwen3 混合推理系列)。
     * <p>语义:
     * <ul>
     *   <li>{@code null}/{@code true}: 不干预, 走模型默认行为(Qwen3 默认开启思考)</li>
     *   <li>{@code false}: 传厂商关闭参数真关闭(如 DashScope {@code enable_thinking=false}),
     *       模型跳过思考阶段直接作答, 节省思考 token 与首字延迟(区别于仅前端不展示)</li>
     * </ul>
     * 注意: 各厂商参数不同(DashScope enable_thinking / 智谱 thinking.type), 由 Provider 按 base-url 适配;
     * 不支持关闭的模型(如 deepseek-reasoner)忽略此开关。
     */
    @Schema(description = "是否开启深度思考（可选，仅思考型模型生效；false 时真关闭思考省 token，默认不干预走模型默认）", defaultValue = "true")
    private Boolean thinkingEnabled;

    /**
     * 知识库 ID 列表(RAG 检索增强, 可选)。
     * <p>非空时, 后端在调用模型前会从这些知识库检索与用户问题相关的文档片段,
     * 拼装为 system prompt 上下文注入, 使模型基于知识库内容作答。
     */
    @Schema(description = "知识库 ID 列表（RAG 检索增强，可选；非空时从对应知识库检索相关片段注入上下文）")
    private List<String> knowledgeBaseIds;

    /**
     * 用户手动指定的技能标识列表 (V3.1, 可选)。
     * <p>
     * <b>与"技能目录"的关系</b>: 默认情况下技能是<b>模型自己判断</b>要不要加载的 ——
     * 平台只把目录(name + 简介)注入 system prompt, 正文由模型调用 {@code skill_load} 取回。
     * 这里非空则表示<b>用户显式指定</b>, 后端会跳过"模型自己判断"这一步, <b>直接把正文注入</b>,
     * 并把它们从技能目录中移除(否则模型会再 skill_load 一次, 同一份正文进上下文两遍)。
     * <p>
     * <b>只认当前用户"可见且已启用"的技能</b>: 名字来自请求体(前端可选列表), 若直接按名查库,
     * 任何人都能通过构造请求加载他人的私有技能。解析在
     * {@code SkillCatalogProvider#skillPrompts} 内、以 {@code visibleEnabledSkills} 为准。
     */
    @Schema(description = "用户手动指定使用的技能标识列表（V3.1, 可选；指定后直接注入其正文, 不再等模型自行判断）")
    private List<String> skillNames;

    /**
     * V2.3 自定义 Agent ID (可选)。
     * <p>非空时, 后端会加载该 Agent 定义, 把其 systemPrompt / model / tools / kbIds / temperature
     * 应用到本次对话(覆盖请求中对应字段), 实现角色化对话体验。
     */
    @Schema(description = "自定义 Agent ID（V2.3, 可选；指定后应用该 Agent 的角色设定/模型/工具/知识库）")
    private String agentId;

    /**
     * LLM 结构化输出约束 (可选)。
     * <p>取值:
     * <ul>
     *   <li>{@code "json_object"}: 强制模型输出合法 JSON (兼容性最好, Qwen/DeepSeek/GLM/OpenAI 均支持)</li>
     *   <li>{@code "json_schema"}: 强制模型按指定 JSON Schema 输出 (需模型支持 strict 模式, 如 gpt-4o)</li>
     *   <li>{@code "tool_call"}: 把期望输出包装为"提交结果"工具并强制调用(tool_choice 指定函数),
     *       从 tool_calls[].function.arguments 取符合 schema 的 JSON。schema 级保证且国产模型
     *       (Qwen/DeepSeek/GLM) function calling 通道普遍支持, 是国产模型下结构化输出的首选路线</li>
     *   <li>{@code null}: 不约束, 模型自由输出文本</li>
     * </ul>
     * 启用 {@code json_object} 时, 须在 prompt 中说明期望的 JSON 结构。
     * 启用 {@code json_schema} 或 {@code tool_call} 时, 须同时设置 {@link #jsonSchemaName} 与 {@link #jsonSchema}。
     */
    @Schema(description = "LLM 结构化输出约束（可选）：json_object / json_schema / tool_call / 留空不约束", hidden = true)
    private String responseFormat;

    /**
     * JSON Schema 名称 (仅 {@code responseFormat="json_schema"} 或 {@code "tool_call"} 时生效)。
     * <p>json_schema strict 模式要求 schema 必须命名; tool_call 模式下作为函数名。
     */
    @Schema(description = "JSON Schema 名称（json_schema/tool_call 模式下生效, tool_call 模式作为函数名）", hidden = true)
    private String jsonSchemaName;

    /**
     * JSON Schema 定义 (仅 {@code responseFormat="json_schema"} 或 {@code "tool_call"} 时生效)。
     * <p>标准 JSON Schema 对象:
     * <ul>
     *   <li>所有字段声明在 {@code required} 中 (无可选字段)</li>
     *   <li>{@code additionalProperties: false}</li>
     *   <li>可空字段用 {@code "type":["string","null"]} 表示</li>
     * </ul>
     */
    @Schema(description = "JSON Schema 定义（json_schema/tool_call 模式下生效）", hidden = true)
    private Map<String, Object> jsonSchema;

    /**
     * 结构化输出说明 (可选, 仅 {@code responseFormat="tool_call"} 时生效)。
     * <p>作为包装工具的 function description, 帮助模型理解各字段语义、提升填充质量。
     */
    @Schema(description = "结构化输出说明（仅 tool_call 模式生效, 作为工具描述）", hidden = true)
    private String jsonSchemaDescription;

    /**
     * 收尾轮副本: 复制本请求但摘掉全部工具定义。
     * <p>
     * <b>为什么需要副本</b>: 原 request 在工具循环里被每一轮复用, 临时清空再还原既不是原子的,
     * 也会让调用链日志里"这一轮究竟发了什么"不可信。
     * <p>
     * <b>为什么逐字段手写而不是框架拷贝工具</b>: 收尾轮必须与正常轮保持同一套生成参数
     * (温度 / 思考开关 / 结构化输出), 漏拷一个字段会让同一句话在不同轮次表现不一致;
     * 手写在本类内, 新增字段时改动点就在字段声明旁边, 不会被忽略。
     * 已在 prepare 阶段消费完的字段(agentId / skillNames / knowledgeBaseIds / images)
     * 也一并拷过来 —— 它们对 provider 无效, 但保留能让调用链日志完整还原这次请求。
     */
    public ChatRequest copyWithoutTools() {
        ChatRequest copy = new ChatRequest();
        copy.sessionId = sessionId;
        copy.message = message;
        copy.model = model;
        copy.modelLocked = modelLocked;
        copy.images = images;
        copy.temperature = temperature;
        copy.stream = stream;
        copy.webSearch = webSearch;
        copy.thinkingEnabled = thinkingEnabled;
        copy.knowledgeBaseIds = knowledgeBaseIds;
        copy.skillNames = skillNames;
        copy.agentId = agentId;
        copy.responseFormat = responseFormat;
        copy.jsonSchemaName = jsonSchemaName;
        copy.jsonSchema = jsonSchema;
        copy.jsonSchemaDescription = jsonSchemaDescription;
        // tools / toolSpecifications 刻意不拷: 收尾轮的全部意义就是让模型没有工具可调
        return copy;
    }
}
