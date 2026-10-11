package com.dcdr.agent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentOrchestrator#cleanMarkdownContent(String)} 单元测试(V3.2)。
 *
 * <p>该方法负责把抓取到的网页正文(HTML/Markdown 混杂)清洗成可喂给模型的纯正文,
 * 是联网搜索链路的后处理核心。它是<b>包级 static</b> —— 无实例状态、无 IO,
 * 故测试类放在同一包内即可直接调用, <b>无需启动 Spring、无需构造那个 20 依赖的上帝类</b>。
 *
 * <p><b>为什么先给它补测试而不是先重构</b>: 该方法因与编排逻辑同处一个 2298 行的类中而长期无人敢动。
 * 先建立行为快照, 将来把它抽成独立工具类时才有"改完行为不变"的验证依据 ——
 * <b>测试是重构的前提, 不是重构的结果</b>。
 *
 * <p>注意: 本类断言的是<b>当前实现的实际行为</b>。其中「页脚关键词中途截断」一条属<b>已知问题</b>
 * (见对应测试的说明), 若将来修复, 该断言需同步更新。
 */
class CleanMarkdownContentTest {

    private static String clean(String text) {
        return AgentOrchestrator.cleanMarkdownContent(text);
    }

    @Test
    @DisplayName("null / 空白输入返回空串")
    void blankInputReturnsEmpty() {
        assertEquals("", clean(null));
        assertEquals("", clean(""));
        assertEquals("", clean("   "));
    }

    // ==================================================================
    //  HTML 处理
    // ==================================================================

    @Nested
    @DisplayName("HTML 残留处理")
    class Html {

        @Test
        @DisplayName("script/style 等块连同内容被整体移除")
        void removesHtmlBlocksWithContent() {
            String out = clean("正文开头\n<script>var a = 1;</script>\n正文结尾");
            assertFalse(out.contains("var a"), "script 内容应连同标签一起移除");
            assertTrue(out.contains("正文开头"));
            assertTrue(out.contains("正文结尾"));
        }

        @Test
        @DisplayName("裸标签被清除但文字保留")
        void removesBareTags() {
            assertEquals("加粗文字", clean("<span>加粗文字</span>"));
        }

        @Test
        @DisplayName("命名实体被解码")
        void decodesNamedEntities() {
            assertEquals("A & B", clean("A &amp; B"));
            assertEquals("\"引用\"", clean("&quot;引用&quot;"));
            assertEquals("A B", clean("A&nbsp;B"));
        }

        @Test
        @DisplayName("数字实体被解码(十进制与十六进制)")
        void decodesNumericEntities() {
            assertEquals("A", clean("&#65;"));
            assertEquals("A", clean("&#x41;"));
        }
    }

    // ==================================================================
    //  逐行去噪规则
    // ==================================================================

    @Nested
    @DisplayName("逐行去噪")
    class LineRules {

        @Test
        @DisplayName("连续 3 行以上的导航链接块被整体移除")
        void removesConsecutiveNavBlocks() {
            String md = """
                    正文开始
                    * [首页](https://a.com/)
                    * [关于我们](https://a.com/about)
                    * [联系方式](https://a.com/contact)
                    正文结束
                    """;
            String out = clean(md);
            assertFalse(out.contains("关于我们"));
            assertTrue(out.contains("正文开始"));
            assertTrue(out.contains("正文结束"));
        }

        @Test
        @DisplayName("少于 3 行的链接行保留(避免误删正文里的步骤列表)")
        void keepsShortLinkLists() {
            String md = """
                    操作步骤:
                    * [第一步](https://a.com/1)
                    * [第二步](https://a.com/2)
                    完成
                    """;
            String out = clean(md);
            assertTrue(out.contains("第一步"), "仅 2 行链接不构成导航块, 不应被删");
            assertTrue(out.contains("第二步"));
        }

        @Test
        @DisplayName("纯图片行被移除")
        void removesImageOnlyLines() {
            String out = clean("正文\n![图片](https://a.com/x.png)\n结束");
            assertFalse(out.contains("x.png"));
            assertTrue(out.contains("正文"));
            assertTrue(out.contains("结束"));
        }

        @Test
        @DisplayName("行内图片语法被剥离, 同行文字保留")
        void stripsInlineImageKeepsText() {
            assertEquals("这是  说明", clean("这是 ![图](https://a.com/x.png) 说明"));
        }

        @Test
        @DisplayName("重复的链接标题行被移除(与下方真实标题重复)")
        void removesLinkTitleLine() {
            String md = "## [某文章标题](https://a.com/1)\n### 某文章标题\n正文内容";
            String out = clean(md);
            assertFalse(out.contains("](https://a.com/1)"));
            assertTrue(out.contains("### 某文章标题"));
            assertTrue(out.contains("正文内容"));
        }

        @Test
        @DisplayName("栏目聚合链接行被移除")
        void removesSectionLinkLine() {
            String out = clean("## [科技](https://a.com/category/tech)\n正文");
            assertFalse(out.contains("category"));
            assertTrue(out.contains("正文"));
        }

        @Test
        @DisplayName("SPA 动态占位行被移除")
        void removesPlaceholderLines() {
            String out = clean("正文\n加载中\n点击加载更多\n结尾");
            assertFalse(out.contains("加载中"));
            assertFalse(out.contains("点击加载更多"));
            assertTrue(out.contains("正文"));
            assertTrue(out.contains("结尾"));
        }
    }

    // ==================================================================
    //  页脚截断 / 空行 / 长度
    // ==================================================================

    @Nested
    @DisplayName("页脚截断与收尾处理")
    class FooterAndTail {

        @Test
        @DisplayName("尾部区域命中页脚特征: 从该行起全部截断")
        void footerKeywordInTailTruncates() {
            // 7 行内容 + 末尾换行 → n=8; 命中在第 7 行(index 6) → ratio = 0.75 >= 0.7
            String md = """
                    正文一
                    正文二
                    正文三
                    正文四
                    正文五
                    正文六
                    Copyright © 2026 某某网站
                    """;
            String out = clean(md);
            assertTrue(out.contains("正文一"));
            assertTrue(out.contains("正文六"));
            assertFalse(out.contains("Copyright"), "尾部命中后从此行起应全部移除");
        }

        @Test
        @DisplayName("修复: 正文中部命中页脚特征不再截断(此前会误删后半篇)")
        void footerKeywordInBodyDoesNotTruncate() {
            // 早前实现为「命中即截断」且不区分位置: 正文中一句「请 Subscribe 我们的栏目」
            // 会把其后全部正文删掉。现仅当命中位于尾部区域(FOOTER_TAIL_RATIO=0.7 之后)才截断。
            // 7 行以内, 命中在第 2 行 → ratio = 0.25, 不触发截断。
            String md = """
                    第一段正文
                    请 Subscribe 我们的栏目以获取更新
                    第三段本应保留的正文
                    """;
            String out = clean(md);
            assertTrue(out.contains("第一段正文"));
            assertTrue(out.contains("第三段本应保留的正文"), "正文中部的页脚词不应触发截断");
        }

        @Test
        @DisplayName("连续空行压缩为一个")
        void compressesBlankLines() {
            assertEquals("A\n\nB", clean("A\n\n\n\nB"));
        }

        @Test
        @DisplayName("超长内容被截断并追加提示")
        void truncatesOverlongContent() {
            String out = clean("x".repeat(5000));
            assertTrue(out.length() < 5000, "应按上限截断");
            assertTrue(out.endsWith("...(内容过长已截断)"));
        }
    }
}
