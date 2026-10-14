package com.dcdr.agent.agent.memory.longterm;

import java.util.Locale;

/**
 * 长期记忆的写入来源 (V3.1)。
 *
 * <p><b>为什么要区分</b>: 记忆现在有三条写入路径, 其中两条都是"用户没亲手打字"的 ——
 * 用户会对着一条自己没写过的记忆发懵, 也看不出"我让 AI 记住的那条到底记下来没有"。
 * 把来源标出来, 这两件事都能一眼回答; 否则"AI 自动提炼"和"我要求的"混在同一个列表里,
 * 前者看起来像是后者失败了。
 *
 * <p>与 {@code scope}(写给谁看) / {@code status}(还在不在用) <b>正交</b>, 三者互不替代。
 */
public enum MemoryOrigin {

    /** 用户在记忆页手动新增 —— 最没有歧义的一条, 界面上不必额外标注 */
    MANUAL("手动新增"),

    /** 用户在对话里明确要求记录(经 memory 工具写入) —— 即"我让它记住的" */
    REQUESTED("你要求记的"),

    /** 平台自动抽取(每 N 轮跑一次) —— 即"AI 自己提炼的" */
    AUTO("AI 自动提炼");

    private final String label;

    MemoryOrigin(String label) {
        this.label = label;
    }

    /** 界面展示用短名 */
    public String label() {
        return label;
    }

    /** 解析: 未知/空一律返回 null 而不抛异常 —— 该列可空, 存量数据正是这种情况 */
    public static MemoryOrigin from(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 归因(含存量数据兜底)。
     * <p>{@code source_kind} 是后加的列, 在此之前写入的行没有来源值。兜底规则用
     * {@code sourceSessionId}: 有来源会话 = 由 AI 在对话中写入(那时只有自动抽取这一条链路),
     * 没有 = 用户在记忆页手动新增。
     * <p>刻意<b>不做数据回填</b>: 回填等于把"当时大概是这样"写成"确定是这样", 而读取侧
     * 这条规则本来就是确定的 —— 老数据不需要猜也能显示正确。
     *
     * @return 永不为 null
     */
    public static MemoryOrigin resolve(String sourceKind, String sourceSessionId) {
        MemoryOrigin parsed = from(sourceKind);
        if (parsed != null) return parsed;
        return (sourceSessionId != null && !sourceSessionId.isBlank()) ? AUTO : MANUAL;
    }
}
