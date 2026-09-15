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

    /**
     * 生成"本次实际发给上游的请求体"快照(JSON 文本), 供调用链日志忠实归档。
     * <p>
     * <b>为什么由 Provider 提供, 而不是编排层自己拼</b>: 编排层持有的只是"业务视角的历史",
     * 而真正发给上游的 messages 与参数由 Provider 组装 —— 它会过滤空消息、改写生图占位、
     * 追加工具定义与厂商参数(如 {@code enable_thinking})。两处各拼一份必然随时间漂移,
     * 因此以 Provider 的组装结果为准。
     * <p>
     * <b>实现约束</b>: 必须与实际发送逻辑<b>同源</b>(复用同一个构建方法), 不得另行拼装 ——
     * 否则等于把漂移从一个地方搬到另一个地方。默认返回 {@code null} 表示不提供快照。
     *
     * @param request            当前请求
     * @param history            对话历史
     * @param includeCurrentUser 是否把当前用户消息追加进请求(须与真实调用一致)
     * @param stream             true=流式调用, false=同步调用
     * @return 请求体 JSON; 不提供或生成失败时返回 null(调用方须容忍 null)
     */
    default String describeWireRequest(ChatRequest request, List<Message> history,
                                       boolean includeCurrentUser, boolean stream) {
        return null;
    }
}
