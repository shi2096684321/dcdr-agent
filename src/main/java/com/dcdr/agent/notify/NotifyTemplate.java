package com.dcdr.agent.notify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息模板渲染。
 * <p>
 * <b>为什么只做变量插值，不做完整模板引擎</b>：
 * <ul>
 *   <li>本项目的消息主体是 Agent 生成的自由文本，模板只决定"外壳"（标题 / 元信息 / 正文位置），
 *       没有 Alertmanager 那种"一批告警里按级别分组"的结构需求，{@code if}/{@code range} 用不上；</li>
 *   <li>模板语法一旦复杂化，配错只能在真推送到手机后才发现，排障成本远高于收益；</li>
 *   <li>需要"三段式摘要 + 列表"这类内容组织时，正解是写在定时任务的 inputMessage 里让模型按格式产出
 *       —— Agent 本身就是比模板引擎更灵活的格式化器。</li>
 * </ul>
 * <p>
 * <b>变量语法用 {@code {{name}}} 而不是 {@code ${name}}</b>：后者是 Spring 的占位符，
 * 模板一旦出现在 yml 里会先被 Spring 解析，结果是静默变空或启动报错。
 *
 * @see NotifyProperties#getTemplatesDir()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyTemplate {

    /** {@code {{name}}}，允许花括号内有空格 */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    /** 模板变量名 */
    static final String VAR_TASK_NAME = "taskName";
    static final String VAR_STATUS_TEXT = "statusText";
    static final String VAR_TIME = "time";
    static final String VAR_DURATION = "duration";
    static final String VAR_CONTENT = "content";
    static final String VAR_SUMMARY = "summary";
    static final String VAR_RUN_LINK = "runLink";
    static final String VAR_ERROR = "error";
    static final String VAR_STATUS = "status";

    private final NotifyProperties props;

    /**
     * 渲染一条消息。
     *
     * @param maxSize 渠道允许的最大长度
     * @param unit    计长单位（企微按字节、钉钉按字符）
     * @param suffix  截断提示语，其长度也计入预算
     */
    public String render(NotifyChannel channel, NotifyMessage msg, int maxSize, Unit unit, String suffix) {
        String template = loadTemplate(channel, msg.isFailed());
        Map<String, String> vars = variablesOf(msg, channel);
        String rendered = apply(template, vars);
        if (unit.size(rendered) <= maxSize) return tidy(rendered);

        // 超限时只截"长变量"，保证标题、时间、耗时这些元信息留在前面不被切掉。
        // 要截哪个变量取决于模板实际用了谁：用 {{summary}} 的模板去截 {{content}} 没有意义。
        String longVar = msg.isFailed()
                ? VAR_ERROR
                : (uses(template, VAR_SUMMARY) ? VAR_SUMMARY : VAR_CONTENT);
        String longValue = vars.get(longVar);
        if (longValue != null && !longValue.isEmpty()) {
            int over = unit.size(rendered) - maxSize + unit.size(suffix);
            int keep = unit.size(longValue) - over;
            vars.put(longVar, unit.fit(longValue, Math.max(0, keep), ""));
            String out = apply(template, vars) + suffix;
            if (unit.size(out) <= maxSize) return tidy(out);
        }
        // 兜底：模板本身或截断后仍超限，整体截断
        return tidy(unit.fit(rendered, maxSize, suffix));
    }

    /**
     * 收敛空白：连续空行压成一个，去掉首尾空白。
     * <p>模板里的可选变量（典型是 {@code {{runLink}}}，未配 share-base-url 时为空）
     * 会在消息末尾留下两三行空白，群里看就是"内容说完了还空一大块"。
     */
    private static String tidy(String text) {
        return text.replaceAll("\n{3,}", "\n\n").strip();
    }

    // ===== 模板加载 =====

    /**
     * 读取模板文件，不存在或读取失败则回退内置默认。
     * <p>每次渲染都读盘：文件只有几百字节，而推送本身有 10 分钟节流，代价可忽略；
     * 换来的是"改完模板立即生效、不用重启"。
     */
    private String loadTemplate(NotifyChannel channel, boolean failed) {
        String name = failed ? "failed" : "success";
        Path file = Paths.get(props.getTemplatesDir(), channel.code(), name + ".md");
        try {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.isBlank()) return text;
            }
        } catch (Exception e) {
            log.warn("读取通知模板失败, 使用内置默认: {} ({})", file, e.getMessage());
        }
        return builtin(channel, failed);
    }

    /** 内置默认模板：与引入模板化之前的行为一致，只是正文改成了概览 + 详情链接 */
    static String builtin(NotifyChannel channel, boolean failed) {
        boolean color = channel == NotifyChannel.WECOM_BOT;
        StringBuilder sb = new StringBuilder();
        sb.append(failed ? "## 定时任务执行失败\n" : "## 定时任务执行完成\n");
        sb.append("> 任务：{{").append(VAR_TASK_NAME).append("}}\n");
        sb.append("> 时间：{{").append(VAR_TIME).append("}}\n");
        sb.append("> 耗时：{{").append(VAR_DURATION).append("}}\n\n");
        if (failed) {
            sb.append("**错误信息**\n");
            // 钉钉不支持 <font>，传进去会原样显示成标签
            sb.append(color ? "<font color=\"warning\">{{" + VAR_ERROR + "}}</font>"
                    : "{{" + VAR_ERROR + "}}").append("\n\n");
        } else {
            sb.append("**执行结果**\n{{").append(VAR_SUMMARY).append("}}\n\n");
        }
        // runLink 为空（未配 share-base-url）时这里会渲染成空行，不影响阅读
        sb.append("{{").append(VAR_RUN_LINK).append("}}\n");
        return sb.toString();
    }

    // ===== 变量与替换 =====

    Map<String, String> variablesOf(NotifyMessage msg) {
        return variablesOf(msg, null);
    }

    /**
     * @param channel 用于取该渠道的概览字数；{@code null} 时回退全局配置
     */
    Map<String, String> variablesOf(NotifyMessage msg, NotifyChannel channel) {
        Map<String, String> vars = new HashMap<>(16);
        // 先做 IM 适配再截断: 表格转列表会让文本变长, 反过来先截会把表格切在中间,
        // 转出来的列表缺半截字段, 还不如不转
        String content = NotifyRender.forIm(NotifyRender.orDefault(msg.getContent(), "(无输出)"));
        vars.put(VAR_TASK_NAME, NotifyRender.orDefault(msg.getTaskName(), "(未命名任务)"));
        vars.put(VAR_STATUS, NotifyRender.orDefault(msg.getStatus(), "success"));
        vars.put(VAR_STATUS_TEXT, msg.isFailed() ? "执行失败" : "执行完成");
        vars.put(VAR_TIME, NotifyRender.formatTime(msg.getRunAt()));
        vars.put(VAR_DURATION, NotifyRender.formatDuration(msg.getDurationMs()));
        vars.put(VAR_CONTENT, content);
        vars.put(VAR_SUMMARY, NotifyRender.summarize(content, summaryLimitOf(channel)));
        vars.put(VAR_ERROR, NotifyRender.orDefault(msg.getError(), "(无错误详情)"));
        vars.put(VAR_RUN_LINK, buildRunLink(msg.getRunUrl()));
        return vars;
    }

    /**
     * 概览字数：渠道级配置 &gt; 0 时优先，否则用全局。
     * <p>两个渠道能装的内容差一个数量级（企微 4096 字节 ≈ 1365 汉字，钉钉 18000 字符），
     * 共用一个值只能取交集 —— 结果就是钉钉那条上限几乎闲置，消息里只显示开头一小段。
     */
    private int summaryLimitOf(NotifyChannel channel) {
        int channelSpecific = 0;
        if (channel == NotifyChannel.WECOM_BOT) {
            channelSpecific = props.getWecom().getSummaryChars();
        } else if (channel == NotifyChannel.DINGTALK_BOT) {
            channelSpecific = props.getDingtalk().getSummaryChars();
        }
        return channelSpecific > 0 ? channelSpecific : props.getSummaryChars();
    }

    /** 详情链接整行（含 markdown 语法）；没有地址时返回空串，模板里那一行自然消失 */
    private static String buildRunLink(String url) {
        if (url == null || url.isBlank()) return "";
        return "[查看完整结果](" + url + ")";
    }

    /** 模板里是否引用了某个变量（允许花括号内有空格） */
    private static boolean uses(String template, String name) {
        Matcher m = VAR.matcher(template);
        while (m.find()) {
            if (name.equals(m.group(1))) return true;
        }
        return false;
    }

    /**
     * 替换变量。
     * <p>未知变量<b>原样保留</b>（渲染结果里会看到 {@code {{foo}}}），而不是替换成空 ——
     * 静默变空会让用户以为模板写对了，实际只是拼错了一个字母。
     */
    static String apply(String template, Map<String, String> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 64);
        while (m.find()) {
            String name = m.group(1);
            String value = vars.get(name);
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 计长单位：企微按 UTF-8 字节，钉钉按字符 */
    public enum Unit {
        BYTES {
            @Override
            public int size(String text) {
                return text.getBytes(StandardCharsets.UTF_8).length;
            }

            @Override
            public String fit(String text, int max, String suffix) {
                return NotifyRender.truncateUtf8(text, max, suffix);
            }
        },
        CHARS {
            @Override
            public int size(String text) {
                return text.length();
            }

            @Override
            public String fit(String text, int max, String suffix) {
                return NotifyRender.truncateChars(text, max, suffix);
            }
        };

        public abstract int size(String text);

        public abstract String fit(String text, int max, String suffix);
    }
}
