package com.dcdr.agent.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本 diff 单测 —— 重点验证:
 * 内容一致时不出噪音、增删行数与摘要一致、上下文合并成合理的 hunk、超大改动不死等。
 */
class TextDiffTest {

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    void 内容相同没有任何输出() {
        TextDiff.Result r = TextDiff.unified("a\nb\nc", "a\nb\nc");

        assertTrue(r.lines().isEmpty());
        assertEquals(0, r.added());
        assertEquals(0, r.removed());
        assertFalse(r.truncated());
    }

    @Test
    void 单行修改给出一个减一个加() {
        String before = "a\nb\nc\nd\ne\nf\ng\nh\ni";
        String after = "a\nb\nc\nd\nE\nf\ng\nh\ni";

        TextDiff.Result r = TextDiff.unified(before, after, 1, 200);

        assertEquals(1, r.added());
        assertEquals(1, r.removed());
        String text = join(r.lines());
        assertTrue(text.contains("-e"), "应包含被删掉的旧行");
        assertTrue(text.contains("+E"), "应包含新行");
        assertTrue(text.contains("@@"), "缺少 hunk 头");
    }

    @Test
    void 纯新增文件全部是加号行() {
        TextDiff.Result r = TextDiff.unified("", "x\ny\nz");

        assertEquals(3, r.added());
        assertEquals(0, r.removed());
        assertTrue(join(r.lines()).contains("+x"));
    }

    @Test
    void 纯删除文件全部是减号行() {
        TextDiff.Result r = TextDiff.unified("x\ny\nz", "");

        assertEquals(0, r.added());
        assertEquals(3, r.removed());
        assertTrue(join(r.lines()).contains("-x"));
    }

    @Test
    void 换行风格差异不算改动() {
        // 同一个文件被 Windows 编辑器改成 CRLF, 内容其实没变
        TextDiff.Result r = TextDiff.unified("a\nb\nc\n", "a\r\nb\r\nc\r\n");

        assertTrue(r.lines().isEmpty(), "只差换行符不应产生 diff: " + join(r.lines()));
    }

    @Test
    void 末尾换行不影响结果() {
        // 一半编辑器会在保存时补/删最后一个换行, 这不该被当成内容改动
        TextDiff.Result r = TextDiff.unified("a\nb\nc", "a\nb\nc\n");

        assertTrue(r.lines().isEmpty(), "只差末尾换行不应产生 diff: " + join(r.lines()));
    }

    @Test
    void 相隔很远的改动拆成两个hunk() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            String line = "line" + i;
            before.append(line).append('\n');
            after.append(i == 2 || i == 28 ? "changed" + i : line).append('\n');
        }

        TextDiff.Result r = TextDiff.unified(before.toString(), after.toString(), 1, 200);

        long hunks = r.lines().stream().filter(l -> l.startsWith("@@")).count();
        assertEquals(2, hunks, "两处相距 26 行的改动不该被并成一个 hunk");
        assertEquals(2, r.added());
        assertEquals(2, r.removed());
    }

    @Test
    void 相邻改动合并成一个hunk() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10";
        String after = "1\nX\nY\n4\n5\nZ\n7\n8\n9\n10";

        TextDiff.Result r = TextDiff.unified(before, after, 3, 200);

        long hunks = r.lines().stream().filter(l -> l.startsWith("@@")).count();
        assertEquals(1, hunks, "上下文重叠的改动应合并");
    }

    @Test
    void 超过行数上限时截断而不是无限输出() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            before.append("old").append(i).append('\n');
            after.append("new").append(i).append('\n');
        }

        TextDiff.Result r = TextDiff.unified(before.toString(), after.toString(), 3, 20);

        assertTrue(r.truncated(), "应标记截断");
        assertTrue(r.lines().size() <= 20, "输出行数不能超过上限: " + r.lines().size());
        // 摘要统计的是真实改动量, 不受截断影响 —— 前端靠它告诉用户"一共改了多少"
        assertEquals(200, r.added());
        assertEquals(200, r.removed());
    }

    @Test
    void 超大改动退化为整体替换而不爆内存() {
        // 1001 × 1001 超过 DP 单元格上限, 必须走 coarse 分支
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            before.append("a").append(i).append('\n');
            after.append("b").append(i).append('\n');
        }

        TextDiff.Result r = TextDiff.unified(before.toString(), after.toString(), 3, 200);

        assertTrue(r.coarse(), "应退化为 coarse 模式");
        assertEquals(1001, r.added());
        assertEquals(1001, r.removed());
        assertFalse(r.lines().isEmpty());
    }

    @Test
    void hunk头里的行号对齐原文件位置() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10";
        String after = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11";

        TextDiff.Result r = TextDiff.unified(before, after, 3, 200);

        String header = r.lines().get(0);
        // 改动发生在第 11 行(新增), 带 3 行上下文应从第 8 行起
        assertTrue(header.startsWith("@@ -8,"), "hunk 起点不对: " + header);
        assertTrue(header.contains("+8,"), "新文件侧行号不对: " + header);
    }

    @Test
    void 空对空没有任何输出() {
        TextDiff.Result r = TextDiff.unified("", "");

        assertTrue(r.lines().isEmpty());
        assertTrue(r.isEmpty());
    }
}
