package com.example.agent.config;

import com.example.agent.model.deepseek.DeepseekChatProvider;
import com.example.agent.model.openai.OpenAiChatProvider;
import com.example.agent.model.openai.OpenAiImageProvider;
import com.example.agent.model.qwen.QwenChatProvider;
import com.example.agent.model.zhipu.ZhipuChatProvider;
import com.example.agent.model.zhipu.ZhipuImageProvider;
import com.example.agent.model.registry.ModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ModelAutoConfiguration {

    private final AgentProperties properties;

    @Bean
    public CommandLineRunner modelRegistrar(ModelRegistry registry) {
        return args -> {
            AgentProperties.ModelsConfig models = properties.getModels();
            int chatCount = 0, imageCount = 0;
            if (models.getChat() != null) {
                for (AgentProperties.ModelConfig c : models.getChat()) {
                    if (!c.isEnabled()) continue;
                    try {
                        switch (c.getProvider()) {
                            case "openai" -> { registry.registerChatProvider(new OpenAiChatProvider(c)); chatCount++; }
                            case "zhipu" -> { registry.registerChatProvider(new ZhipuChatProvider(c)); chatCount++; }
                            case "qwen" -> { registry.registerChatProvider(new QwenChatProvider(c)); chatCount++; }
                            case "deepseek" -> { registry.registerChatProvider(new DeepseekChatProvider(c)); chatCount++; }
                        }
                    } catch (Exception e) { log.error("注册对话模型失败: {}", c.getName(), e); }
                }
            }
            if (models.getImage() != null) {
                for (AgentProperties.ModelConfig c : models.getImage()) {
                    if (!c.isEnabled()) continue;
                    try {
                        switch (c.getProvider()) {
                            case "openai" -> { registry.registerImageProvider(new OpenAiImageProvider(c.getBaseUrl(), c.getApiKey(), c.getModel(), c.getSize())); imageCount++; }
                            case "zhipu" -> { registry.registerImageProvider(new ZhipuImageProvider(c.getBaseUrl(), c.getApiKey(), c.getModel())); imageCount++; }
                        }
                    } catch (Exception e) { log.error("注册图片模型失败: {}", c.getName(), e); }
                }
            }
            if (models.getDefaultChat() != null) registry.setDefaultChatProvider(models.getDefaultChat());
            log.info("模型注册完成: 对话{}个, 图片{}个", chatCount, imageCount);
        };
    }
}
