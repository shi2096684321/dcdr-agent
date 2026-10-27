package com.dcdr.agent.workspace;

import java.util.function.Supplier;

/**
 * 工作区工具的 {@link ThreadLocal} 上下文(V3.3 F-49)。
 * <p>
 * 工具接口 {@code execute(params)} 只能拿到模型给的参数, 而"当前是谁、在哪个会话、操作哪个工作区"
 * 必须由编排层注入 —— 与 {@code SandboxContextHolder} / {@code KnowledgeSearchContextHolder}
 * 同一模式, 由 {@code AgentOrchestrator} 在工具执行前 {@code set}, finally 里 {@code clear}。
 * <p>
 * <b>为什么不能让模型传 workspaceId</b>: 一旦 workspaceId 来自模型参数, 它就能把改动写进别的工作区、
 * 甚至尝试越权访问; 撤回范围也会随之失效。授权档位同理 —— 只由用户在界面上改。
 * <p>
 * <b>线程安全</b>: 工具在 {@code boundedElastic} 线程同步执行, 注入与读取同线程;
 * 跨异步边界读到 null 时工具返回友好提示而不是抛异常(fail-closed, 不误放行)。
 */
public final class WorkspaceContextHolder {

    /** 单次工具执行的上下文; workspaceId 懒解析后回填, 避免每轮都查库 */
    public static final class Context {
        private final String userId;
        private final String sessionId;
        private volatile String workspaceId;
        /**
         * 工作区归属人(= 会话的主人), 与 {@link #userId} 可能<b>不同</b>:
         * admin 打开别人的会话时 userId 是 admin、ownerId 是会话主人。工作区授权该按后者判。
         */
        private volatile String ownerId;

        Context(String userId, String sessionId, String workspaceId) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.workspaceId = workspaceId;
        }

        public String userId() {
            return userId;
        }

        public String sessionId() {
            return sessionId;
        }

        public String workspaceId() {
            return workspaceId;
        }

        void workspaceId(String id) {
            this.workspaceId = id;
        }

        public String ownerId() {
            return ownerId;
        }

        void ownerId(String id) {
            this.ownerId = id;
        }
    }

    private static final ThreadLocal<Context> CTX = new ThreadLocal<>();

    /** 编排层注入: 只知道"谁、哪个会话", 工作区由会话绑定关系懒解析 */
    public static void set(String userId, String sessionId) {
        CTX.set(new Context(userId, sessionId, null));
    }

    public static Context current() {
        return CTX.get();
    }

    public static String userId() {
        Context c = CTX.get();
        return c != null ? c.userId() : null;
    }

    public static String sessionId() {
        Context c = CTX.get();
        return c != null ? c.sessionId() : null;
    }

    public static String workspaceId() {
        Context c = CTX.get();
        return c != null ? c.workspaceId() : null;
    }

    /** 懒解析结果回填(只在当前线程有效) */
    public static void workspaceId(String id) {
        Context c = CTX.get();
        if (c != null) c.workspaceId(id);
    }

    /** 工作区归属人(会话主人); 未解析时为 null */
    public static String ownerId() {
        Context c = CTX.get();
        return c != null ? c.ownerId() : null;
    }

    /** 懒解析时一并回填归属人 */
    public static void ownerId(String id) {
        Context c = CTX.get();
        if (c != null) c.ownerId(id);
    }

    /** 在指定上下文内执行(单测与非编排调用点用) */
    public static <T> T callWith(String userId, String sessionId, Supplier<T> body) {
        Context previous = CTX.get();
        set(userId, sessionId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                CTX.set(previous);
            }
        }
    }

    /** 清除上下文(必须在工具执行 finally 中调用, 防止线程池复用导致串号) */
    public static void clear() {
        CTX.remove();
    }

    private WorkspaceContextHolder() {
    }
}
