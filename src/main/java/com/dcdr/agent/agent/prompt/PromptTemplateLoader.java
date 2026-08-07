package com.dcdr.agent.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板加载器（统一渲染层的基础设施）。
 * <p>
 * 负责从 classpath 读取提示词文本文件（{@code src/main/resources/prompts/*.txt}），
 * 作为可独立管理的<b>配置资产</b>，避免把长文本写在 {@code application.yml} 里导致配置文件臃肿。
 * <p>
 * 行为：
 * <ul>
 *   <li>首次读取后缓存，避免每次请求重复 IO；</li>
 *   <li>文件不存在或读取失败时返回 {@code null}，由调用方决定回退策略（配置中心/内置默认）；</li>
 *   <li>无状态、线程安全。</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptTemplateLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 读取 classpath 下的提示词文件内容（UTF-8）。失败返回 null。
     *
     * @param location 形如 {@code classpath:prompts/chat.md}；空或非法返回 null
     */
    public String load(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        return cache.computeIfAbsent(location, this::doLoad);
    }

    private String doLoad(String location) {
        try {
            String path = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.warn("[PromptTemplateLoader] 提示词文件不存在, location={}", location);
                return null;
            }
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[PromptTemplateLoader] 读取提示词文件失败, location={}, error={}", location, e.getMessage());
            return null;
        }
    }
}
