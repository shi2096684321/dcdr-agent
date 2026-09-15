package com.dcdr.agent.auth.ownership;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 归属 Provider 注册表 (V3.1 F-41 统一归属框架)。
 * <p>
 * 启动时收集所有 {@link ResourceOwnershipProvider} Bean, 并按 {@link ResourceType} 建索引。
 * <p>
 * <b>fail-fast 设计</b>: 若某个 {@link ResourceType} 缺少 Provider, 或同一类型存在多个 Provider,
 * 应用将在启动期直接失败。这是刻意的 —— 新增受保护资源时若忘记实现 Provider,
 * 宁可启动失败也不能静默放过越权请求。
 */
@Slf4j
@Component
public class OwnershipRegistry {

    private final Map<ResourceType, ResourceOwnershipProvider> providers;

    public OwnershipRegistry(List<ResourceOwnershipProvider> providerList) {
        Map<ResourceType, ResourceOwnershipProvider> map = new EnumMap<>(ResourceType.class);
        for (ResourceOwnershipProvider provider : providerList) {
            ResourceOwnershipProvider previous = map.put(provider.type(), provider);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "资源类型 %s 存在多个归属 Provider: %s 与 %s",
                        provider.type(), previous.getClass().getName(), provider.getClass().getName()));
            }
        }

        List<ResourceType> missing = new ArrayList<>();
        for (ResourceType type : ResourceType.values()) {
            if (!map.containsKey(type)) missing.add(type);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "以下资源类型缺少归属 Provider, 无法启动统一归属框架: " + missing
                            + " (请为每个 ResourceType 提供 ResourceOwnershipProvider 实现)");
        }

        this.providers = Collections.unmodifiableMap(map);
        log.info("统一归属框架已装配 {} 类资源的归属 Provider: {}", providers.size(), providers.keySet());
    }

    /** 获取指定资源类型的 Provider (启动期已保证存在) */
    public ResourceOwnershipProvider get(ResourceType type) {
        ResourceOwnershipProvider provider = providers.get(type);
        if (provider == null) {
            // 理论不可达: 构造器已 fail-fast 校验
            throw new IllegalStateException("未注册归属 Provider 的资源类型: " + type);
        }
        return provider;
    }
}
