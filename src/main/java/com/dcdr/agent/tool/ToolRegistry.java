package com.dcdr.agent.tool;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心 - 管理所有可用工具
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        log.info("注册工具: {}", tool.getName());
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public Collection<Tool> getAllTools() {
        return tools.values();
    }

    /** 执行工具 */
    public String execute(String toolName, Map<String, Object> params) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "工具不存在: " + toolName;
        }
        try {
            log.debug("执行工具: {}, 参数: {}", toolName, params);
            return tool.execute(params);
        } catch (Exception e) {
            log.error("工具执行失败: {}", toolName, e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 执行工具并返回结构化结果(含来源信息, 如联网搜索的 references)。
     * 相比 {@link #execute} 仅返回纯文本, 此方法保留 {@link ToolInfo#getSources()} 等扩展字段。
     */
    public ToolInfo executeInfo(String toolName, Map<String, Object> params) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return ToolInfo.text("工具不存在: " + toolName);
        }
        try {
            log.debug("执行工具: {}, 参数: {}", toolName, params);
            return tool.executeWithInfo(params);
        } catch (Exception e) {
            log.error("工具执行失败: {}", toolName, e);
            return ToolInfo.text("工具执行失败: " + e.getMessage());
        }
    }

    /** 判断是否有已注册工具 */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /** 把单个 Tool 包装成 OpenAI function calling 的 tools 元素 */
    private Map<String, Object> toDefinition(Tool tool, ObjectMapper mapper) throws Exception {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.getName());
        function.put("description", tool.getDescription());
        Object parameters = mapper.readValue(tool.getParameterSchema(), Map.class);
        function.put("parameters", parameters);
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "function");
        def.put("function", function);
        return def;
    }

    /**
     * 返回全部已注册工具的 OpenAI tools 定义(供非流式场景 / agent 模式使用)。
     * 这是工具定义的唯一来源: 每个 Tool 的 name/description/parameterSchema 在此统一包装成标准结构。
     */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (Tool tool : tools.values()) {
            try {
                defs.add(toDefinition(tool, mapper));
            } catch (Exception e) {
                log.warn("工具 {} 的参数 schema 解析失败, 已跳过", tool.getName(), e);
            }
        }
        return defs;
    }

    /**
     * 仅返回标记 supportsStreaming()=true 的工具定义, 供流式对话(chat)注入。
     * 避免把 Weather / Calculator 等仅适用于其它场景的工具暴露给流式对话。
     */
    public List<Map<String, Object>> getStreamingToolDefinitions() {
        return getStreamingToolDefinitionsFiltered(null);
    }

    /**
     * V2.3 自定义 Agent 工具白名单过滤版本。
     * <p>
     * 当 {@code allowedToolNames} 为 null/空时, 返回全部 streaming 工具定义 (与
     * {@link #getStreamingToolDefinitions()} 等价); 否则仅返回名称在白名单中的工具。
     * 用于让自定义 Agent 仅暴露其配置中启用的工具子集。
     */
    public List<Map<String, Object>> getStreamingToolDefinitionsFiltered(java.util.List<String> allowedToolNames) {
        boolean filter = allowedToolNames != null && !allowedToolNames.isEmpty();
        List<Map<String, Object>> defs = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (Tool tool : tools.values()) {
            if (!tool.supportsStreaming()) continue;
            if (filter && !allowedToolNames.contains(tool.getName())) continue;
            try {
                defs.add(toDefinition(tool, mapper));
            } catch (Exception e) {
                log.warn("工具 {} 的参数 schema 解析失败, 已跳过", tool.getName(), e);
            }
        }
        return defs;
    }
}
