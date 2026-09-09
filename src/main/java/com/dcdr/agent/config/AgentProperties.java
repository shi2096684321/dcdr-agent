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
        private int maxMessages = 6;
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
        private String defaultChat = "qwen-chat";
        /** 用户自定义模型 apiKey 的 AES 加密主密钥(>=16 字节)。生产用环境变量 MODEL_API_KEY_SECRET 注入; 缺省回退内置值 */
        private String apiKeySecret;
        private List<ModelConfig> chat = new ArrayList<>();
        private List<ModelConfig> image = new ArrayList<>();
        private List<ModelConfig> video = new ArrayList<>();
        /** 语音模型 (STT/TTS), V1.1 新增 */
        private List<ModelConfig> audio = new ArrayList<>();
    }

    @Data
    public static class ModelConfig {
        private String name;
        private boolean enabled = true;
        private String provider;
        /** 实现引擎: langchain4j=框架内核(默认, 适配任意 OpenAI 兼容端点) | manual=手写 WebClient 实现(回退选项) */
        private String engine = "langchain4j";
        private String apiKey;
        /** 主 base-url: langchain4j 引擎使用(版本段如 /v1 由它携带, 框架在其后追加资源路径) */
        private String baseUrl;
        /** 手写引擎专用 base-url(可选): 仅当两种引擎端点本质不同时配置, 如 qwen 生图
         *  (manual=DashScope 原生根地址 / langchain4j=compatible-mode); 缺省回退 base-url */
        private String manualBaseUrl;
        /** 鉴权头格式: bearer(默认, 即 "Bearer <key>") 或 raw(即 "<key>", 部分中转平台用) */
        private String authScheme = "bearer";
        private String model;
        /** 图生视频专用模型名(可选, 默认同 model), 如万相 wan2.7-i2v 系列 */
        private String modelI2v;
        private Double temperature;
        private Integer maxTokens;
        private String size;
        private List<String> capabilities = new ArrayList<>();
    }
}
