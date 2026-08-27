package com.dcdr.agent.tool.plugin;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * V3.0-rc F-37 插件系统: 工具插件 SPI。
 * <p>
 * 第三方以 JAR 形式实现本接口, 放入 {@code agent.v3.plugin.dir} (默认 ./plugins) 目录,
 * 经 {@code META-INF/services/com.dcdr.agent.tool.plugin.ToolPlugin} 声明 (标准 ServiceLoader),
 * 由 {@link PluginLoader} 启动时扫描加载并注册到 {@code ToolRegistry}。
 * <p>
 * 示例实现见 {@code docs/plugin-demo.md} 的插件骨架。
 */
public interface ToolPlugin {

    /** 插件名 (唯一标识, 展示用) */
    String getName();

    /** 插件版本 */
    String getVersion();

    /** 插件作者 (展示用, 可空) */
    default String getAuthor() { return ""; }

    /**
     * 初始化: 读取上下文配置 (平台注入), 完成插件内部准备。
     * 抛异常则该插件加载失败 (不影响其他插件与主程序)。
     */
    void initialize(PluginContext context);

    /** 插件提供的工具列表 */
    List<PluginTool> getTools();

    /** 卸载回调 (插件重载/停机时调用, 释放资源) */
    default void destroy() { }

    /**
     * 插件工具定义: 由插件声明 name/description/参数 JSON Schema 与执行函数。
     */
    interface PluginTool {
        /** 工具名 (注册到 ToolRegistry 的唯一标识, 建议加插件前缀避免冲突) */
        String name();

        /** 工具描述 (给 LLM 看) */
        String description();

        /**
         * 参数 JSON Schema 字符串 (OpenAI 风格 {"type":"object","properties":{...}})。
         * 无参数工具可返回 null。
         */
        default String parameterSchema() { return null; }

        /** 是否允许在流式对话中被调用 */
        default boolean supportsStreaming() { return false; }

        /** 执行工具: params 为参数 Map, 返回纯文本结果 */
        String execute(Map<String, Object> params);
    }

    /** 函数式便捷构造 (供插件内部 lambda 使用) */
    static PluginTool tool(String name, String description, String schema,
                           boolean supportsStreaming, Function<Map<String, Object>, String> executor) {
        return new PluginTool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public String parameterSchema() { return schema; }
            @Override public boolean supportsStreaming() { return supportsStreaming; }
            @Override public String execute(Map<String, Object> params) { return executor.apply(params); }
        };
    }
}
