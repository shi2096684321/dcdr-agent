package com.dcdr.agent.tool.websearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网页正文抽取的<b>黄金集评测</b>(V3.4)。
 *
 * <p><b>为什么需要它</b>: {@code web_extract} 的整条链路
 * (jsoup 判编码 → crux 提正文 → flexmark 转 Markdown → DOM/文本规范化)此前<b>一条测试都没有</b>,
 * 因为取字节与解析写在同一方法里, 想验证只能起 HTTP 服务。而这段代码恰恰是最容易静默退化的:
 * crux 的 {@code doc.body()} 恒为空、中文站的 GBK 编码、SPA 空壳页 ——
 * 任何一处坏掉, 外部表现都只是"抓不到内容", 与"站点拒绝抓取"完全无法区分。
 *
 * <p><b>为什么用存档 HTML 而不是真实 URL</b>: 评测的是<b>抽取算法</b>, 不是站点可用性。
 * 存档之后不依赖网络、毫秒级完成、可进常规构建; 真实页面的冒烟另见
 * {@code WebExtractLiveSmokeTest}(默认不执行)。
 *
 * <p><b>断言口径</b>: 用「必须出现的正文句子」与「必须不出现的噪声词」,
 * 不用「必须等于某段 Markdown」—— 后者会把每个空格差异都算作失败, 没人愿意维护。
 * 噪声词比正文句子更值得断言: 它出现在正文里意味着算法把整页都当正文了, 比"抽得少"更糟。
 *
 * <p>用例定义在 {@code src/test/resources/eval/web/extract-golden.json}。
 */
class WebExtractFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与工具默认上限一致, 避免测试用一套、线上用另一套 */
    private static final int MAX_CHARS = 8000;

    private static final String DIR = "/eval/web/";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoldenCase(
            String id,
            String description,
            String file,
            String url,
            Boolean expectExtracted,
            String titleContains,
            String titleNotContains,
            List<String> mustContain,
            List<String> mustNotContain,
            Integer minChars,
            Integer maxCharsIfExtracted) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Golden(List<GoldenCase> cases) {
    }

    private static Golden loadGolden() {
        try (InputStream in = WebExtractFixtureTest.class.getResourceAsStream(DIR + "extract-golden.json")) {
            assertNotNull(in, "黄金集缺失: " + DIR + "extract-golden.json");
            return MAPPER.readValue(in, Golden.class);
        } catch (Exception e) {
            throw new IllegalStateException("黄金集解析失败", e);
        }
    }

    /** 按<b>字节</b>读取: GBK fixture 必须原样交给 jsoup, 不能在读取阶段就被转成 UTF-8 */
    private static byte[] fixtureBytes(String file) {
        try (InputStream in = WebExtractFixtureTest.class.getResourceAsStream(DIR + file)) {
            assertNotNull(in, "fixture 缺失: " + DIR + file);
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("fixture 读取失败: " + file, e);
        }
    }

    static Stream<Arguments> cases() {
        return loadGolden().cases().stream().map(c -> Arguments.of(c.id(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("黄金集")
    void matchesGolden(String id, GoldenCase c) {
        WebExtractTool.Extracted ex = WebExtractTool.extractFromHtml(fixtureBytes(c.file()), c.url(), MAX_CHARS);

        // 明确要求"抽不到"的用例(纯 JS 空壳页): 必须干净地返回 null,
        // 而不是把 <div id="app"></div> 这类空壳当正文交给模型
        if (Boolean.FALSE.equals(c.expectExtracted())) {
            assertNull(ex, "[" + c.id() + "] 应判定为抓取失败: " + c.description());
            return;
        }
        if (Boolean.TRUE.equals(c.expectExtracted())) {
            assertNotNull(ex, "[" + c.id() + "] 应能抽出正文: " + c.description());
        }
        if (ex == null) {
            return;   // 未强制要求的用例(如登录拦截页)允许抽不到
        }

        String content = ex.content();
        String preview = content.length() > 400 ? content.substring(0, 400) + "…" : content;

        if (c.titleContains() != null) {
            assertTrue(ex.title().contains(c.titleContains()),
                    "[" + c.id() + "] 标题缺少「" + c.titleContains() + "」, 实际=" + ex.title());
        }
        if (c.titleNotContains() != null) {
            assertFalse(ex.title().contains(c.titleNotContains()),
                    "[" + c.id() + "] 标题未剥离站点后缀「" + c.titleNotContains() + "」, 实际=" + ex.title());
        }
        for (String must : c.mustContain() == null ? List.<String>of() : c.mustContain()) {
            assertTrue(content.contains(must),
                    "[" + c.id() + "] 正文缺少必要片段「" + must + "」\n实际正文前 400 字:\n" + preview);
        }
        for (String noise : c.mustNotContain() == null ? List.<String>of() : c.mustNotContain()) {
            assertFalse(content.contains(noise),
                    "[" + c.id() + "] 正文混入噪声「" + noise + "」—— 提取算法把导航/侧栏/页脚也当正文了\n"
                            + "实际正文前 400 字:\n" + preview);
        }
        if (c.minChars() != null) {
            assertTrue(content.length() >= c.minChars(),
                    "[" + c.id() + "] 正文过短: " + content.length() + " < " + c.minChars()
                            + "（抽取不完整, 会退化成「摘要级」内容）\n" + preview);
        }
        if (c.maxCharsIfExtracted() != null) {
            // 登录墙这类页面即便抽出内容也必须是极短的一两句:
            // 超过阈值说明页脚菜单/推荐位被当成了正文
            assertTrue(content.length() <= c.maxCharsIfExtracted(),
                    "[" + c.id() + "] 正文超出预期上限: " + content.length() + " > " + c.maxCharsIfExtracted()
                            + "\n" + preview);
        }
    }

    @Test
    @DisplayName("fixture 目录与黄金集必须一一对应(加了文件忘加用例 / 加了用例忘加文件 都要发现)")
    void goldenAndFixturesAreInSync() {
        Set<String> declared = new HashSet<>();
        loadGolden().cases().forEach(c -> declared.add(c.file()));

        for (String f : declared) {
            assertNotNull(WebExtractFixtureTest.class.getResourceAsStream(DIR + f),
                    "黄金集引用了不存在的 fixture: " + f);
        }
        // 反向检查: 目录里的 .html 都应被某个用例覆盖
        for (String f : listFixtureFiles()) {
            assertTrue(declared.contains(f),
                    "fixture " + f + " 未被任何用例引用 —— 它不会被执行, 等于没测");
        }
    }

    /** 列出 classpath 下 fixture 目录中的 .html 文件名(目录扫描, 兼容 jar 与 IDE) */
    private static List<String> listFixtureFiles() {
        try {
            var url = WebExtractFixtureTest.class.getResource(DIR);
            assertNotNull(url, "fixture 目录缺失: " + DIR);
            var path = java.nio.file.Paths.get(url.toURI());
            try (var files = java.nio.file.Files.list(path)) {
                return files.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".html"))
                        .sorted()
                        .toList();
            }
        } catch (Exception e) {
            throw new IllegalStateException("fixture 目录扫描失败", e);
        }
    }

    @Test
    @DisplayName("空响应 / 空字节: 返回 null 而不是抛异常或产出空串")
    void emptyBodyYieldsNull() {
        assertNull(WebExtractTool.extractFromHtml(new byte[0], "https://example.com/x", MAX_CHARS));
    }

    @Test
    @DisplayName("超过上限时截断在 maxChars, 且带上截断标记")
    void clipsToMaxChars() {
        byte[] raw = fixtureBytes("article-zh-clean.html");
        WebExtractTool.Extracted ex = WebExtractTool.extractFromHtml(raw, "https://example.com/a.html", 50);
        assertNotNull(ex);
        assertTrue(ex.content().length() <= 51, "截断后长度不应超过 maxChars(含一个省略号)");
        assertTrue(ex.content().endsWith("…"), "截断必须有可见标记, 否则模型以为正文到此为止");
    }
}
