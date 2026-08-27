package com.dcdr.agent.tool.plugin;

import java.util.Map;

/**
 * V3.0-rc F-37: 插件初始化上下文。
 * <p>
 * 平台向插件注入的受控环境: 插件目录/工作目录/自定义配置。
 * 故意不提供 ClassLoader/文件系统/网络等原生能力 —— 插件按需自行封装,
 * 加载前危险 API 审计 (PluginLoader.blockDangerous) 会拦截越权行为。
 */
public class PluginContext {

    /** 插件 JAR 所在目录 */
    private final String pluginDir;
    /** 插件自定义配置 (agent.v3.plugin.config.<plugin-name>.* 透传, 便于不同插件差异化配置) */
    private final Map<String, String> config;

    public PluginContext(String pluginDir, Map<String, String> config) {
        this.pluginDir = pluginDir;
        this.config = config == null ? Map.of() : config;
    }

    public String getPluginDir() { return pluginDir; }

    /** 读取插件自定义配置 (key 不存在返回 def) */
    public String getConfig(String key, String def) {
        return config.getOrDefault(key, def);
    }

    public Map<String, String> getConfig() { return config; }
}
