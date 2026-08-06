package com.dcdr.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 流式消息块 - SSE 推送的最小单元
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "流式消息块 - SSE 推送的最小单元")
public class ChatChunk {

    /** 块类型: chunk / tool_call / tool_result / done / error / interrupted */
    @Schema(description = "块类型", allowableValues = {"chunk", "tool_call", "tool_result", "done", "error", "interrupted"}, example = "chunk")
    private String type;

    /** 文本内容(type=chunk时) */
    @Schema(description = "文本内容（type=chunk 时）")
    private String content;

    @Schema(description = "推理内容（type=chunk 时）")
    private String reasoningContent;

    /** 工具名(type=tool_call/tool_result时) */
    @Schema(description = "工具名（type=tool_call/tool_result 时）", example = "calculator")
    private String tool;

    /** 工具调用 id(type=tool_call时, 由 provider 生成, 用于 tool_result 配对) */
    @Schema(description = "工具调用 id（type=tool_call 时，用于 tool_result 配对）")
    private String id;

    /** 工具参数(type=tool_call时, JSON字符串) */
    @Schema(description = "工具参数（type=tool_call 时，JSON 字符串）")
    private String args;

    /** 工具结果(type=tool_result时) */
    @Schema(description = "工具执行结果（type=tool_result 时）")
    private String result;

    /** 引用来源(type=tool_result且为联网搜索时, 携带来自 web_search 的来源列表) */
    @Schema(description = "引用来源（type=tool_result 且为联网搜索时，携带来源列表）")
    private java.util.List<java.util.Map<String, Object>> sources;

    /** 图片 URL(type=image 时, 文生图内联结果) */
    @Schema(description = "图片 URL（type=image 时，文生图内联结果）")
    private String imageUrl;

    /** 错误信息(type=error时) */
    @Schema(description = "错误信息（type=error 时）")
    private String message;

    /** 错误码(type=error时) */
    @Schema(description = "错误码（type=error 时）")
    private String code;

    /** Token消耗(type=done时) */
    @Schema(description = "Token 消耗（type=done 时）")
    private ChatResponse.TokenUsage tokenUsage;

    /** 结束原因(type=usage/done时, 来自模型 finish_reason, 如 stop/length/content_filter/insufficient_system_resource) */
    @Schema(description = "结束原因（来自模型 finish_reason）")
    private String finishReason;

    /** 本次请求服务端耗时(毫秒, type=done时) */
    @Schema(description = "本次请求服务端耗时(毫秒)")
    private Long durationMs;

    // ===== 静态工厂方法 =====

    public static ChatChunk chunk(String content) {
        ChatChunk c = new ChatChunk();
        c.setType("chunk");
        c.setContent(content);
        return c;
    }
    public static ChatChunk chunk(String content, String reasoningContent) {
        ChatChunk c = new ChatChunk();
        c.setType("chunk");
        c.setReasoningContent(reasoningContent);
        c.setContent(content);
        return c;
    }

    public static ChatChunk toolCall(String tool, String args) {
        return toolCall(null, tool, args);
    }

    /** 工具调用块(携带 id, 用于与后续 tool_result 配对回灌) */
    public static ChatChunk toolCall(String id, String tool, String args) {
        ChatChunk c = new ChatChunk();
        c.setType("tool_call");
        c.setId(id);
        c.setTool(tool);
        c.setArgs(args);
        return c;
    }

    public static ChatChunk toolResult(String tool, String result) {
        ChatChunk c = new ChatChunk();
        c.setType("tool_result");
        c.setTool(tool);
        c.setResult(result);
        return c;
    }

    /** 工具结果块(携带来源, 如联网搜索) */
    public static ChatChunk toolResult(String tool, String result, java.util.List<java.util.Map<String, Object>> sources) {
        ChatChunk c = toolResult(tool, result);
        c.setSources(sources);
        return c;
    }

    /** 文生图内联结果: type=image, 携带可访问的图片 URL */
    public static ChatChunk image(String imageUrl) {
        ChatChunk c = new ChatChunk();
        c.setType("image");
        c.setImageUrl(imageUrl);
        return c;
    }

    public static ChatChunk done(int prompt, int completion, int total) {
        ChatChunk c = new ChatChunk();
        c.setType("done");
        c.setTokenUsage(new ChatResponse.TokenUsage(prompt, completion, total));
        return c;
    }

    /** 完成块: 携带 token 统计 + 服务端耗时(毫秒) */
    public static ChatChunk done(int prompt, int completion, int total, long durationMs) {
        ChatChunk c = new ChatChunk();
        c.setType("done");
        c.setTokenUsage(new ChatResponse.TokenUsage(prompt, completion, total));
        c.setDurationMs(durationMs);
        return c;
    }

    public static ChatChunk error(String message, String code) {
        ChatChunk c = new ChatChunk();
        c.setType("error");
        c.setMessage(message);
        c.setCode(code);
        return c;
    }

    /** 中断块: 用户主动中断或客户端断开后由后端下发 */
    public static ChatChunk interrupted() {
        ChatChunk c = new ChatChunk();
        c.setType("interrupted");
        c.setMessage("对话已被用户中断");
        return c;
    }

    /** 临时状态提示: 如"正在识别图片…", 前端展示为过渡态, 收到正文/完成后清除 */
    public static ChatChunk status(String message) {
        ChatChunk c = new ChatChunk();
        c.setType("status");
        c.setMessage(message);
        return c;
    }

    /** 上下文接近上限提示: 建议用户开启新对话 */
    public static ChatChunk contextWarning(String message) {
        ChatChunk c = new ChatChunk();
        c.setType("context_warning");
        c.setMessage(message != null ? message : "对话已较长，建议开启新对话");
        return c;
    }

    /** 用量块: 模型真实返回的 token 消耗(prompt/completion/total) */
    public static ChatChunk usage(int prompt, int completion, int total) {
        ChatChunk c = new ChatChunk();
        c.setType("usage");
        c.setTokenUsage(new ChatResponse.TokenUsage(prompt, completion, total));
        return c;
    }
}
