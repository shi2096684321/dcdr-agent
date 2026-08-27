package com.dcdr.agent.config;

import com.dcdr.agent.model.deepseek.DeepseekChatProvider;
import com.dcdr.agent.model.kling.KlingVideoProvider;
import com.dcdr.agent.model.langchain4j.LangChain4jChatProvider;
import com.dcdr.agent.model.ollama.OllamaChatProvider;
import com.dcdr.agent.model.openai.OpenAiAudioProvider;
import com.dcdr.agent.model.openai.OpenAiChatProvider;
import com.dcdr.agent.model.openai.OpenAiImageProvider;
import com.dcdr.agent.model.qwen.QwenAudioProvider;
import com.dcdr.agent.model.qwen.QwenChatProvider;
import com.dcdr.agent.model.qwen.QwenImageProvider;
import com.dcdr.agent.model.wan.WanVideoProvider;
import com.dcdr.agent.model.zhipu.ZhipuChatProvider;
import com.dcdr.agent.model.zhipu.ZhipuImageProvider;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.model.circuitbreaker.CircuitBreakerChatProvider;
import com.dcdr.agent.model.circuitbreaker.LlmCircuitBreaker;
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
    public CommandLineRunner modelRegistrar(ModelRegistry registry, LlmCircuitBreaker circuitBreaker) {
        return args -> {
            AgentProperties.ModelsConfig models = properties.getModels();
            int chatCount = 0, imageCount = 0, videoCount = 0, audioCount = 0;
            if (models.getChat() != null) {
                for (AgentProperties.ModelConfig c : models.getChat()) {
                    if (!c.isEnabled()) continue;
                    try {
                        // V3.0-rc F-40.4: 所有对话 Provider 注册前包一层熔断装饰器 (关闭时透明直通)
                        java.util.function.UnaryOperator<ChatModelProvider> wrap = p ->
                                new CircuitBreakerChatProvider(p, circuitBreaker);
                        switch (c.getProvider()) {
                            // langchain4j: 框架内核实现(V3.1 起主线默认), 适配任意 OpenAI 兼容端点
                            case "langchain4j" -> { registry.registerChatProvider(wrap.apply(new LangChain4jChatProvider(c))); chatCount++; }
                            // V3.0-rc F-38 本地模型: Ollama OpenAI 兼容端点, 复用 langchain4j 内核
                            case "ollama" -> { registry.registerChatProvider(wrap.apply(new OllamaChatProvider(c))); chatCount++; }
                            // 以下为手写 WebClient 实现, 保留作为回退选项(改 provider 值即可切换)
                            case "openai" -> { registry.registerChatProvider(wrap.apply(new OpenAiChatProvider(c))); chatCount++; }
                            case "zhipu" -> { registry.registerChatProvider(wrap.apply(new ZhipuChatProvider(c))); chatCount++; }
                            case "qwen" -> { registry.registerChatProvider(wrap.apply(new QwenChatProvider(c))); chatCount++; }
                            case "deepseek" -> { registry.registerChatProvider(wrap.apply(new DeepseekChatProvider(c))); chatCount++; }
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
                            case "kuaishou" -> { registry.registerVideoProvider(new KlingVideoProvider(c.getBaseUrl(), c.getApiKey(), c.getAuthScheme(), c.getModel(), c.getName())); videoCount++; }
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
