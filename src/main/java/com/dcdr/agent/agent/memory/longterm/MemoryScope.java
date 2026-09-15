package com.dcdr.agent.agent.memory.longterm;

/**
 * 长期记忆作用域 (V3.1)。
 * <p>
 * 采用「用户为主干 + Agent 为叠加维度」的模型, 而非两套并列的记忆体系:
 * 绝大多数所谓"Agent 记忆"本质仍是<b>某个用户在该 Agent 下的偏好</b>, 因此
 * {@code user_id} 恒为必填主干, {@code agent_id} 只是可选的作用域收窄维度。
 * <p>
 * 召回时两级合并, 冲突时<b>局部优先</b>: {@link #AGENT} 覆盖 {@link #USER}。
 */
public enum MemoryScope {

    /** 用户级: {@code agent_id} 为空, 跨 Agent 通用画像, 更换 Agent 不丢失 */
    USER("用户级记忆"),

    /** Agent 级: {@code agent_id} 非空, 该 Agent 专属习惯 */
    AGENT("Agent 级记忆");

    private final String label;

    MemoryScope(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 解析作用域名, 非法值抛异常 (避免静默落库错误枚举) */
    public static MemoryScope from(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("记忆作用域不能为空 (可选: USER / AGENT)");
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的记忆作用域: " + name + " (可选: USER / AGENT)");
        }
    }
}
