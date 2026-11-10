package com.dcdr.agent.agent.memory.strategy;

/**
 * 上下文压缩事件类型(V3.5)。
 *
 * <p><b>为什么单独建事件表, 而不是只看摘要正文</b>: 摘要只表达"现在记住的是哪一版",
 * 表达不了"为什么这一轮没压/为什么压失败/为什么摘要被作废"。而这三件事恰恰是排查
 * "会话突然忘事"时最先要问的, 且都发生在摘要之外 —— 不落一条事件, 它们只存在于服务端
 * 文件日志里, 部署后基本无从查。
 *
 * <p>取值与 {@code context_compactions.event_type} 列共用这一份枚举, 前端展示名由
 * {@link #labelOf(String)} 给出, 避免前后端各写一份中文。
 */
public enum CompactionEvent {

    /** 摘要生成成功并已写回会话 */
    COMPACTED("compacted", "压缩成功"),
    /** 该压但没压成, 本轮退回滑动窗口(模型失败/超时/返回为空/保留条数不足) */
    DEGRADED("degraded", "降级窗口"),
    /** 摘要覆盖到的边界消息已不存在(用户撤回/删除), 整份摘要被作废 */
    INVALIDATED("invalidated", "摘要作废"),
    /** 用户主动清除摘要 */
    CLEARED("cleared", "用户清除"),
    /** 用户手工改写摘要正文 */
    EDITED("edited", "用户改写");

    private final String code;
    private final String label;

    CompactionEvent(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 库里 code → 中文名; 未知值原样返回(历史/手工插入的数据至少还能看见是什么) */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) return "";
        for (CompactionEvent e : values()) {
            if (e.code.equalsIgnoreCase(code)) return e.label;
        }
        return code;
    }

    /** @return 规范化的 code; 无法识别返回 {@code null}, 由调用方决定报错还是忽略 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (CompactionEvent e : values()) {
            if (e.code.equalsIgnoreCase(raw.trim())) return e.code;
        }
        return null;
    }
}
