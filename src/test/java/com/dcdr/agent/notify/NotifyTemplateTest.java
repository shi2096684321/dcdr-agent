package com.dcdr.agent.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板渲染测试。
 * <p>
 * 两个重点：
 * <ul>
 *   <li>没有模板文件时必须回退内置默认，行为与引入模板化之前完全一致；</li>
 *   <li>超长消息只截正文，标题 / 时间 / 耗时这类元信息必须留在消息里 ——
 *       整体截断会把最该保留的部分切掉。</li>
 * </ul>
 */
class NotifyTemplateTest {

    private static final String SUFFIX = "\n\n…(内容过长, 已截断)";

    @TempDir
    Path tempDir;

    private NotifyTemplate template;

    @BeforeEach
    void setUp() {
        template = newTemplate(tempDir);
    }

    private static NotifyTemplate newTemplate(Path dir) {
        NotifyProperties props = new NotifyProperties();
        props.setTemplatesDir(dir.toString());
        return new NotifyTemplate(props);
    }

    // ===== 内置默认模板 =====

    @Test
    @DisplayName("无模板文件时走内置默认，成功消息含任务名与耗时")
    void builtinSuccess() {
        String out = template.render(NotifyChannel.WECOM_BOT, successMsg("今天有 3 条要闻"),
                4096, NotifyTemplate.Unit.BYTES, SUFFIX);
        assertTrue(out.contains("定时任务执行完成"));
        assertTrue(out.contains("每日晨报"));
        assertTrue(out.contains("1.2 s"));
        assertTrue(out.contains("今天有 3 条要闻"));
        assertFalse(out.contains("错误信息"), "成功消息不应出现错误段");
    }

    @Test
    @DisplayName("企业微信默认模板给错误信息加 font 着色")
    void builtinFailedWecomKeepsColor() {
        String out = template.render(NotifyChannel.WECOM_BOT, failedMsg(),
                4096, NotifyTemplate.Unit.BYTES, SUFFIX);
        assertTrue(out.contains("定时任务执行失败"));
        assertTrue(out.contains("<font color=\"warning\">"));
        assertTrue(out.contains("LLM 调用超时"));
    }

    @Test
    @DisplayName("钉钉默认模板不带 font 标签（会被原样显示）")
    void builtinFailedDingtalkHasNoColor() {
        String out = template.render(NotifyChannel.DINGTALK_BOT, failedMsg(),
                18_000, NotifyTemplate.Unit.CHARS, SUFFIX);
        assertTrue(out.contains("定时任务执行失败"));
        assertFalse(out.contains("<font"));
        assertTrue(out.contains("LLM 调用超时"));
    }

    @Test
    @DisplayName("空任务名/空正文都有兜底，不会推一条空白消息")
    void blanksFallBackToPlaceholder() {
        NotifyMessage msg = NotifyMessage.builder()
                .taskId("t").taskName("").status("success").build();
        String out = template.render(NotifyChannel.WECOM_BOT, msg, 4096, NotifyTemplate.Unit.BYTES, SUFFIX);
        assertTrue(out.contains("(未命名任务)"));
        assertTrue(out.contains("(无输出)"));

        NotifyMessage bad = NotifyMessage.builder()
                .taskId("t").taskName("x").status("failed").build();
        assertTrue(template.render(NotifyChannel.WECOM_BOT, bad, 4096,
                NotifyTemplate.Unit.BYTES, SUFFIX).contains("(无错误详情)"));
    }

    // ===== 自定义模板 =====

    @Test
    @DisplayName("模板文件存在时优先使用，变量被替换")
    void customTemplateIsUsed() throws IOException {
        writeTemplate("wecom_bot/success.md", "[{{taskName}}] {{statusText}} @ {{time}}");
        String out = template.render(NotifyChannel.WECOM_BOT, successMsg("正文"),
                4096, NotifyTemplate.Unit.BYTES, SUFFIX);
        assertTrue(out.startsWith("[每日晨报] 执行完成 @ 2026-10-30 09:00:00"), out);
    }

    @Test
    @DisplayName("按渠道与状态分别取模板，互不串用")
    void templateIsPerChannelAndStatus() throws IOException {
        writeTemplate("wecom_bot/success.md", "WECOM-OK");
        writeTemplate("wecom_bot/failed.md", "WECOM-BAD");
        writeTemplate("dingtalk_bot/success.md", "DING-OK");

        assertEquals("WECOM-OK", render(NotifyChannel.WECOM_BOT, false));
        assertEquals("WECOM-BAD", render(NotifyChannel.WECOM_BOT, true));
        assertEquals("DING-OK", render(NotifyChannel.DINGTALK_BOT, false));
        // 钉钉失败模板没写，应回退内置默认
        assertTrue(render(NotifyChannel.DINGTALK_BOT, true).contains("定时任务执行失败"));
    }

    @Test
    @DisplayName("空模板文件视为不存在，回退内置默认")
    void blankTemplateFallsBack() throws IOException {
        writeTemplate("wecom_bot/success.md", "   \n");
        assertTrue(render(NotifyChannel.WECOM_BOT, false).contains("定时任务执行完成"));
    }

    @Test
    @DisplayName("未知变量原样保留，而不是静默替换成空")
    void unknownVariableIsKept() throws IOException {
        writeTemplate("wecom_bot/success.md", "{{taskName}}/{{notARealVar}}");
        assertEquals("每日晨报/{{notARealVar}}", render(NotifyChannel.WECOM_BOT, false));
    }

    @Test
    @DisplayName("变量值里的 $ 与反斜杠不会被当成正则替换语法")
    void dollarInValueIsLiteral() {
        NotifyMessage msg = NotifyMessage.builder()
                .taskId("t").taskName("价格 $100 \\ 折扣").status("success").content("x").build();
        Map<String, String> vars = template.variablesOf(msg);
        assertEquals("价格 $100 \\ 折扣", NotifyTemplate.apply("{{taskName}}", vars));
    }

    // ===== 截断 =====

    @Test
    @DisplayName("超长正文被截断，但标题/时间/耗时完整保留")
    void truncatesContentButKeepsMetadata() {
        // 默认模板已改用概览（{{summary}} 只取前 300 字），要触发截断得把概览上限调到全文
        NotifyTemplate unlimited = templateWithSummaryChars(100_000);
        String huge = "中".repeat(3000); // 9000 字节，远超 4096
        String out = unlimited.render(NotifyChannel.WECOM_BOT, successMsg(huge),
                4096, NotifyTemplate.Unit.BYTES, SUFFIX);

        assertTrue(out.getBytes(StandardCharsets.UTF_8).length <= 4096, "超出字节上限");
        assertTrue(out.contains("任务：每日晨报"), "元信息被截掉了");
        assertTrue(out.contains("耗时：1.2 s"), "元信息被截掉了");
        assertTrue(out.contains("…(内容过长, 已截断)"), "缺少截断提示");
    }

    @Test
    @DisplayName("截断按字节计算，中文不会被切成半个")
    void truncationKeepsMultibyteCharsIntact() {
        String out = template.render(NotifyChannel.WECOM_BOT, successMsg("中".repeat(2000)),
                4096, NotifyTemplate.Unit.BYTES, SUFFIX);
        assertFalse(out.contains("\uFFFD"), "出现替换字符说明切坏了字符");
        assertTrue(out.contains("执行结果"));
    }

    @Test
    @DisplayName("钉钉按字符截断，不吃字节预算")
    void dingtalkTruncatesByChars() {
        NotifyTemplate unlimited = templateWithSummaryChars(100_000);
        String out = unlimited.render(NotifyChannel.DINGTALK_BOT, successMsg("字".repeat(50_000)),
                18_000, NotifyTemplate.Unit.CHARS, SUFFIX);
        assertTrue(out.length() <= 18_000);
        assertTrue(out.contains("…(内容过长, 已截断)"));
    }

    /** 概览上限调到全文，用于触发"整体超限 → 截断"这条路径 */
    private NotifyTemplate templateWithSummaryChars(int chars) {
        NotifyProperties props = new NotifyProperties();
        props.setTemplatesDir(tempDir.toString());
        props.setSummaryChars(chars);
        // 渠道级配置优先级高于全局，这里要一并抬高，否则会被默认的 900 / 3000 卡住
        props.getWecom().setSummaryChars(chars);
        props.getDingtalk().setSummaryChars(chars);
        return new NotifyTemplate(props);
    }

    @Test
    @DisplayName("截断走字符版时不留下孤立的高位代理（emoji 被切成半个）")
    void truncateCharsKeepsSurrogatePairs() {
        String emoji = "😀".repeat(10);
        String out = NotifyRender.truncateChars(emoji, 5, "");
        assertEquals(4, out.length());
        assertFalse(Character.isHighSurrogate(out.charAt(out.length() - 1)));
    }

    // ===== 概览与详情链接 =====

    @Test
    @DisplayName("摘要尽量在句末断开，不出现半句话")
    void summarizeBreaksAtSentenceEnd() {
        String text = "第一句话结束了。第二句话也结束了。第三句会很长很长" + "啊".repeat(200);
        String out = NotifyRender.summarize(text, 20);
        assertTrue(out.length() <= 21, "摘要超长: " + out.length());
        assertTrue(out.endsWith("。…"), "没有在句号处断开: " + out);
    }

    @Test
    @DisplayName("摘要未超长时原样返回，不加省略号")
    void summarizeShortTextUntouched() {
        assertEquals("短文本", NotifyRender.summarize("短文本", 300));
        assertEquals("", NotifyRender.summarize(null, 300));
    }

    @Test
    @DisplayName("摘要不切出半个 emoji")
    void summarizeKeepsSurrogatePairs() {
        String out = NotifyRender.summarize("😀".repeat(300), 15);
        assertFalse(out.contains("\uFFFD"), "出现了替换字符");
    }

    @Test
    @DisplayName("默认模板用概览而不是全文，长正文不会撑爆消息")
    void defaultTemplateUsesSummary() {
        String longContent = "这是一段很长的正文。".repeat(100);
        String out = template.render(NotifyChannel.WECOM_BOT, successMsg(longContent),
                4096, NotifyTemplate.Unit.BYTES, SUFFIX);
        assertTrue(out.contains("…"), "应出现省略号");
        assertTrue(out.contains("任务：每日晨报"));
        assertTrue(out.length() < longContent.length(), "概览没有生效，仍然是全文");
    }

    @Test
    @DisplayName("有 runUrl 时渲染成 markdown 链接，没有时那一行为空")
    void runLinkRendering() {
        NotifyMessage withUrl = successMsg("正文");
        withUrl.setRunUrl("https://example.com/share/runs/abc123");
        String out = template.render(NotifyChannel.WECOM_BOT, withUrl,
                4096, NotifyTemplate.Unit.BYTES, SUFFIX);
        assertTrue(out.contains("[查看完整结果](https://example.com/share/runs/abc123)"));

        String without = template.render(NotifyChannel.WECOM_BOT, successMsg("正文"),
                4096, NotifyTemplate.Unit.BYTES, SUFFIX);
        assertFalse(without.contains("查看完整结果"), "没配 base-url 时不应出现链接文案");
    }

    @Test
    @DisplayName("概览长度按渠道分别取值: 钉钉能放 3000 字, 企微只有 900")
    void summaryLimitIsPerChannel() {
        String content = "字".repeat(8000);

        String ding = template.render(NotifyChannel.DINGTALK_BOT, successMsg(content),
                18_000, NotifyTemplate.Unit.CHARS, SUFFIX);
        // 旧的全局 300 字会让这里只有约 350 字 —— 钉钉 18000 字符上限几乎闲置
        assertTrue(ding.length() > 2_500, "钉钉概览仍然过短: " + ding.length());

        String wecom = template.render(NotifyChannel.WECOM_BOT, successMsg(content),
                4096, NotifyTemplate.Unit.BYTES, SUFFIX);
        // 4096 字节 ÷ 3 ≈ 1365 汉字, 超过这个数会走到截断分支、把末尾链接切掉
        assertTrue(wecom.getBytes(StandardCharsets.UTF_8).length <= 4096, "超出企微字节上限");
        assertTrue(wecom.length() < 1_300, "企微概览过长, 会挤掉链接: " + wecom.length());
    }

    @Test
    @DisplayName("渠道级为 0 时回退全局 summary-chars")
    void summaryFallsBackToGlobal() {
        NotifyProperties props = new NotifyProperties();
        props.setTemplatesDir(tempDir.toString());
        props.setSummaryChars(150);
        props.getWecom().setSummaryChars(0);
        props.getDingtalk().setSummaryChars(0);
        NotifyTemplate t = new NotifyTemplate(props);

        String out = t.render(NotifyChannel.DINGTALK_BOT, successMsg("字".repeat(8000)),
                18_000, NotifyTemplate.Unit.CHARS, SUFFIX);
        assertTrue(out.length() < 400, "没有回退到全局配置: " + out.length());
    }

    // ===== 工具 =====

    private String render(NotifyChannel channel, boolean failed) {
        NotifyMessage msg = failed ? failedMsg() : successMsg("正文");
        return template.render(channel, msg, 100_000, NotifyTemplate.Unit.CHARS, SUFFIX);
    }

    private void writeTemplate(String relative, String content) throws IOException {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static NotifyMessage successMsg(String content) {
        return NotifyMessage.builder()
                .taskId("t1")
                .taskName("每日晨报")
                .status("success")
                .content(content)
                .durationMs(1234)
                .runAt(LocalDateTime.of(2026, 10, 30, 9, 0, 0))
                .build();
    }

    private static NotifyMessage failedMsg() {
        return NotifyMessage.builder()
                .taskId("t2")
                .taskName("数据同步")
                .status("failed")
                .error("LLM 调用超时: connect timed out")
                .durationMs(900)
                .runAt(LocalDateTime.of(2026, 10, 30, 9, 0, 0))
                .build();
    }
}
