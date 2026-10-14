package com.dcdr.agent.agent.memory.longterm;

/**
 * 长期记忆工具的 ThreadLocal 上下文。
 * <p>
 * 工具的 {@code execute(params)} 只能拿到模型给的参数, 拿不到"当前是谁在问、在哪次会话里问",
 * 而写记忆这两样都缺不得: userId 决定写到谁名下, sessionId 用于溯源(记忆页据此标出
 * "这条是 AI 从对话里记的")。
 * <p>
 * 与 {@link com.dcdr.agent.rag.tool.KnowledgeSearchContextHolder} 同一套做法: 在
 * {@code AgentOrchestrator#executeTools} 订阅时注入, 执行后 finally 清除。
 * <p>
 * <b>线程安全</b>: 工具在同一个 boundedElastic 线程上同步执行, ThreadLocal 生命周期与
 * 单次工具往返一致, 不会跨线程。
 */
public final class MemoryContextHolder {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    /** 注入当前请求的用户与会话 */
    public static void set(String userId, String sessionId) {
        USER_ID.set(userId);
        SESSION_ID.set(sessionId);
    }

    /** 当前用户; 未注入(如认证关闭模式)返回 null */
    public static String getUserId() {
        return USER_ID.get();
    }

    /** 当前会话; 未注入返回 null */
    public static String getSessionId() {
        return SESSION_ID.get();
    }

    /** 清除上下文(必须在 executeTools 的 finally 中调用, 防止线程池线程复用导致串号) */
    public static void clear() {
        USER_ID.remove();
        SESSION_ID.remove();
    }

    private MemoryContextHolder() {}
}
