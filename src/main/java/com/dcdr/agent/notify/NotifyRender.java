package com.dcdr.agent.notify;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 各渠道共用的文本处理工具（截断与格式化）。
 * <p>
 * 消息模板已抽到 {@link NotifyTemplate}（支持变量插值），这里只保留与"长度"和"时间格式"相关的
 * 纯函数 —— 两个渠道的差异主要在计长单位（企微按字节、钉钉按字符），故两套截断并存。
 */
final class NotifyRender {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private NotifyRender() {
    }

    /**
     * 按 UTF-8 <b>字节</b>截断, 且保证不切断多字节字符。
     * <p>
     * UTF-8 续字节的高两位固定为 {@code 10}, 一旦落在续字节上说明切口在字符中间,
     * 必须向前回退到该字符的首字节。直接 {@code substring} 会产生半个汉字/半个 emoji,
     * 渠道侧会显示成乱码方块, 而且不报错 —— 只有人肉看才能发现。
     */
    static String truncateUtf8(String text, int maxBytes, String suffix) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int suffixBytes = suffix == null ? 0 : suffix.getBytes(StandardCharsets.UTF_8).length;
        int budget = maxBytes - suffixBytes;
        if (budget <= 0) {
            // 上限小到装不下提示语时, 宁可不加提示也不要超限
            budget = maxBytes;
            suffix = "";
        }
        int end = budget;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8) + suffix;
    }

    /**
     * 按<b>字符</b>截断 (钉钉等按字符计长的渠道用)。
     * <p>注意避开代理对: 直接 substring 会把 emoji 切成两半, 留下一个孤立的高位代理。
     */
    static String truncateChars(String text, int maxChars, String suffix) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        int budget = maxChars - (suffix == null ? 0 : suffix.length());
        if (budget <= 0) {
            budget = maxChars;
            suffix = "";
        }
        int end = budget;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + suffix;
    }

    /**
     * 生成概览：取前 {@code maxChars} 个字符，尽量在句末/换行处断开。
     * <p>
     * 为什么不硬切：抓取类任务的输出是成段的文字，从句子中间截断会出现
     * "……同比增长 12.4%，主要得" 这种半句话，读起来像内容坏了。
     * 允许从断点回退最多 60 字去找标点，找不到再硬切。
     */
    static String summarize(String text, int maxChars) {
        if (text == null) return "";
        String t = text.strip();
        if (maxChars <= 0 || t.length() <= maxChars) return t;

        int end = maxChars;
        int floor = Math.max(0, maxChars - 60);
        for (int i = maxChars; i > floor; i--) {
            char c = t.charAt(i - 1);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                    || c == '.' || c == '!' || c == '?' || c == ';') {
                end = i;
                break;
            }
        }
        // 不要留下孤立的代理，否则 emoji 会变成方块
        if (end > 0 && Character.isHighSurrogate(t.charAt(end - 1))) end--;
        return t.substring(0, end).stripTrailing() + "…";
    }

    static String orDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    static String formatTime(LocalDateTime t) {
        return (t == null ? LocalDateTime.now() : t).format(TIME_FMT);
    }

    static String formatDuration(long ms) {
        if (ms < 0) return "-";
        if (ms < 1000) return ms + " ms";
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
        return String.format(Locale.ROOT, "%d 分 %d 秒", ms / 60_000, (ms % 60_000) / 1000);
    }
}
