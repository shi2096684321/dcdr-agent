package com.dcdr.agent.agent.memory.longterm;

/**
 * 用户对「AI 自动记忆」的表态 —— 三态。
 *
 * <p><b>为什么是三态而不是布尔</b>: 用户表那列为 {@code null} 时表示<b>从未表态</b>, 此时跟随部署配置
 * {@code agent.memory.auto-extract.user-default}。若把它压成布尔, 就无法区分
 * "用户明确关闭"与"用户没选过", 于是部署方日后调整默认值时, 从未表态的人会被当成明确拒绝而拿不到新默认
 * (见 {@code AutoExtractPreference} 的类注释)。
 *
 * <p><b>为什么收敛到枚举</b>: 取值此前是 {@code AutoExtractPreference} 里的三个 {@code static final String},
 * 对前端却仍是裸字符串(界面直接比 {@code === 'default'} 判断该显示"默认"还是"恢复默认")。
 * 取值、中文名、以及 {@code Boolean -> 三态} 的映射收在一处后, 前端改为消费后端下发的
 * {@link #value()} 与 {@link #label()}, 不必再自己拼一份。
 */
public enum AutoExtractChoice {

    /** 用户明确开启 */
    ON("on", "已开启"),

    /** 用户明确关闭 —— 立即停写, 已有记忆原样保留 */
    OFF("off", "已关闭"),

    /** 从未表态, 跟随部署默认 */
    DEFAULT("default", "默认");

    /** 下发 / 落库的取值; 与 enum 名分开写死: 改名即等于改契约 */
    private final String value;
    /** 界面展示用短名 */
    private final String label;

    AutoExtractChoice(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    /** 用户表里的可空布尔 → 三态; {@code null} 即未表态 */
    public static AutoExtractChoice of(Boolean enabled) {
        if (enabled == null) return DEFAULT;
        return enabled ? ON : OFF;
    }

    /** 解析: 未知/空一律返回 {@link #DEFAULT} —— 认不出就当"没表过态", 与列可空的语义一致 */
    public static AutoExtractChoice from(String value) {
        if (value == null || value.isBlank()) return DEFAULT;
        for (AutoExtractChoice c : values()) {
            if (c.value.equalsIgnoreCase(value.trim())) return c;
        }
        return DEFAULT;
    }
}
