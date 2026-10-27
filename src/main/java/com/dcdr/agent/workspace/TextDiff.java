package com.dcdr.agent.workspace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 行级文本差异(V3.3 F-49 前端 diff 展示用)。
 * <p>
 * <b>为什么不引 diff 库</b>: 这里的需求只有"给人看", 不要求生成能 {@code git apply} 的补丁,
 * 也不做字符级 diff。一个 60 行的 LCS 实现足以, 而多一个依赖就多一份升级与体积负担。
 * <p>
 * <b>性能上的两个关键取舍</b>:
 * <ol>
 *   <li><b>先裁公共前后缀</b>: 一次改动通常只占文件末尾或开头的一小段, 裁完之后真正要做 LCS 的
 *       行数往往只有个位数 —— 这是让"改一行"在大文件上也能瞬间出结果的主要原因。</li>
 *   <li><b>DP 有硬上限</b>: LCS 是 O(n×m) 内存。整文件重写(前后缀全裁不到)时若照样开 DP,
 *       一个 5000 行的文件要 2 亿个格子, 直接 OOM。超过上限退化为"全删 + 全插",
 *       视觉上仍然准确地表达了"整个文件被重写了"。</li>
 * </ol>
 */
public final class TextDiff {

    /** DP 单元格数上限; 1000×1000 的 int 矩阵约 4MB, 单次请求可接受 */
    private static final long MAX_DP_CELLS = 1_000_000L;

    private static final int DEFAULT_CONTEXT = 3;

    /** 对比结果; {@code lines} 已格式化好, 前端直接逐行渲染 */
    public record Result(List<String> lines, int added, int removed, boolean truncated, boolean coarse) {

        public boolean isEmpty() {
            return added == 0 && removed == 0;
        }
    }

    private enum Kind { EQUAL, DELETE, INSERT }

    /** {@code oldPos}/{@code newPos} 是 0-based 位置: DELETE 用 oldPos, INSERT 用 newPos */
    private record Edit(Kind kind, String text, int oldPos, int newPos) {
    }

    private TextDiff() {
    }

    public static Result unified(String before, String after) {
        return unified(before, after, DEFAULT_CONTEXT, 200);
    }

    /**
     * @param contextLines 每个改动块上下保留的相同行数
     * @param maxLines     输出行数上限(含 {@code @@} 头); 超出截断并置 {@code truncated}
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

        // 3. 按上下文合并成 hunk
        List<int[]> hunks = new ArrayList<>();
        int ctx = Math.max(0, contextLines);
        for (int k = 0; k < edits.size(); k++) {
            if (edits.get(k).kind() == Kind.EQUAL) continue;
            int start = Math.max(0, k - ctx);
            int end = Math.min(edits.size() - 1, k + ctx);
            // 与上一块相邻或重叠就并成一块, 否则会出现一堆只隔一两行的碎片 hunk
            if (!hunks.isEmpty() && start <= hunks.get(hunks.size() - 1)[1] + 1) {
                hunks.get(hunks.size() - 1)[1] = Math.max(hunks.get(hunks.size() - 1)[1], end);
            } else {
                hunks.add(new int[]{start, end});
            }
        }

        // 4. 渲染
        List<String> lines = new ArrayList<>();
        boolean truncated = false;
        outer:
        for (int[] hunk : hunks) {
            int oldCount = 0;
            int newCount = 0;
            for (int k = hunk[0]; k <= hunk[1]; k++) {
                Kind kind = edits.get(k).kind();
                if (kind != Kind.INSERT) oldCount++;
                if (kind != Kind.DELETE) newCount++;
            }
            // oldPos/newPos 已是含前缀偏移的绝对 0-based 位置, 这里只做 1-based 转换
            String header = "@@ -" + (edits.get(hunk[0]).oldPos() + 1) + "," + oldCount
                    + " +" + (edits.get(hunk[0]).newPos() + 1) + "," + newCount + " @@";
            if (lines.size() >= maxLines) {
                truncated = true;
                break;
            }
            lines.add(header);
            for (int k = hunk[0]; k <= hunk[1]; k++) {
                Edit e = edits.get(k);
                if (lines.size() >= maxLines) {
                    truncated = true;
                    break outer;
                }
                char sign = switch (e.kind()) {
                    case EQUAL -> ' ';
                    case DELETE -> '-';
                    case INSERT -> '+';
                };
                lines.add(sign + e.text());
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
     * {@code -}/{@code +} 的空行, 看着像改动, 实际只是换行符位置。
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
