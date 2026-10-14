package com.dcdr.agent.skill;

/**
 * 技能加载的线程上下文 (V3.1 专家体系)。
 * <p>
 * 与 {@code KnowledgeSearchContextHolder} / {@code ToolResultContextHolder} 同一模式:
 * 工具执行时只能拿到模型给的参数({@code skill_load(name=...)}), 但"当前是谁在问"
 * 必须由编排层在调用工具前注入 —— 否则工具无法按归属过滤技能(可能加载到他人的私有技能)。
 * <p>
 * 由 {@code AgentOrchestrator} 在 {@code executeTools} 前 set, 执行后 finally clear。
 * 跨异步边界(boundedElastic / 线程池)会丢失, 故只在工具执行的同步区间内使用。
 */
public final class SkillContextHolder {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private SkillContextHolder() {
    }

    public static void set(String userId) {
        CURRENT_USER.set(userId);
    }

    /** @return 当前请求用户 ID; 未注入时为 null(视为匿名, 只能加载内置/公开技能) */
    public static String get() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
