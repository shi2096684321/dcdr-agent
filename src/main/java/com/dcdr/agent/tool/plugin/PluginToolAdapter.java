package com.dcdr.agent.tool.plugin;

import com.dcdr.agent.tool.Tool;
import com.dcdr.agent.tool.ToolInfo;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * V3.0-rc F-37: 插件工具适配器 —— 把 {@link ToolPlugin.PluginTool} 包装为平台 {@link Tool},
 * 注册进 ToolRegistry 后即可被 LLM 工具循环调用 (与内置工具同链路)。
 */
public class PluginToolAdapter implements Tool {

    private final ToolPlugin.PluginTool delegate;
    private final String pluginName;

    public PluginToolAdapter(ToolPlugin.PluginTool delegate, String pluginName) {
        this.delegate = delegate;
        this.pluginName = pluginName;
    }

    @Override
    public String getName() { return delegate.name(); }

    @Override
    public String getDescription() {
        return "[" + pluginName + "] " + delegate.description();
    }

    @Override
    public String getParameterSchema() { return delegate.parameterSchema(); }

    @Override
    public JsonObjectSchema getParameters() {
        // 动态 schema 工具: 由 ToolRegistry 解析 getParameterSchema() JSON 字符串
        return null;
    }

    @Override
    public boolean supportsStreaming() { return delegate.supportsStreaming(); }

    @Override
    public String execute(Map<String, Object> params) { return delegate.execute(params); }

    @Override
    public ToolInfo executeWithInfo(Map<String, Object> params) {
        return ToolInfo.text(execute(params));
    }
}
