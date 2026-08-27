package com.dcdr.agent.tool;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * 工具接口 - Agent 可调用的外部能力
 */
public interface Tool {

    /** 工具名称(唯一标识) */
    String getName();

    /** 工具描述(给 LLM 看的, 告诉它什么时候用这个工具) */
    String getDescription();

    /**
     * 参数 schema(框架原生类型, V3.1): 类型安全构造, 注册时由 ToolRegistry
     * 缓存为规范格式 {@code ToolSpecification}。
     * <p>
     * 静态 schema 工具(参数固定)优先实现此方法(流式 builder, 编译期校验);
     * 动态 schema 工具(MCP 服务端下发 / 配置驱动)可返回 null,
     * 由 ToolRegistry 解析 {@link #getParameterSchema()} JSON 字符串。
     */
    default JsonObjectSchema getParameters() {
        return null;
    }

    /**
     * 参数 JSON Schema 字符串(动态 schema 工具的数据源, 如 MCP inputSchema)。
     * 静态工具已迁移到 {@link #getParameters()}, 此方法仅动态工具需要覆盖。
     */
    default String getParameterSchema() {
        return null;
    }

    /** 执行工具(返回纯文本) */
    String execute(Map<String, Object> params);

    /**
     * 执行工具并返回结构化结果(含来源等扩展信息)。
     * 默认实现用纯文本结果包装一个 {@link ToolInfo}。
     */
    default ToolInfo executeWithInfo(Map<String, Object> params) {
        return ToolInfo.text(execute(params));
    }

    /**
     * 是否允许在流式对话(chat)中被模型调用, 默认 false。
     * 用于避免把仅适用于其它场景(如创作 / agent)的工具暴露给流式对话。
     */
    default boolean supportsStreaming() {
        return false;
    }
}
