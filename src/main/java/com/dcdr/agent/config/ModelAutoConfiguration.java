package com.dcdr.agent.config;

import com.dcdr.agent.model.deepseek.DeepseekChatProvider;
import com.dcdr.agent.model.kling.KlingVideoProvider;
import com.dcdr.agent.model.langchain4j.LangChain4jAudioProvider;
import com.dcdr.agent.model.langchain4j.LangChain4jChatProvider;
import com.dcdr.agent.model.langchain4j.LangChain4jImageProvider;
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
    public CommandLineRunner modelRegistrar(ModelRegistry registry, LlmCircuitBreaker circuitBreaker, LlmMetrics metrics) {
        return args -> {
            AgentProperties.ModelsConfig models = properties.getModels();
            int chatCount = 0, imageCount = 0, videoCount = 0, audioCount = 0;
            if (models.getChat() != null) {
                for (AgentProperties.ModelConfig c : models.getChat()) {
                    if (!c.isEnabled()) continue;
                    try {
                        // V3.0-rc F-40.4: 所有对话 Provider 注册前包一层熔断装饰器 (关闭时透明直通)
                        // V3.0 F-40.2: 同时注入 LlmMetrics, 在统一出口打自定义业务指标
                        java.util.function.UnaryOperator<ChatModelProvider> wrap = p ->
                                new CircuitBreakerChatProvider(p, circuitBreaker, metrics);
                        // engine 分发: manual=手写 WebClient 实现(回退); 默认 langchain4j 框架内核
                        if ("manual".equals(c.getEngine())) {
                            switch (c.getProvider()) {
                                case "openai" -> { registry.registerChatProvider(wrap.apply(new OpenAiChatProvider(c))); chatCount++; }
                                case "zhipu" -> { registry.registerChatProvider(wrap.apply(new ZhipuChatProvider(c))); chatCount++; }
                                case "qwen" -> { registry.registerChatProvider(wrap.apply(new QwenChatProvider(c))); chatCount++; }
                                case "deepseek" -> { registry.registerChatProvider(wrap.apply(new DeepseekChatProvider(c))); chatCount++; }
                                default -> log.warn("未知手写对话 provider: {} (name={}), 跳过", c.getProvider(), c.getName());
                            }
                        } else if ("ollama".equals(c.getProvider())) {
                            // V3.0-rc F-38 本地模型: Ollama OpenAI 兼容端点, 复用 langchain4j 内核
                            // (健康检查按 provider=ollama 识别, 故按厂商而非 engine 路由)
                            registry.registerChatProvider(wrap.apply(new OllamaChatProvider(c))); chatCount++;
                        } else {
                            registry.registerChatProvider(wrap.apply(new LangChain4jChatProvider(c))); chatCount++;
                        }
                    } catch (Exception e) { log.error("注册对话模型失败: {}", c.getName(), e); }
                }
            }
            if (models.getImage() != null) {
                for (AgentProperties.ModelConfig c : models.getImage()) {
                    if (!c.isEnabled()) continue;
                    try {
                        // engine 分发: manual=手写 WebClient 实现(回退); 默认 langchain4j 框架内核。
                        // manual 引擎 base-url 优先取 manual-base-url(两种引擎端点本质不同时各自配置), 缺省回退 base-url
                        if ("manual".equals(c.getEngine())) {
                            String manualUrl = c.getManualBaseUrl() != null ? c.getManualBaseUrl() : c.getBaseUrl();
                            switch (c.getProvider()) {
                                case "qwen" -> { registry.registerImageProvider(new QwenImageProvider(manualUrl, c.getApiKey(), c.getModel(), c.getName())); imageCount++; }
                                case "openai" -> { registry.registerImageProvider(new OpenAiImageProvider(manualUrl, c.getApiKey(), c.getModel(), c.getSize())); imageCount++; }
                                case "zhipu" -> { registry.registerImageProvider(new ZhipuImageProvider(manualUrl, c.getApiKey(), c.getModel())); imageCount++; }
                                default -> log.warn("未知手写图片 provider: {} (name={}), 跳过", c.getProvider(), c.getName());
                            }
                        } else {
                            registry.registerImageProvider(new LangChain4jImageProvider(c)); imageCount++;
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
                        // engine 分发: manual=手写 WebClient 实现(回退); 默认 langchain4j 框架内核。
                        // qwen 语音走 DashScope Sambert 原生协议(无 OpenAI 兼容端点), 始终手写实现
                        if ("openai".equals(c.getProvider()) && !"manual".equals(c.getEngine())) {
                            registry.registerAudioProvider(new LangChain4jAudioProvider(c)); audioCount++;
                        } else {
                            String manualUrl = c.getManualBaseUrl() != null ? c.getManualBaseUrl() : c.getBaseUrl();
                            switch (c.getProvider()) {
                                case "openai" -> { registry.registerAudioProvider(new OpenAiAudioProvider(manualUrl, c.getApiKey(), c.getName())); audioCount++; }
                                case "qwen" -> { registry.registerAudioProvider(new QwenAudioProvider(manualUrl, c.getApiKey(), c.getName(), c.getModel())); audioCount++; }
                                default -> log.warn("未知语音 provider: {} (name={}), 跳过", c.getProvider(), c.getName());
                            }
                        }
                    } catch (Exception e) { log.error("注册语音模型失败: {}", c.getName(), e); }
                }
            }
            if (models.getDefaultChat() != null) registry.setDefaultChatProvider(models.getDefaultChat());
            log.info("模型注册完成: 对话{}个, 图片{}个, 视频{}个, 语音{}个", chatCount, imageCount, videoCount, audioCount);
        };
    }
}
