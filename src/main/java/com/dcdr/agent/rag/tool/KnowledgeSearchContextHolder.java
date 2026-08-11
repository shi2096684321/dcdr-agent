package com.dcdr.agent.rag.tool;

import java.util.Collections;
import java.util.List;

/**
 * 知识库检索工具的 ThreadLocal 上下文。
 * <p>
 * Agentic RAG 模式下, 工具执行时需要知道当前对话可用的知识库 ID 列表
 * (用户选择的 + 平台级配置的), 但工具的 {@code execute(params)} 只能拿到 LLM 给的参数。
 * 通过 ThreadLocal 在 {@link com.dcdr.agent.agent.AgentOrchestrator#executeTools} 执行前注入,
 * 工具执行时读取, 执行后清除。
 * <p>
 * <b>线程安全</b>: executeTools 在 boundedElastic 线程同步执行, 工具调用也在同一线程,
 * ThreadLocal 生命周期与单次工具往返一致, 不会有跨线程问题。
 */
public final class KnowledgeSearchContextHolder {

    private static final ThreadLocal<List<String>> KB_IDS = new ThreadLocal<>();

    /** 注入当前对话可用的知识库 ID 列表(用户选的 + 平台级) */
    public static void set(List<String> kbIds) {
        KB_IDS.set(kbIds);
    }

    /** 获取当前对话可用的知识库 ID 列表, 未设置返回空列表 */
    public static List<String> get() {
        List<String> ids = KB_IDS.get();
        return ids != null ? ids : Collections.emptyList();
    }

    /** 清除上下文(必须在 executeTools finally 中调用, 防止线程池线程复用导致泄漏) */
    public static void clear() {
        KB_IDS.remove();
    }

    private KnowledgeSearchContextHolder() {}
}
