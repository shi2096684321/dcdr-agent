package com.dcdr.agent.config;

import com.dcdr.agent.model.deepseek.DeepseekChatProvider;
import com.dcdr.agent.model.kling.KlingVideoProvider;
import com.dcdr.agent.model.openai.OpenAiAudioProvider;
import com.dcdr.agent.model.openai.OpenAiChatProvider;
import com.dcdr.agent.model.openai.OpenAiImageProvider;
import com.dcdr.agent.model.qwen.QwenAudioProvider;
import com.dcdr.agent.model.qwen.QwenChatProvider;
import com.dcdr.agent.model.qwen.QwenImageProvider;
import com.dcdr.agent.model.wan.WanVideoProvider;
import com.dcdr.agent.model.zhipu.ZhipuChatProvider;
import com.dcdr.agent.model.zhipu.ZhipuImageProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
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
            int chatCount = 0, imageCount = 0, videoCount = 0, audioCount = 0;
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
                            case "qwen" -> { registry.registerImageProvider(new QwenImageProvider(c.getBaseUrl(), c.getApiKey(), c.getModel(), c.getName())); imageCount++; }
                            case "openai" -> { registry.registerImageProvider(new OpenAiImageProvider(c.getBaseUrl(), c.getApiKey(), c.getModel(), c.getSize())); imageCount++; }
                            case "zhipu" -> { registry.registerImageProvider(new ZhipuImageProvider(c.getBaseUrl(), c.getApiKey(), c.getModel())); imageCount++; }
                        }
                    } catch (Exception e) { log.error("注册图片模型失败: {}", c.getName(), e); }
                }
            }
            if (models.getVideo() != null) {
                for (AgentProperties.ModelConfig c : models.getVideo()) {
                    if (!c.isEnabled()) continue;
                    try {
                        switch (c.getProvider()) {
                            case "kuaishou" -> { registry.registerVideoProvider(new KlingVideoProvider(c.getBaseUrl(), c.getApiKey(), c.getModel(), c.getName())); videoCount++; }
                            case "wan" -> { registry.registerVideoProvider(new WanVideoProvider(c.getBaseUrl(), c.getApiKey(), c.getModel(), c.getName(), c.getModelI2v())); videoCount++; }
                        }
                    } catch (Exception e) { log.error("注册视频模型失败: {}", c.getName(), e); }
                }
            }
            if (models.getAudio() != null) {
                for (AgentProperties.ModelConfig c : models.getAudio()) {
                    if (!c.isEnabled()) continue;
                    try {
                        switch (c.getProvider()) {
                            case "openai" -> { registry.registerAudioProvider(new OpenAiAudioProvider(c.getBaseUrl(), c.getApiKey(), c.getName())); audioCount++; }
                            case "qwen" -> { registry.registerAudioProvider(new QwenAudioProvider(c.getBaseUrl(), c.getApiKey(), c.getName(), c.getModel())); audioCount++; }
                        }
                    } catch (Exception e) { log.error("注册语音模型失败: {}", c.getName(), e); }
                }
            }
            if (models.getDefaultChat() != null) registry.setDefaultChatProvider(models.getDefaultChat());
            log.info("模型注册完成: 对话{}个, 图片{}个, 视频{}个, 语音{}个", chatCount, imageCount, videoCount, audioCount);
        };
    }
}
