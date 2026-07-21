package com.example.agent.dto;

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

    @Data
    @Schema(description = "Token 消耗统计")
    public static class TokenUsage {
        @Schema(description = "输入 Token 数", example = "120")
        private int prompt;
        @Schema(description = "输出 Token 数", example = "35")
        private int completion;
        @Schema(description = "总 Token 数", example = "155")
        private int total;

        public TokenUsage() {}

        public TokenUsage(int prompt, int completion, int total) {
            this.prompt = prompt;
            this.completion = completion;
            this.total = total;
        }
    }
}
