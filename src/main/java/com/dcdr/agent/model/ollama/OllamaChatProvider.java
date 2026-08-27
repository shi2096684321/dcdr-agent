package com.dcdr.agent.model.ollama;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.model.langchain4j.LangChain4jChatProvider;

/**
 * V3.0-rc F-38 本地模型支持: Ollama 对话 Provider。
 * <p>
 * Ollama 原生暴露 OpenAI 兼容端点 ({@code http://localhost:11434/v1}),
 * 因此复用 {@link LangChain4jChatProvider} 的全部能力 (流式/思考链/工具调用),
 * 仅覆写 label 展示, 前端模型选择器经 /api/models 自动可见。
 * <p>
 * 配置 (application.yml, 默认关闭):
 * <pre>
 * agent.models.chat:
 *   - name: ollama-chat
 *     enabled: false
 *     provider: ollama            # ModelAutoConfiguration 走本类
 *     base-url: http://localhost:11434/v1
 *     model: qwen3:8b
 * </pre>
 */
public class OllamaChatProvider extends LangChain4jChatProvider {

    public OllamaChatProvider(AgentProperties.ModelConfig config) {
        super(config);
    }

    @Override
    public String getLabel() {
        // 展示区分本地模型, 如 "Ollama qwen3:8b"
        return "Ollama " + super.getLabel();
    }
}
