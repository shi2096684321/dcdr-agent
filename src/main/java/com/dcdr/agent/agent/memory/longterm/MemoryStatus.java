package com.dcdr.agent.agent.memory.longterm;

/**
 * 长期记忆状态 (V3.1)。
 * <p>
 * 删除采用软删除: 用户删除记忆后条目仍保留在库中, 但不再参与召回 —— 便于审计与误删恢复。
 */
public enum MemoryStatus {

    /** 活跃: 参与召回 */
    ACTIVE("活跃"),

    /** 归档: 不参与召回, 但保留数据 (如已过期的时效性记忆) */
    ARCHIVED("已归档"),

    /** 已删除: 用户主动删除, 不参与召回 */
    DELETED("已删除");

    private final String label;

    MemoryStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 解析状态名, 非法值抛异常 */
    public static MemoryStatus from(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("记忆状态不能为空 (可选: ACTIVE / ARCHIVED / DELETED)");
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的记忆状态: " + name + " (可选: ACTIVE / ARCHIVED / DELETED)");
        }
    }
}
