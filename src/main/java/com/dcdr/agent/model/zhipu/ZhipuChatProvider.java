package com.dcdr.agent.model.zhipu;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.model.openai.OpenAiCompatibleChatProvider;

/**
 * 智谱 GLM 对话模型 Provider
 * <p>
 * 智谱 API 兼容 OpenAI 格式, 复用基类逻辑。
 */
public class ZhipuChatProvider extends OpenAiCompatibleChatProvider {

    public ZhipuChatProvider(AgentProperties.ModelConfig config) {
        super(config.getBaseUrl(), config.getApiKey(),
                config.getName(), "GLM-4-Plus", "zhipu", config.getModel(),
                config.getTemperature(), config.getMaxTokens(), config.getCapabilities());
    }

    @Override
    protected String getChatPath() {
        return "/chat/completions";
    }
}
