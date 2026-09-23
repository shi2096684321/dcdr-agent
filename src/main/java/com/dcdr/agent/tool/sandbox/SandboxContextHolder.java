package com.dcdr.agent.tool.sandbox;

/**
 * 代码执行沙箱的 {@link ThreadLocal} 上下文(V3.1 F-43)。
 * <p>
 * 沙箱工具执行时需要知道<b>当前用户与会话</b>(审计留痕: 谁在哪个会话执行了什么代码),
 * 但工具接口 {@code execute(params)} 只能拿到 LLM 给的参数。
 * 因此由 {@link com.dcdr.agent.agent.AgentOrchestrator} 在工具执行前注入,
 * 执行后清除 —— 与 {@link com.dcdr.agent.rag.tool.KnowledgeSearchContextHolder} 同一模式。
 * <p>
 * <b>线程安全</b>: 工具在 {@code boundedElastic} 线程同步执行, 注入与读取在同一线程,
 * ThreadLocal 生命周期与单次工具往返一致。跨异步边界读取为空时,
 * 审计以 {@code null} 用户落库(不填充, fail-closed), 不阻塞功能。
 *
 * @see com.dcdr.agent.tool.sandbox.CodeInterpreterTool
 */
public final class SandboxContextHolder {

    /** 单次工具执行的上下文(不可变) */
    public record Context(String userId, String sessionId) {
    }

    private static final ThreadLocal<Context> CTX = new ThreadLocal<>();

    /** 注入当前用户与会话(工具执行前调用) */
    public static void set(String userId, String sessionId) {
        CTX.set(new Context(userId, sessionId));
    }

    /** 当前用户 ID, 未设置返回 null */
    public static String userId() {
        Context ctx = CTX.get();
        return ctx != null ? ctx.userId() : null;
    }

    /** 当前会话 ID, 未设置返回 null */
    public static String sessionId() {
        Context ctx = CTX.get();
        return ctx != null ? ctx.sessionId() : null;
    }

    /** 清除上下文(必须在工具执行 finally 中调用, 防止线程池复用导致身份串号) */
    public static void clear() {
        CTX.remove();
    }

    private SandboxContextHolder() {
    }
}
