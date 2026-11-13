package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 对话响应(同步模式)
 */
@Data
@Schema(description = "对话响应（同步模式）")
public class ChatResponse {

    /** 回复内容 */
    @Schema(description = "回复内容", example = "你好！我是 JavaAgent，有什么可以帮你？")
    private String content;

    /** 使用的模型 */
    @Schema(description = "使用的模型名称", example = "openai-chat")
    private String model;

    /** 使用的工具(JSON) */
    @Schema(description = "使用的工具信息（JSON 字符串）")
    private String toolsUsed;

    /** Token消耗 */
    @Schema(description = "Token 消耗")
    private TokenUsage tokenUsage;

    /**
     * 待用户回答的提问列表(同步接口专用)。
     * <p>
     * 流式接口靠 {@code user_input_request} 块下发; 同步接口没有"中间块", 只能把它挂在响应上。
     * 非空表示这一轮没有产出回答, 而是<strong>在等用户选择</strong> ——
     * 调用方(RPA/定时任务等)应当把问题抛给用户, 拿到答案后带上 {@code questionAnswer} 再调一次,
     * 不要把它当成"回答为空的成功响应"。
     */
    @Schema(description = "待用户回答的提问列表；非空表示本轮在等用户选择，而非已完成")
    private java.util.List<java.util.Map<String, Object>> pendingQuestions;

    /** 待回答提问对应的 tool_call id, 与下一次请求的 questionAnswer 配套使用 */
    @Schema(description = "待回答提问对应的工具调用 id")
    private String pendingRequestId;

    /** 上下文/能力提示(如: 当前模型不支持看图, 已用视觉模型识别后作答) */
    @Schema(description = "上下文或能力提示信息")
    private String contextWarning;

    @Data
    @Schema(description = "Token 消耗统计")
    public static class TokenUsage {
        @Schema(description = "输入 Token 数", example = "120")
        private int prompt;
        @Schema(description = "输出 Token 数", example = "35")
        private int completion;
        @Schema(description = "总 Token 数", example = "155")
        private int total;

        /**
         * 命中「前缀缓存」的输入 token 数(按缓存价计费, 通常为输入价的 1/10 左右)。
         * <p>字段来源: OpenAI {@code usage.prompt_tokens_details.cached_tokens};
         * DeepSeek {@code prompt_cache_hit_tokens}; Anthropic {@code cache_read_input_tokens}。
         * 供应商未上报时恒为 0 —— 0 表示「没有观测到缓存」, 不表示「一定没有缓存」。
         */
        @Schema(description = "命中前缀缓存的输入 token 数(未上报时为 0)", example = "1024")
        private int cacheReadTokens;

        /**
         * 写入缓存的输入 token 数(仅 Anthropic 显式上报 {@code cache_creation_input_tokens};
         * OpenAI / DeepSeek 的缓存写入隐含在 prompt 里, 恒为 0)。
         */
        @Schema(description = "写入前缀缓存的输入 token 数(仅部分供应商上报)", example = "0")
        private int cacheCreationTokens;

        public TokenUsage() {}

        public TokenUsage(int prompt, int completion, int total) {
            this.prompt = prompt;
            this.completion = completion;
            this.total = total;
        }

        /** 带缓存明细的构造: 新增字段一律追加在末尾, 老调用方的三参构造保持可用 */
        public TokenUsage(int prompt, int completion, int total, int cacheReadTokens, int cacheCreationTokens) {
            this(prompt, completion, total);
            this.cacheReadTokens = cacheReadTokens;
            this.cacheCreationTokens = cacheCreationTokens;
        }

        /**
         * 前缀命中率 = 缓存读 token / 输入 token。
         * <p>输入 token 为 0(无 usage)时返回 0, 避免出现 NaN —— 调用方不必各自判空。
         */
        public double cacheHitRate() {
            return prompt > 0 ? (double) cacheReadTokens / prompt : 0.0;
        }
    }
}
