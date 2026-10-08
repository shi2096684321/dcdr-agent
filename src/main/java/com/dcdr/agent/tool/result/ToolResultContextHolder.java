package com.dcdr.agent.tool.result;

/**
 * V3.1 F-47 工具结果治理的 ThreadLocal 上下文。
 * <p>
 * 工具结果落盘需要按<b>会话</b>分目录, 而工具自身的 {@code execute(params)} 只能拿到模型给的参数 ——
 * 与 {@link com.dcdr.agent.rag.tool.KnowledgeSearchContextHolder} /
 * {@link com.dcdr.agent.tool.sandbox.SandboxContextHolder} 同一模式:
 * 在 {@code AgentOrchestrator.executeTools} 执行前注入, 工具执行时读取, 执行后清除。
 * <p>
 * <b>安全意义</b>: 落盘文件按会话目录隔离, {@code tool_result_read} 只允许读取<b>当前会话</b>目录内的文件,
 * 否则一个"读文件"工具就等于把同主机其他会话的工具结果(可能含私有知识库内容)暴露给任意用户。
 * <p>
 * <b>线程安全</b>: executeTools 在 boundedElastic 线程同步执行, 工具调用同线程,
 * 生命周期与单次工具往返一致 (finally 中 clear, 防线程复用串号)。
 */
public final class ToolResultContextHolder {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    /** 注入当前用户与会话(执行工具前调用) */
    public static void set(String userId, String sessionId) {
        USER_ID.set(userId);
        SESSION_ID.set(sessionId);
    }

    public static String userId() {
        return USER_ID.get();
    }

    public static String sessionId() {
        return SESSION_ID.get();
    }

    /** 清除上下文(必须在 executeTools 的 finally 中调用) */
    public static void clear() {
        USER_ID.remove();
        SESSION_ID.remove();
    }

    private ToolResultContextHolder() {}
}
