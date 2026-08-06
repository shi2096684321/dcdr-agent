package com.dcdr.agent.model.provider;

import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.ChatChunk;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话模型提供者接口
 * <p>
 * 所有对话模型(OpenAI/智谱/通义等)实现此接口,
 * 由 {@link ModelRegistry} 统一管理。
 */
public interface ChatModelProvider {

    /** 提供者唯一标识, 如 "openai-chat" */
    String getName();

    /** 模型类型 */
    ModelType getType();

    /** 显示标签 */
    String getLabel();

    /** 提供商名称 */
    String getProvider();

    /** 同步调用
     * @param request 当前请求(包含本次用户消息)
     * @param history 对话历史(不含本次用户消息), 用于构造多轮上下文 */
    ChatResponse invoke(ChatRequest request, List<Message> history);

    /** 流式调用(返回响应流)
     * @param request 当前请求(包含本次用户消息)
     * @param history 对话历史(不含本次用户消息), 用于构造多轮上下文 */
    Flux<ChatChunk> stream(ChatRequest request, List<Message> history);

    /**
     * 流式调用(显式控制是否追加当前 user 消息)。
     * 工具循环回灌轮使用 includeCurrentUser=false(上下文已在 history 中)。
     * 默认实现转调两参版本, 对新老 Provider 兼容。
     */
    default Flux<ChatChunk> stream(ChatRequest request, List<Message> history, boolean includeCurrentUser) {
        return stream(request, history);
    }

    /** 是否支持多模态(图片输入) */
    default boolean supportsVision() {
        return false;
    }

    /** 是否支持函数调用(工具) */
    default boolean supportsFunctionCalling() {
        return false;
    }
}
