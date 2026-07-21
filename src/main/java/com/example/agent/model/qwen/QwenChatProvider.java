package com.example.agent.model.qwen;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.openai.OpenAiCompatibleChatProvider;

/**
 * 通义千问对话模型 Provider (兼容OpenAI格式)
 */
public class QwenChatProvider extends OpenAiCompatibleChatProvider {

    public QwenChatProvider(AgentProperties.ModelConfig config) {
        super(config.getBaseUrl(), config.getApiKey(),
                config.getName(), "Qwen-Max", "qwen", config.getModel(),
                config.getTemperature(), config.getMaxTokens(), config.getCapabilities());
    }
}
