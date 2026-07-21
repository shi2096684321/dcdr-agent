package com.example.agent.tool;

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

    /** 执行工具 */
    String execute(Map<String, Object> params);
}
