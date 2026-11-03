package com.dcdr.agent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentOrchestrator#rebuildCompact(List)} 的回灌口径测试。
 *
 * <p>被测方法负责把联网搜索结果拼成喂给模型的文本, 是"模型到底看到了多少内容"的唯一出口。
 * 它是包级 static(见方法注释), 故测试类放在同一包内直接调用, 无需启动 Spring。
 *
 * <p><b>本测试锁的是一个真实 bug</b>: 早期实现只认 {@code content} 与 {@code snippet},
 * 把 DeepSeek 搜索路径每条自带的 {@code summary}(600~800 字符)整段丢弃,
 * 于是没被抓取的那些来源只剩约 110 字符的一句话。模型据此判断"信息不足"继续换词搜索 ——
 * 线上表现是搜了五六次、每次 15 条, 却反复搜同一个主题。
 * 实测(2026-11-02 21:18 那次 turn): 61 条无 content 的来源 summary 平均 734 字符,
 * 实际喂给模型的只有 111 字符。
 */
class RebuildCompactTest {

    private static final String ARTICLE_URL = "https://example.com/job/1986021465.shtml";

    @Test
    @DisplayName("抓取到正文时正文优先, 但 search 摘要作为补充保留")
    void prefersContentButKeepsSummary() {
        Map<String, Object> s = src(ARTICLE_URL, "岗位标题");
        s.put("content", "抓取到的完整正文");
        s.put("summary", "搜索摘要");
        s.put("snippet", "搜索片段");

        String out = AgentOrchestrator.rebuildCompact(List.of(s));

        assertTrue(out.contains("抓取到的完整正文"), "content 是抓来的真正文, 优先占大头");
        // 早期版本在抓到正文后把 summary 整条丢掉, 于是 600~800 字的提炼摘要被
        // 800 字的"正文前段"(常含导航)顶掉。摘要的价值是正文里没有的那部分信息
        assertTrue(out.contains("搜索摘要"), "摘要不该被静默丢弃");
        assertFalse(out.contains("搜索片段"), "snippet 只是摘要缺失时的兜底, 两者都在时不该出现");
    }

    @Test
    @DisplayName("正文开头是导航块时, 按 query 挑出真正相关的那段")
    void skipsNavigationBlockAndPicksRelevantSpan() {
        // 真实样本: yupao 招聘页 1270 字正文, 前 300 字全是无标点拼接的导航与筛选项。
        // cleanMarkdownContent 清不掉它(没有分隔符), 取开头等于喂给模型半屏噪音
        String nav = "首页职位公司校园意外险下载APP我要招聘我要找工作".repeat(10) + "筛选项NOISE";
        String real = "前端开发工程师 1.1-1.7万/月 职位详情：熟练掌握 Java 与 Vue，"
                + "三年以上 Web 开发经验，统招本科学历。福利：双休、五险一金、年终奖。";
        Map<String, Object> s = src(ARTICLE_URL, "前端开发工程师");
        s.put("content", nav + real.repeat(30));

        String out = AgentOrchestrator.rebuildCompact(List.of(s), "长沙 Java 开发工程师 社招");

        assertTrue(out.contains("1.1-1.7万"), "应挑中岗位正文, 实际输出: " + out);
        assertFalse(out.contains("筛选项NOISE"),
                "导航块不该出现在回灌内容里(它占了配额却不含信息), 实际输出: " + out);
    }

    @Test
    @DisplayName("query 为空时退回取开头, 与改造前行为一致")
    void fallsBackToHeadWhenQueryMissing() {
        Map<String, Object> s = src(ARTICLE_URL, "标题");
        String body = "开头内容。" + "后面的内容。".repeat(200);
        s.put("content", body);

        String out = AgentOrchestrator.rebuildCompact(List.of(s));

        assertTrue(out.contains("开头内容"), "没有 query 时无从判断相关性, 只能取开头");
    }

    @Test
    @DisplayName("摘要只是正文的复述时不重复拼接")
    void doesNotDuplicateSummaryThatMirrorsContent() {
        String body = "前端开发工程师 1.1-1.7万/月 职位详情：熟练掌握 Java 与 Vue。" + "补充说明。".repeat(20);
        Map<String, Object> s = src(ARTICLE_URL, "标题");
        s.put("content", body);
        s.put("summary", "前端开发工程师 1.1-1.7万/月 职位详情");   // 正文前 20 字的复述

        String out = AgentOrchestrator.rebuildCompact(List.of(s), "开发工程师");

        assertFalse(out.contains("（搜索摘要）"), "复述内容再拼一遍只会浪费配额");
    }

    @Test
    @DisplayName("没有 content 时用 summary, 而不是退化成 snippet —— 这是本次修复的核心")
    void fallsBackToSummaryWhenNoContent() {
        Map<String, Object> s = src(ARTICLE_URL, "岗位标题");
        s.put("summary", "长".repeat(700));      // 真实分布: 平均 734 字符
        s.put("snippet", "短");                  // 真实分布: 平均 111 字符

        String out = AgentOrchestrator.rebuildCompact(List.of(s));

        assertTrue(out.contains("长".repeat(700)),
                "summary 应完整进入回灌内容; 旧实现只给 1 个字的 snippet");
        assertFalse(out.contains("\n    短"), "有 summary 时不该再用 snippet");
    }

    @Test
    @DisplayName("summary 与 snippet 都没有时回落 snippet")
    void fallsBackToSnippet() {
        Map<String, Object> s = src(ARTICLE_URL, "岗位标题");
        s.put("snippet", "只有片段可用");

        String out = AgentOrchestrator.rebuildCompact(List.of(s));

        assertTrue(out.contains("只有片段可用"));
    }

    @Test
    @DisplayName("undefined 字符串视为缺失, 不能喂给模型")
    void treatsUndefinedAsMissing() {
        Map<String, Object> s = src(ARTICLE_URL, "岗位标题");
        s.put("content", "undefined");
        s.put("summary", "真实摘要");

        String out = AgentOrchestrator.rebuildCompact(List.of(s));

        assertFalse(out.contains("undefined"), "部分 Tavily 版本会返回字符串 undefined");
        assertTrue(out.contains("真实摘要"));
    }

    @Test
    @DisplayName("单条超长正文按单条上限截断, 不会一条吃掉整个预算")
    void clipsSingleLongBody() {
        Map<String, Object> s = src(ARTICLE_URL, "岗位标题");
        s.put("content", "正".repeat(5000));

        String out = AgentOrchestrator.rebuildCompact(List.of(s));

        assertTrue(out.length() < 2000, "单条上限 1000 字符, 加上标题/序号远不到 2000");
        assertTrue(out.contains("…"), "截断应带省略号");
    }

    @Test
    @DisplayName("多条结果各自分到额度, 不会被前面的条目挤没")
    void distributesBudgetAcrossItems() {
        List<Map<String, Object>> srcs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            Map<String, Object> s = src("https://example.com/article/" + i + ".html", "标题" + i);
            s.put("summary", "内".repeat(800));
            srcs.add(s);
        }

        String out = AgentOrchestrator.rebuildCompact(srcs);

        // 20 条 × 800 = 16000 字符, 超过 12000 总预算, 必须被均摊而不是整段截断
        assertTrue(out.length() < 15000, "总长应受 COMPACT_TOTAL_CHARS 约束, 实际=" + out.length());
        assertTrue(out.contains("内".repeat(300)),
                "后面的条目也必须留有可观内容 —— 否则和旧版的硬截断没区别");
        for (int i = 1; i <= 20; i++) {
            assertTrue(out.contains("标题" + i), "第 " + i + " 条不应因为预算分配而消失");
        }
    }

    @Test
    @DisplayName("每个来源都带递增的 [[n]] 引用序号")
    void numbersSourcesSequentially() {
        Map<String, Object> a = src("https://example.com/a/1.html", "甲");
        a.put("snippet", "甲的内容");
        Map<String, Object> b = src("https://example.com/a/2.html", "乙");
        b.put("snippet", "乙的内容");

        String out = AgentOrchestrator.rebuildCompact(List.of(a, b));

        assertTrue(out.contains("[[1]] 甲"), "实际输出: " + out);
        assertTrue(out.contains("[[2]] 乙"), "实际输出: " + out);
    }

    @Test
    @DisplayName("三者皆空的来源被剔除, 只有 summary 的来源要保留")
    void dropsEmptyButKeepsSummaryOnly() {
        Map<String, Object> empty = src("https://example.com/a/empty.html", "空壳");
        Map<String, Object> summaryOnly = src("https://example.com/a/sum.html", "有摘要");
        summaryOnly.put("summary", "这条只有 summary, 不该被当噪音删掉");

        String out = AgentOrchestrator.rebuildCompact(List.of(empty, summaryOnly));

        assertFalse(out.contains("空壳"), "title+url 无信息量的条目是结构化噪音");
        assertTrue(out.contains("这条只有 summary"), "只按 content/snippet 判空会误删它");
    }

    @Test
    @DisplayName("空结果给出明确提示, 不让模型凭空编")
    void handlesEmptyInput() {
        assertTrue(AgentOrchestrator.rebuildCompact(List.of()).contains("未返回可用结果"));
        assertTrue(AgentOrchestrator.rebuildCompact(null).contains("未返回可用结果"));
    }

    // ===== 辅助 =====

    private static Map<String, Object> src(String url, String title) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("title", title);
        return m;
    }
}
