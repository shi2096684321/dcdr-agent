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

    // ===== IM 展示适配：Markdown 表格 → 列表 =====

    /** 表格行：以 {@code |} 开头（GFM 允许省略首尾竖线，但 Agent 产出基本都带） */
    private static final java.util.regex.Pattern TABLE_ROW = java.util.regex.Pattern.compile("^\\s*\\|.*$");

    /** 表头分隔行：只由 {@code - | : 空格} 组成且至少一个 {@code -} */
    private static final java.util.regex.Pattern TABLE_SEP = java.util.regex.Pattern.compile("^[\\s|:\\-]*-[\\s|:\\-]*$");

    private static final java.util.regex.Pattern URL_ONLY = java.util.regex.Pattern.compile("^https?://\\S+$");

    /** 正文里的引用角标 {@code [[1]]}；IM 没有来源卡片，留着只是噪音 */
    private static final java.util.regex.Pattern CITATION = java.util.regex.Pattern.compile("\\[\\[\\d{1,3}]]");

    /** 每行最多拼几个"列名：值"，超过就换行 —— 一列一行的横排会把单行撑到几百字符 */
    private static final int PAIRS_PER_LINE = 3;

    private static final String PAIR_SEP = "｜";

    /**
     * IM 的硬换行（行尾两个空格 + \n）。
     * <p>钉钉官方说明里单 {@code \n} 会被折叠，必须写成「\n 前后各两个空格」才换行；
     * 只写 {@code \n} 时三个字段会连成一行，看起来就是"表格全挤在一起"。
     * 企业微信的 Markdown 同样识别这个写法，因此两边共用。
     */
    private static final String IM_LINE_BREAK = "  \n";

    /**
     * 把正文转成 IM 里能看的样子（目前就是处理 Markdown 表格）。
     * <p>
     * <b>为什么必须转</b>：企微群机器人与钉钉群机器人的 markdown <b>都不支持表格</b>，
     * {@code | 公司 | 岗位 |} 这类内容会被原样当普通文字发出去，
     * 用户看到的就是一堆竖线加下划线分隔行 —— 既占篇幅又没有可读性，
     * 而 300 字概览截断后往往只剩表头和半行数据，看起来"内容特别少"。
     * <p>
     * 转换规则：表头保留为字段名，每个数据行变成「序号 + 第一列加粗」的标题行，
     * 其余列按 {@code 列名：值} 每 {@value #PAIRS_PER_LINE} 组换一行；
     * 纯 URL 的单元格压成 {@code [链接](url)}，省下的字符够多放两条数据。
     * <p>
     * 非表格内容原样保留，所以"结论 + 表格"这种混排也能正确处理。
     */
    static String forIm(String text) {
        if (text == null || text.isEmpty()) return "";
        // 先去掉引用角标: 表格转卡片后第一列会变成公司名, 角标若留在里面会显示成
        // "小满科技（阿里巴巴旗下）[[1]]"; 而 IM 里既没有来源卡片, 也没有可点的 [1]
        text = CITATION.matcher(text).replaceAll("");
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 128);
        int i = 0;
        while (i < lines.length) {
            if (TABLE_ROW.matcher(lines[i]).matches()) {
                int end = i;
                while (end < lines.length && TABLE_ROW.matcher(lines[end]).matches()) end++;
                // 至少 3 行（表头 + 分隔 + 至少一条数据）才当表格，避免误伤普通的竖线文本
                if (end - i >= 3 && TABLE_SEP.matcher(lines[i + 1].trim()).matches()) {
                    appendTable(out, lines, i, end);
                    i = end;
                    continue;
                }
            }
            out.append(lines[i]).append('\n');
            i++;
        }
        return out.toString().stripTrailing();
    }

    private static void appendTable(StringBuilder out, String[] lines, int from, int to) {
        java.util.List<String> header = cells(lines[from]);
        int seq = 0;
        for (int r = from + 2; r < to; r++) {
            java.util.List<String> row = cells(lines[r]);
            if (row.stream().allMatch(String::isBlank)) continue;
            seq++;
            String head = cellAt(row, 0);
            out.append("**").append(seq).append(". ").append(head.isEmpty() ? "(空)" : head).append("**")
                    .append(IM_LINE_BREAK);
            StringBuilder line = new StringBuilder();
            int pairsOnLine = 0;
            int cols = Math.max(header.size(), row.size());
            for (int c = 1; c < cols; c++) {
                String value = cellAt(row, c);
                if (value.isEmpty()) continue;
                String name = c < header.size() ? header.get(c) : ("列" + (c + 1));
                if (pairsOnLine > 0) line.append(PAIR_SEP);
                line.append(name).append("：").append(cellValue(value));
                if (++pairsOnLine >= PAIRS_PER_LINE) {
                    out.append(line).append(IM_LINE_BREAK);
                    line.setLength(0);
                    pairsOnLine = 0;
                }
            }
            if (line.length() > 0) out.append(line).append(IM_LINE_BREAK);
            out.append('\n');
        }
    }

    /** 拆单元格：去掉首尾竖线后按 {@code |} 切；{@code \|} 视为单元格内的字面竖线 */
    private static java.util.List<String> cells(String line) {
        String s = line.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().strip());
        return out;
    }

    private static String cellAt(java.util.List<String> row, int idx) {
        return idx < row.size() ? row.get(idx) : "";
    }

    /** 单元格值的展示形态：裸 URL 压成链接，其余原样 */
    private static String cellValue(String value) {
        if (URL_ONLY.matcher(value).matches()) return "[链接](" + value + ")";
        return value;
    }

    /**
     * 把推送异常转成"能展示也能入库"的简短原因。
     * <p>
     * <b>为什么不直接用 {@code e.getMessage()}</b>: 推送失败的典型原因是 4xx/5xx，
     * 而 {@code WebClientResponseException#getMessage()} 会把完整请求 URI 拼进去 ——
     * Webhook 的 key/token 就在 query 里。这条字符串最终会写进
     * {@code scheduled_tasks.notify_last_error} 并渲染到任务详情页，
     * 等于把机器人凭据打印在页面上（管理员还能看到所有人的）。
     * 所以这里只保留状态码与异常类型。
     */
    static String safeReason(Throwable e) {
        if (e == null) return "未知错误";
        if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            return "HTTP " + ex.getStatusCode().value();
        }
        if (e instanceof java.util.concurrent.TimeoutException) return "请求超时";
        String name = e.getClass().getSimpleName();
        return (name == null || name.isBlank()) ? "请求异常" : name;
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
