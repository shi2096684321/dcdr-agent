package com.dcdr.agent.model.deepseek;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.model.openai.OpenAiCompatibleChatProvider;

/**
 *  Deepseek对话模型 Provider (兼容OpenAI格式)
 */
public class DeepseekChatProvider extends OpenAiCompatibleChatProvider {

    public DeepseekChatProvider(AgentProperties.ModelConfig config) {
        super(config.getBaseUrl(), config.getApiKey(),
                config.getName(), "deepseek-v4-flash", "deepseek", config.getModel(),
                config.getTemperature(), config.getMaxTokens(), config.getCapabilities());
    }

    @Override
    protected String getChatPath() {
        return "/chat/completions";
    }
}
