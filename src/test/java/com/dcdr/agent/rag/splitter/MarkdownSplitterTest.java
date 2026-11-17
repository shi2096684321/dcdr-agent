package com.dcdr.agent.rag.splitter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MarkdownSplitter} 单元测试。 */
class MarkdownSplitterTest {

    private static final String DOC = """
            # 标题A

            段落一。

            ## 小节A1

            段落二。

            # 标题B

            段落三。
            """;

    @Test
    @DisplayName("按标题分节, 子节携带完整的标题路径")
    void splitsByHeadingAndKeepsHeadingPath() {
        List<String> chunks = new MarkdownSplitter(20, 0).split(DOC);

        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).startsWith("# 标题A"));
        assertFalse(chunks.get(0).contains("## 小节A1"), "父节不应带上子节标题");
        assertTrue(chunks.get(1).contains("# 标题A"));
        assertTrue(chunks.get(1).contains("## 小节A1"), "子节要带完整标题路径");
        assertTrue(chunks.get(2).startsWith("# 标题B"));
        assertFalse(chunks.get(2).contains("标题A"), "兄弟节之间不应串标题");
    }

    @Test
    @DisplayName("小节超过块大小时细分正文, 每块都保留标题前缀")
    void oversizedSectionKeepsPrefixOnEveryChunk() {
        String text = "# H\n" + "abcdefghij".repeat(3);
        List<String> chunks = new MarkdownSplitter(10, 0).split(text);

        assertTrue(chunks.size() >= 5);
        for (String chunk : chunks) {
            assertTrue(chunk.startsWith("# H\n"), "细分后的每块都应带上所属标题: " + chunk);
            assertTrue(chunk.length() <= 10);
        }
    }

    @Test
    @DisplayName("代码块内的 # 不识别为标题, 内容不丢失")
    void ignoresHeadingsInsideCodeFences() {
        String text = """
                # 真标题

                ```md
                # 假标题
                ```

                正文
                """;
        List<String> chunks = new MarkdownSplitter(1000, 0).split(text);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).startsWith("# 真标题"));
        assertTrue(chunks.get(0).contains("# 假标题"), "代码块内的文本是正文, 不能丢");
    }

    @Test
    @DisplayName("全文没有标题时退化为递归分块")
    void fallsBackToRecursiveWhenNoHeading() {
        List<String> chunks = new MarkdownSplitter(4, 0).split("第一句。第二句。");
        assertEquals(List.of("第一句。", "第二句。"), chunks);
    }

    @Test
    @DisplayName("参数校验与其它策略一致")
    void validatesParameters() {
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSplitter(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSplitter(10, 10));
    }
}
