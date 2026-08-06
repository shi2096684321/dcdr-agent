package com.dcdr.agent.tool;

import java.util.Map;

/**
 * 工具接口 - Agent 可调用的外部能力
 */
public interface Tool {

    /** 工具名称(唯一标识) */
    String getName();

    /** 工具描述(给 LLM 看的, 告诉它什么时候用这个工具) */
    String getDescription();

    /** 参数 JSON Schema(给 LLM 看的, 描述需要哪些参数) */
    String getParameterSchema();

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
