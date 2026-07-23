package com.dcdr.agent.model.openai;

import com.dcdr.agent.config.AgentProperties;

/**
 * OpenAI 对话模型 Provider (GPT-4o)
 */
public class OpenAiChatProvider extends OpenAiCompatibleChatProvider {

    public OpenAiChatProvider(AgentProperties.ModelConfig config) {
        super(config.getBaseUrl(), config.getApiKey(),
                config.getName(), "GPT-4o", "openai", config.getModel(),
                config.getTemperature(), config.getMaxTokens(), config.getCapabilities());
    }
}
