package com.dcdr.agent.agent.memory.longterm;

/**
 * 长期记忆类型 (V3.1)。
 * <p>
 * 分类借鉴 CodeBuddy Typed Memory 的四类划分 (user / feedback / project / reference) 并做本地化扩展,
 * 目的是让"什么该常驻注入、什么该按需触发"由类型决定, 而不是靠每轮重新判断:
 * <ul>
 *   <li>常驻类 ({@link #PROFILE} / {@link #PREFERENCE} / {@link #FEEDBACK}) —— 条数少、价值高、与提问无关也要生效;</li>
 *   <li>触发类 ({@link #PROJECT} / {@link #REFERENCE}) —— 只在 {@code topic} / {@code tags} 命中当轮提问时注入。</li>
 * </ul>
 */
public enum MemoryType {

    /** 用户画像: 角色、技术栈、知识背景 (常驻) */
    PROFILE("用户画像", true),

    /** 偏好: 回答风格、格式、语言习惯 (常驻) */
    PREFERENCE("偏好", true),

    /** 纠正: 用户对 Agent 行为的反馈与约束, 优先级最高 (常驻) */
    FEEDBACK("纠正", true),

    /** 项目: 进行中的工作、目标、决策 (条件触发) */
    PROJECT("项目", false),

    /** 资源指引: 外部系统与资源的定位信息 (条件触发) */
    REFERENCE("资源指引", false);

    private final String label;
    private final boolean resident;

    MemoryType(String label, boolean resident) {
        this.label = label;
        this.resident = resident;
    }

    public String label() {
        return label;
    }

    /** 是否常驻注入: true 表示每轮都参与注入候选, 不受 topic 命中限制 */
    public boolean resident() {
        return resident;
    }

    /**
     * 解析类型名, 非法值抛异常。
     * <p>别名容错: 兼容小写与连字符写法 (如 {@code user-profile}), 便于前端/外部导入。
     */
    public static MemoryType from(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("记忆类型不能为空");
        }
        String normalized = name.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的记忆类型: " + name
                    + " (可选: " + java.util.Arrays.toString(values()) + ")");
        }
    }
}
