package com.dcdr.agent.rag.config;

import com.dcdr.agent.rag.embedding.EmbeddingRegistry;
import com.dcdr.agent.rag.embedding.OpenAiEmbeddingProvider;
import com.dcdr.agent.rag.embedding.QwenEmbeddingProvider;
import com.dcdr.agent.rag.embedding.ZhipuEmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding Provider 自动注册 (V2.1 RAG)。
 * <p>
 * 与 {@code ModelAutoConfiguration} 平行: 启动时遍历 {@code agent.rag.embedding.providers},
 * 按 provider 字段实例化对应实现并注册到 {@link EmbeddingRegistry}。
 * 启动期校验: 已启用 provider 的 dimensions 必须与 schema VECTOR(1536) 一致, 不一致直接失败。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EmbeddingAutoConfiguration {

    /** schema document_chunks.embedding 固定 1536 维, 启动期强校验 */
    private static final int SCHEMA_EMBEDDING_DIM = 1536;

    private final RagProperties properties;

    @Bean
    public CommandLineRunner embeddingRegistrar(EmbeddingRegistry registry) {
        return args -> {
            if (!properties.isEnabled()) {
                log.info("RAG 已禁用 (agent.rag.enabled=false), 跳过 Embedding 注册");
                return;
            }
            var configs = properties.getEmbedding().getProviders();
            if (configs == null || configs.isEmpty()) {
                log.warn("未配置任何 Embedding provider, RAG 上传/检索将不可用");
                return;
            }
            int count = 0;
            for (RagProperties.EmbeddingProviderConfig c : configs) {
                if (!c.isEnabled()) continue;
                // 维度强校验: 与 schema 不匹配直接跳过并告警, 避免入库时才报错(已写入部分 chunk)
                if (c.getDimensions() != SCHEMA_EMBEDDING_DIM) {
                    log.error("跳过 Embedding provider [{}]: 维度 {} 与 schema VECTOR({}) 不匹配, "
                                    + "请改用 1536 维模型或同步修改 schema 维度",
                            c.getName(), c.getDimensions(), SCHEMA_EMBEDDING_DIM);
                    continue;
                }
                try {
                    switch (c.getProvider()) {
                        case "qwen" -> { registry.register(new QwenEmbeddingProvider(c)); count++; }
                        case "openai" -> { registry.register(new OpenAiEmbeddingProvider(c)); count++; }
                        case "zhipu" -> { registry.register(new ZhipuEmbeddingProvider(c)); count++; }
                        default -> log.warn("未知 Embedding provider 类型: {} (name={})", c.getProvider(), c.getName());
                    }
                } catch (Exception e) {
                    log.error("注册 Embedding 模型失败: {}", c.getName(), e);
                }
            }
            String defaultName = properties.getEmbedding().getDefaultProvider();
            if (defaultName != null) {
                registry.setDefaultProviderName(defaultName);
            }
            log.info("Embedding 注册完成: {} 个, 默认: {}", count, defaultName != null ? defaultName : "(未指定, 取第一个)");
        };
    }
}
