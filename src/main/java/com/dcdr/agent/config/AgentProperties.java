package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 核心配置属性绑定
 */
@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String name;

    private MemoryConfig memory = new MemoryConfig();
    private StorageConfig storage = new StorageConfig();
    private ModelsConfig models = new ModelsConfig();

    @Data
    public static class MemoryConfig {
        private int maxMessages = 20;
        private String storage = "memory";
        /** 估算上下文 token 上限, 达到 warn-ratio 比例时提示用户开启新对话 */
        private int contextTokenLimit = 8000;
        /** 触发提示的阈值比例(0~1) */
        private double contextWarnRatio = 0.8;
    }

    @Data
    public static class StorageConfig {
        private String baseDir = "./data";
        private String imageDir = "./data/images";
        private String videoDir = "./data/videos";
        private String uploadDir = "./data/uploads";
    }

    @Data
    public static class ModelsConfig {
        private String defaultChat;
        private List<ModelConfig> chat = new ArrayList<>();
        private List<ModelConfig> image = new ArrayList<>();
        private List<ModelConfig> video = new ArrayList<>();
    }

    @Data
    public static class ModelConfig {
        private String name;
        private boolean enabled = true;
        private String provider;
        private String apiKey;
        private String baseUrl;
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private String size;
        private List<String> capabilities = new ArrayList<>();
    }
}
