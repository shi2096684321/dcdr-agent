package com.dcdr.agent.tool;

import com.dcdr.agent.model.langchain4j.JsonSchemaConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心 - 管理所有可用工具
 * <p>
 * V3.1 框架化: 注册时即把工具定义转换为框架规范格式 {@link ToolSpecification} 并缓存
 * (schema 解析/转换只发生一次, 且坏 schema 在启动期暴露而非对话期),
 * LLM 工具定义有两个出口:
 * <ul>
 *   <li>{@link #getStreamingToolSpecificationsFiltered}: 框架原生格式(LangChain4j Provider 直连, 零转换)</li>
 *   <li>{@link #getToolDefinitions}/{@link #getStreamingToolDefinitionsFiltered}: OpenAI 兼容 Map
 *       (legacy 手写 Provider 回退路径, 由缓存 spec 反向派生或保留动态工具原始 JSON)</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    /** 注册期构建的工具规范格式定义(框架原生) */
    private final Map<String, ToolSpecification> specifications = new ConcurrentHashMap<>();
    /** 注册期构建的 OpenAI 兼容 Map 定义(legacy Provider 回退路径) */
    private final Map<String, Map<String, Object>> openAiDefinitions = new ConcurrentHashMap<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        try {
            ToolSpecification spec = buildSpecification(tool);
            if (spec != null) {
                specifications.put(tool.getName(), spec);
                openAiDefinitions.put(tool.getName(), buildOpenAiDefinition(tool, spec));
                log.info("注册工具: {}", tool.getName());
            } else {
                log.warn("工具 {} 的参数 schema 不可用, 已注册执行但不暴露给 LLM", tool.getName());
            }
        } catch (Exception e) {
            // schema 构建失败: 工具仍可按名执行(workflow 等路径), 仅不暴露给 LLM
            log.error("工具 {} 的 ToolSpecification 构建失败, 已注册执行但不暴露给 LLM", tool.getName(), e);
            specifications.remove(tool.getName());
            openAiDefinitions.remove(tool.getName());
        }
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

    /**
     * 构建工具的框架规范格式定义。
     * <p>
     * schema 来源优先级: {@link Tool#getParameters()}(静态工具, 框架类型直供) →
     * {@link Tool#getParameterSchema()} JSON 解析(动态工具, 如 MCP inputSchema)。
     * 两者均缺省视为无参数工具。
     */
    private ToolSpecification buildSpecification(Tool tool) throws Exception {
        JsonObjectSchema parameters = tool.getParameters();
        if (parameters == null) {
            String json = tool.getParameterSchema();
            if (json != null && !json.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> schemaMap = MAPPER.readValue(json, Map.class);
                parameters = JsonSchemaConverter.toObjectSchema(schemaMap);
            }
        }
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(tool.getName())
                .description(tool.getDescription());
        if (parameters != null) {
            builder.parameters(parameters);
        }
        return builder.build();
    }

    /**
     * 构建 OpenAI 兼容 function 定义(legacy Provider 回退路径)。
     * <p>
     * 动态 schema 工具保留原始 JSON 解析结果(避免 spec 往返转换损失 $ref 等特性);
     * 静态工具(schema 仅存在于框架类型)由缓存 spec 反向派生。
     */
    private Map<String, Object> buildOpenAiDefinition(Tool tool, ToolSpecification spec) throws Exception {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.getName());
        function.put("description", tool.getDescription());

        String json = tool.getParameterSchema();
        if (json != null && !json.isBlank()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = MAPPER.readValue(json, Map.class);
            function.put("parameters", parameters);
        } else if (spec.parameters() != null) {
            function.put("parameters", JsonSchemaConverter.fromObjectSchema(spec.parameters()));
        }

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "function");
        def.put("function", function);
        return def;
    }

    /**
     * 返回全部已注册工具的框架原生 ToolSpecification(V3.1 主路径,
     * LangChain4j Provider 直接消费, 无 Map 往返转换)。
     */
    public List<ToolSpecification> getToolSpecifications() {
        return getStreamingToolSpecificationsFiltered(null, false);
    }

    /**
     * 仅返回标记 supportsStreaming()=true 的工具规范, 供流式对话(chat)注入。
     */
    public List<ToolSpecification> getStreamingToolSpecifications() {
        return getStreamingToolSpecificationsFiltered(null);
    }

    /**
     * V2.3 自定义 Agent 工具白名单过滤版本的流式工具规范。
     * <p>
     * 当 {@code allowedToolNames} 为 null/空时, 返回全部 streaming 工具规范;
     * 否则仅返回名称在白名单中的工具。
     */
    public List<ToolSpecification> getStreamingToolSpecificationsFiltered(List<String> allowedToolNames) {
        return getStreamingToolSpecificationsFiltered(allowedToolNames, true);
    }

    private List<ToolSpecification> getStreamingToolSpecificationsFiltered(
            List<String> allowedToolNames, boolean streamingOnly) {
        boolean filter = allowedToolNames != null && !allowedToolNames.isEmpty();
        List<ToolSpecification> result = new ArrayList<>();
        for (Map.Entry<String, ToolSpecification> e : specifications.entrySet()) {
            if (streamingOnly && !tools.get(e.getKey()).supportsStreaming()) continue;
            if (filter && !allowedToolNames.contains(e.getKey())) continue;
            result.add(e.getValue());
        }
        return result;
    }

    /**
     * 返回全部已注册工具的 OpenAI tools 定义(legacy 手写 Provider 兼容路径)。
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return getStreamingToolSpecificationsFiltered(null, false).stream()
                .map(spec -> openAiDefinitions.get(spec.name()))
                .filter(Objects::nonNull)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * 仅返回标记 supportsStreaming()=true 的工具定义, 供流式对话(chat)注入。
     */
    public List<Map<String, Object>> getStreamingToolDefinitions() {
        return getStreamingToolDefinitionsFiltered(null);
    }

    /**
     * V2.3 自定义 Agent 工具白名单过滤版本(OpenAI 兼容格式)。
     * <p>
     * 当 {@code allowedToolNames} 为 null/空时, 返回全部 streaming 工具定义 (与
     * {@link #getStreamingToolDefinitions()} 等价); 否则仅返回名称在白名单中的工具。
     */
    public List<Map<String, Object>> getStreamingToolDefinitionsFiltered(List<String> allowedToolNames) {
        boolean filter = allowedToolNames != null && !allowedToolNames.isEmpty();
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Map.Entry<String, Tool> e : tools.entrySet()) {
            if (!e.getValue().supportsStreaming()) continue;
            if (filter && !allowedToolNames.contains(e.getKey())) continue;
            Map<String, Object> def = openAiDefinitions.get(e.getKey());
            if (def != null) {
                defs.add(def);
            }
        }
        return defs;
    }
}
