package com.dcdr.agent.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本 diff 单测 —— 重点验证:
 * 内容一致时不出噪音、增删行数与摘要一致、折叠标记如实反映"跳过多少行"、超大改动不死等。
 */
class TextDiffTest {

    @Test
    void 内容相同没有任何输出() {
        TextDiff.Result r = TextDiff.unified("a\nb\nc", "a\nb\nc");

        assertTrue(r.lines().isEmpty());
        assertEquals(0, r.added());
        assertEquals(0, r.removed());
        assertFalse(r.truncated());
    }

    @Test
    void 单行修改给出一条删除一条新增() {
        String before = "a\nb\nc\nd\ne\nf\ng\nh\ni";
        String after = "a\nb\nc\nd\nE\nf\ng\nh\ni";

        TextDiff.Result r = TextDiff.unified(before, after, 1, 200);

        assertEquals(1, r.added());
        assertEquals(1, r.removed());
        assertTrue(has(r, "del", "e"), "缺少被删掉的旧行");
        assertTrue(has(r, "add", "E"), "缺少新行");
    }

    @Test
    void 纯新增文件全部是add行() {
        TextDiff.Result r = TextDiff.unified("", "x\ny\nz");

        assertEquals(3, r.added());
        assertEquals(0, r.removed());
        assertTrue(has(r, "add", "x"));
        assertTrue(r.lines().stream().noneMatch(l -> "skip".equals(l.type())), "全是新增时没有可折叠的区间");
    }

    @Test
    void 纯删除文件全部是del行() {
        TextDiff.Result r = TextDiff.unified("x\ny\nz", "");

        assertEquals(0, r.added());
        assertEquals(3, r.removed());
        assertTrue(has(r, "del", "x"));
    }

    @Test
    void 换行风格差异不算改动() {
        // 同一个文件被 Windows 编辑器改成 CRLF, 内容其实没变
        TextDiff.Result r = TextDiff.unified("a\nb\nc\n", "a\r\nb\r\nc\r\n");

        assertTrue(r.lines().isEmpty());
    }

    @Test
    void 末尾换行不影响结果() {
        // 一半编辑器会在保存时补/删最后一个换行, 这不该被当成内容改动
        TextDiff.Result r = TextDiff.unified("a\nb\nc", "a\nb\nc\n");

        assertTrue(r.lines().isEmpty());
    }

    @Test
    void 相隔很远的改动之间与尾部都有折叠标记() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            String line = "line" + i;
            before.append(line).append('\n');
            after.append(i == 2 || i == 28 ? "changed" + i : line).append('\n');
        }

        TextDiff.Result r = TextDiff.unified(before.toString(), after.toString(), 1, 200);

        assertEquals(2, r.added());
        assertEquals(2, r.removed());
        long skips = r.lines().stream().filter(l -> "skip".equals(l.type())).count();
        // 两块改动之间一段（第 4–26 行）+ 尾部一段（第 30 行）
        assertEquals(2, skips, "块之间和文件尾部都应标出折叠区间");

        TextDiff.DiffLine tail = r.lines().get(r.lines().size() - 1);
        assertEquals(1, tail.skipped(), "尾部应只折叠 1 行: " + tail);
    }

    @Test
    void 折叠标记的行号从1开始且连续() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            before.append("l").append(i).append('\n');
            after.append(i == 20 ? "CHANGED" : "l" + i).append('\n');
        }

        TextDiff.Result r = TextDiff.unified(before.toString(), after.toString(), 2, 200);

        TextDiff.DiffLine head = r.lines().get(0);
        assertEquals("skip", head.type());
        assertEquals(1, head.oldNo(), "从第 1 行开始折叠时行号必须是 1");
        assertEquals(17, head.skipped(), "第 18 行起才是上下文, 之前 17 行应折叠");

        TextDiff.DiffLine tail = r.lines().get(r.lines().size() - 1);
        assertEquals("skip", tail.type());
        assertEquals(23, tail.oldNo(), "折叠应从第 23 行开始");
    }

    @Test
    void 相邻改动合并成一块不产生折叠() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10";
        String after = "1\nX\nY\n4\n5\nZ\n7\n8\n9\n10";

        // 上下文给到 5 行, 足以覆盖整份 10 行的文件
        TextDiff.Result r = TextDiff.unified(before, after, 5, 200);

        assertTrue(r.lines().stream().noneMatch(l -> "skip".equals(l.type())),
                "上下文足够覆盖时应合成一个连续区间, 不该出现折叠");
        assertEquals(13, r.lines().size(), "7 条上下文 + 3 条删除 + 3 条新增");
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

        assertTrue(r.truncated());
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
    void 文件尾部追加时行号对齐且前面只折叠未显示的部分() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10";
        String after = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11";

        TextDiff.Result r = TextDiff.unified(before, after, 3, 200);

        // 前 7 行被折叠（第 8–10 行是 3 行上下文）
        TextDiff.DiffLine head = r.lines().get(0);
        assertEquals("skip", head.type());
        assertEquals(1, head.oldNo());
        assertEquals(7, head.skipped());

        TextDiff.DiffLine last = r.lines().get(r.lines().size() - 1);
        assertEquals("add", last.type());
        assertEquals(11, last.newNo(), "新行应是改后文件的第 11 行");
        assertEquals("11", last.text());
    }

    @Test
    void 展开全文时不产生折叠标记() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            before.append("l").append(i).append('\n');
            after.append(i == 50 ? "CHANGED" : "l" + i).append('\n');
        }

        // 上下文给到足够大 = 前端点「展开全文」
        TextDiff.Result r = TextDiff.unified(before.toString(), after.toString(), 1000, 2000);

        assertTrue(r.lines().stream().noneMatch(l -> "skip".equals(l.type())),
                "展开全文时不该再有折叠条");
        // 替换一行 = 一条 del + 一条 add, 所以是 100 行 + 1 条新增行
        assertEquals(101, r.lines().size(), "改前 100 行 + 替换产生的新行都应输出");
        assertTrue(has(r, "del", "l50"));
        assertTrue(has(r, "add", "CHANGED"));
    }

    @Test
    void 空对空没有任何输出() {
        TextDiff.Result r = TextDiff.unified("", "");

        assertTrue(r.lines().isEmpty());
        assertTrue(r.isEmpty());
    }

    private static boolean has(TextDiff.Result r, String type, String text) {
        return r.lines().stream().anyMatch(l -> type.equals(l.type()) && text.equals(l.text()));
    }
}
