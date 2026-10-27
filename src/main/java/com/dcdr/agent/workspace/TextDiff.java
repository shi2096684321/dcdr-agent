package com.dcdr.agent.workspace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 行级文本差异(V3.3 F-49 变更面板)。
 * <p>
 * <b>输出结构化行而不是拼好的文本</b>: 前端要画行号、要能把"这里省略了 42 行"渲染成可点击的
 * 折叠条、还要能切成并排视图。这三种渲染方式共用同一份数据, 拼死成字符串就全做不到了。
 * <p>
 * <b>为什么不引 diff 库</b>: 需求只有"给人看", 不要求生成能 {@code git apply} 的补丁,
 * 也不做字符级 diff。一个 60 行的 LCS 实现足以, 多一个依赖就多一份升级与体积负担。
 * <p>
 * <b>性能上的两个关键取舍</b>:
 * <ol>
 *   <li><b>先裁公共前后缀</b>: 一次改动通常只占文件末尾或开头的一小段, 裁完之后真正要做 LCS 的
 *       行数往往只有个位数 —— 这是"改一行"在大文件上也能瞬间出结果的主要原因。</li>
 *   <li><b>DP 有硬上限</b>: LCS 是 O(n×m) 内存。整文件重写(前后缀全裁不到)时若照样开 DP,
 *       一个 5000 行的文件要 2 亿个格子, 直接 OOM。超过上限退化为"全删 + 全插",
 *       视觉上仍然准确地表达了"整个文件被重写了"。</li>
 * </ol>
 */
public final class TextDiff {

    /** DP 单元格数上限; 1000×1000 的 int 矩阵约 4MB, 单次请求可接受 */
    private static final long MAX_DP_CELLS = 1_000_000L;

    private static final int DEFAULT_CONTEXT = 3;

    /**
     * 一行 diff。
     * <p>
     * {@code type} 取值: {@code equal} / {@code del} / {@code add} / {@code skip}。
     * {@code oldNo}/{@code newNo} 是 1-based 行号, 该侧不适用时为 0。
     * {@code skip} 表示"这一段没改动、已折叠", 此时 {@code skipped} 是被折叠的行数。
     */
    public record DiffLine(String type, int oldNo, int newNo, String text, int skipped) {

        static DiffLine equal(int oldNo, int newNo, String text) {
            return new DiffLine("equal", oldNo, newNo, text, 0);
        }

        static DiffLine del(int oldNo, String text) {
            return new DiffLine("del", oldNo, 0, text, 0);
        }

        static DiffLine add(int newNo, String text) {
            return new DiffLine("add", 0, newNo, text, 0);
        }

        static DiffLine skip(int oldNo, int newNo, int count) {
            return new DiffLine("skip", oldNo, newNo, "", count);
        }
    }

    /** 对比结果; {@code lines} 已含折叠标记, 前端可直接逐行渲染 */
    public record Result(List<DiffLine> lines, int added, int removed, boolean truncated, boolean coarse) {

        public boolean isEmpty() {
            return added == 0 && removed == 0;
        }
    }

    private enum Kind { EQUAL, DELETE, INSERT }

    /** {@code oldPos}/{@code newPos} 是 0-based 位置: 该行在对应文件中的下标 */
    private record Edit(Kind kind, String text, int oldPos, int newPos) {
    }

    private TextDiff() {
    }

    public static Result unified(String before, String after) {
        return unified(before, after, DEFAULT_CONTEXT, 200);
    }

    /**
     * @param contextLines 每个改动块上下保留的相同行数; 传很大的值即为"展开全文"
     * @param maxLines     输出行数上限(含折叠标记); 超出截断并置 {@code truncated}
     */
    public static Result unified(String before, String after, int contextLines, int maxLines) {
        List<String> a = splitLines(before);
        List<String> b = splitLines(after);

        // 1. 裁剪公共前后缀, 把 LCS 的规模压到"真正变化的那一段"
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);

        if (midA.isEmpty() && midB.isEmpty()) {
            return new Result(List.of(), 0, 0, false, false);
        }

        // 2. 求编辑脚本。
        // 裁掉的前后缀必须作为 EQUAL 补回列表 —— 否则中间段两侧的公共行不在脚本里,
        // hunk 就取不到上下文, 输出的 diff 会只剩孤零零的 -/+ 两行, 看不出改在哪。
        List<Edit> edits = new ArrayList<>(a.size() + b.size());
        for (int i = 0; i < prefix; i++) {
            edits.add(new Edit(Kind.EQUAL, a.get(i), i, i));
        }
        boolean coarse = (long) (midA.size() + 1) * (midB.size() + 1) > MAX_DP_CELLS;
        edits.addAll(coarse ? coarseEdits(midA, midB, prefix) : lcsEdits(midA, midB, prefix));
        for (int i = 0; i < suffix; i++) {
            int ai = a.size() - suffix + i;
            int bi = b.size() - suffix + i;
            edits.add(new Edit(Kind.EQUAL, a.get(ai), ai, bi));
        }

        int added = 0;
        int removed = 0;
        for (Edit e : edits) {
            if (e.kind() == Kind.INSERT) added++;
            else if (e.kind() == Kind.DELETE) removed++;
        }

        // 3. 按上下文合并成 hunk。
        //    上下文按"行"扩, 不是按"编辑条目"扩: 一次替换会产生 DELETE+INSERT 两条,
        //    若直接对条目下标 ±ctx, 替换处下文就会少一行, 看起来上下不对称。
        List<int[]> blocks = new ArrayList<>();
        int k = 0;
        while (k < edits.size()) {
            if (edits.get(k).kind() == Kind.EQUAL) {
                k++;
                continue;
            }
            int start = k;
            while (k < edits.size() && edits.get(k).kind() != Kind.EQUAL) k++;
            blocks.add(new int[]{start, k - 1});
        }

        List<int[]> hunks = new ArrayList<>();
        int ctx = Math.max(0, contextLines);
        for (int[] block : blocks) {
            int start = block[0];
            int extend = 0;
            while (start > 0 && extend < ctx && edits.get(start - 1).kind() == Kind.EQUAL) {
                start--;
                extend++;
            }
            int end = block[1];
            extend = 0;
            while (end < edits.size() - 1 && extend < ctx && edits.get(end + 1).kind() == Kind.EQUAL) {
                end++;
                extend++;
            }
            // 与上一块相邻或重叠就并成一块, 否则会出现一堆只隔一两行的碎片 hunk
            if (!hunks.isEmpty() && start <= hunks.get(hunks.size() - 1)[1] + 1) {
                hunks.get(hunks.size() - 1)[1] = Math.max(hunks.get(hunks.size() - 1)[1], end);
            } else {
                hunks.add(new int[]{start, end});
            }
        }

        // 4. 渲染。块与块之间、以及首尾的空档都插一条 skip, 让用户知道"这里还有多少行没显示"。
        //    不做这一步的话, 界面上两个 @@ 块直接挨着, 看起来像文件只有这么长。
        List<DiffLine> lines = new ArrayList<>();
        boolean truncated = false;
        int consumedOld = 0;
        int consumedNew = 0;

        outer:
        for (int[] hunk : hunks) {
            int firstOld = edits.get(hunk[0]).oldPos();
            int gap = firstOld - consumedOld;
            if (gap > 0) {
                if (lines.size() >= maxLines) {
                    truncated = true;
                    break;
                }
                lines.add(DiffLine.skip(consumedOld + 1, consumedNew + 1, gap));
                // 折叠掉的也是"已消费的行"。不累加的话, 后面每块的行号与尾部长度都会算错,
                // 表现为越往后偏差越大
                consumedOld += gap;
                consumedNew += gap;
            }

            for (int idx = hunk[0]; idx <= hunk[1]; idx++) {
                if (lines.size() >= maxLines) {
                    truncated = true;
                    break outer;
                }
                Edit e = edits.get(idx);
                lines.add(switch (e.kind()) {
                    case EQUAL -> DiffLine.equal(e.oldPos() + 1, e.newPos() + 1, e.text());
                    case DELETE -> DiffLine.del(e.oldPos() + 1, e.text());
                    case INSERT -> DiffLine.add(e.newPos() + 1, e.text());
                });
                if (e.kind() != Kind.INSERT) consumedOld++;
                if (e.kind() != Kind.DELETE) consumedNew++;
            }
        }

        // 尾部的未显示行
        if (!truncated) {
            int tail = a.size() - consumedOld;
            if (tail > 0) {
                if (lines.size() >= maxLines) {
                    truncated = true;
                } else {
                    lines.add(DiffLine.skip(consumedOld + 1, consumedNew + 1, tail));
                }
            }
        }
        return new Result(lines, added, removed, truncated, coarse);
    }

    // ===== 内部 =====

    private static List<Edit> lcsEdits(List<String> a, List<String> b, int offset) {
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.get(i).equals(b.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }

        List<Edit> edits = new ArrayList<>(n + m);
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                edits.add(new Edit(Kind.EQUAL, a.get(i), offset + i, offset + j));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                edits.add(new Edit(Kind.DELETE, a.get(i), offset + i, offset + j));
                i++;
            } else {
                edits.add(new Edit(Kind.INSERT, b.get(j), offset + i, offset + j));
                j++;
            }
        }
        // 尾部残余: 两侧不可能同时非空
        while (i < n) {
            edits.add(new Edit(Kind.DELETE, a.get(i), offset + i, offset + j));
            i++;
        }
        while (j < m) {
            edits.add(new Edit(Kind.INSERT, b.get(j), offset + i, offset + j));
            j++;
        }
        return edits;
    }

    /** 超大改动: 不比较, 直接"整段删 + 整段插" */
    private static List<Edit> coarseEdits(List<String> a, List<String> b, int offset) {
        List<Edit> edits = new ArrayList<>(a.size() + b.size());
        for (int i = 0; i < a.size(); i++) {
            edits.add(new Edit(Kind.DELETE, a.get(i), offset + i, offset));
        }
        for (int j = 0; j < b.size(); j++) {
            edits.add(new Edit(Kind.INSERT, b.get(j), offset + a.size(), offset + j));
        }
        return edits;
    }

    /**
     * 拆行并统一换行符。
     * <p>
     * 末尾那个由"文件以换行结尾"产生出来的空元素被丢掉: 否则每个文件都会莫名多出一行
     * 空行的改动, 看着像改过, 实际只是换行符位置。
     */
    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
