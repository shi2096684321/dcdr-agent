package com.dcdr.agent.rag.embedding;

import com.dcdr.agent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding Provider 注册中心 (V2.1 RAG)。
 * <p>
 * 与 {@code ModelRegistry} 平行, 仅管理 Embedding Provider。
 * 遵循开闭原则: 新增 provider 只需实现接口 + 注册, 不改此类。
 */
@Slf4j
@Component
public class EmbeddingRegistry {

    private final Map<String, EmbeddingProvider> providers = new ConcurrentHashMap<>();
    private String defaultProviderName;

    public void register(EmbeddingProvider provider) {
        providers.put(provider.getName(), provider);
        log.info("注册 Embedding 模型: {} ({}, {}维)", provider.getName(), provider.getLabel(), provider.getDimensions());
    }

    public void setDefaultProviderName(String name) {
        this.defaultProviderName = name;
    }

    /** 按名称获取, 不存在抛业务异常 */
    public EmbeddingProvider get(String name) {
        EmbeddingProvider p = providers.get(name);
        if (p == null) {
            throw new BusinessException(404, "Embedding 模型不存在: " + name, "EMBEDDING_NOT_FOUND");
        }
        return p;
    }

    /** 默认 provider: 优先配置指定, 否则取已注册的第一个 */
    public EmbeddingProvider getDefault() {
        if (defaultProviderName != null && providers.containsKey(defaultProviderName)) {
            return providers.get(defaultProviderName);
        }
        return providers.values().stream().findFirst()
                .orElseThrow(() -> new BusinessException(500, "没有可用的 Embedding 模型, 请检查 agent.rag.embedding.providers 配置",
                        "EMBEDDING_NOT_CONFIGURED"));
    }

    /** 是否有可用 provider */
    public boolean hasAvailable() {
        return !providers.isEmpty();
    }
}
