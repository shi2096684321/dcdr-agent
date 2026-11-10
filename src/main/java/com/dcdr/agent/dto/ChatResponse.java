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

        public TokenUsage() {}

        public TokenUsage(int prompt, int completion, int total) {
            this.prompt = prompt;
            this.completion = completion;
            this.total = total;
        }
    }
}
