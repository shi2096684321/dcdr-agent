package com.dcdr.agent.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IM 展示适配（Markdown 表格 → 列表）测试。
 * <p>
 * 背景：企微与钉钉群机器人的 markdown 都<b>不支持表格</b>，
 * 表格会被当普通文字发出去，用户看到的是一堆竖线 + 下划线分隔行。
 * 用例取自线上真实任务输出（"每日岗位推荐"，9 列表格）。
 */
class NotifyImRenderTest {

    private static final String REAL_TABLE = """
            | 公司 | 岗位 | 城市 | 年限要求 | 薪资 | 核心技术要求 | 发布时间 | 投递链接 | 内推 |
            |---|---|---|---|---|---|---|---|---|
            | 深圳市衡序云联科技有限公司 | Java开发工程师（支付方向） | 深圳 | 3-5年 | 15-25K | Java高并发/多线程, Redis/Kafka, 分布式事务 | 2026-10-30 | https://www.liepin.com/job/1986448923.shtml | 未标注 |
            | 高新兴科技集团股份有限公司 | Java工程师 | 广州 | 3-5年 | 12-20K·13薪 | Spring Cloud, Kafka/Redis | 2026-11-02 | https://www.liepin.com/job/1986446635.shtml | 未标注 |
            """;

    /** 线上实际输出: 公司名后带引用角标, 岗位字段较长 */
    private static final String REAL_RUN_TABLE = """
            | 公司 | 岗位 | 城市 | 年限要求 | 薪资 | 核心技术要求 | 发布时间 | 投递链接 | 内推 |
            |---|---|---|---|---|---|---|---|---|
            | 小满科技（阿里巴巴旗下）[[1]] | Java后端开发工程师（平台方向） | 深圳 | 3-5年 | 25-35K·16薪 | Spring Boot 基础框架/Starter、并发编程与JVM、MySQL+Redis+消息队列 | 11月2日更新 | https://www.liepin.com/job/1986471989.shtml | 未标注 |
            """;

    @Test
    @DisplayName("岗位卡片按钉钉要求硬换行, 引用角标不进入公司名")
    void jobCardsUseHardBreaksAndDropCitations() {
        String out = NotifyRender.forIm(REAL_RUN_TABLE);

        assertFalse(out.contains("[[1]]"), "IM 没有来源卡片, 角标应去掉:\n" + out);
        assertTrue(out.contains("**1. 小满科技（阿里巴巴旗下）**  \n"),
                "公司名后必须是「两空格 + 换行」, 单 \\n 会被钉钉折叠:\n" + out);
        assertTrue(out.contains("岗位：Java后端开发工程师（平台方向）｜城市：深圳｜年限要求：3-5年  \n"),
                out);
        assertTrue(out.contains("投递链接：[链接](https://www.liepin.com/job/1986471989.shtml)"), out);
    }

    @Test
    @DisplayName("真表格: 表头消失, 第一列变标题, 其余列变“列名：值”")
    void convertsRealTable() {
        String out = NotifyRender.forIm(REAL_TABLE);

        assertFalse(out.contains("| 公司 |"), "表格分隔符没有去掉:\n" + out);
        assertFalse(out.contains("---|"), "分隔行没有去掉:\n" + out);
        assertTrue(out.contains("**1. 深圳市衡序云联科技有限公司**"), out);
        assertTrue(out.contains("**2. 高新兴科技集团股份有限公司**"), out);
        assertTrue(out.contains("岗位：Java开发工程师（支付方向）"), out);
        assertTrue(out.contains("薪资：15-25K"), out);
    }

    @Test
    @DisplayName("裸 URL 压成 markdown 链接, 省下的字符足够多放一条")
    void urlCellBecomesLink() {
        String out = NotifyRender.forIm(REAL_TABLE);
        assertTrue(out.contains("[链接](https://www.liepin.com/job/1986448923.shtml)"), out);
        assertFalse(out.contains("投递链接：https://"), "URL 没有被压缩:\n" + out);
    }

    @Test
    @DisplayName("每行最多 3 组“列名：值”, 不把单行撑到几百字符")
    void wrapsEveryThreePairs() {
        String out = NotifyRender.forIm(REAL_TABLE);
        String body = out.lines()
                .filter(l -> l.contains("：") && l.contains("｜"))
                .findFirst().orElse("");
        // 城市｜年限｜薪资 正好 3 组
        assertEquals(2L, body.chars().filter(c -> c == '｜').count(),
                "首行应恰好 3 组（2 个分隔符）: " + body);
    }

    @Test
    @DisplayName("结论 + 表格的混排: 正文原样保留")
    void keepsSurroundingText() {
        String text = "今日筛选出 2 个匹配岗位：\n\n" + REAL_TABLE + "\n建议优先看第一家的支付方向。";
        String out = NotifyRender.forIm(text);

        assertTrue(out.startsWith("今日筛选出 2 个匹配岗位："), out);
        assertTrue(out.contains("**1. 深圳市衡序云联科技有限公司**"), out);
        assertTrue(out.endsWith("建议优先看第一家的支付方向。"), out);
    }

    @Test
    @DisplayName("没有分隔行的竖线文本不当表格, 原样保留")
    void doesNotTouchPseudoTable() {
        String text = "| 这是一行 | 普通文本 |\n| 另一行 | 也是 |";
        assertEquals(text, NotifyRender.forIm(text));
    }

    @Test
    @DisplayName("单元格里的转义竖线不会被拆列")
    void escapedPipeInsideCell() {
        String text = """
                | 名称 | 说明 |
                |---|---|
                | A | 含 \\| 竖线 |
                """;
        String out = NotifyRender.forIm(text);
        assertTrue(out.contains("**1. A**"), out);
        assertTrue(out.contains("含 | 竖线"), out);
    }

    @Test
    @DisplayName("空单元格跳过, 不留空字段名")
    void skipsBlankCells() {
        String text = """
                | 公司 | 城市 | 薪资 |
                |---|---|---|
                | 甲公司 |  | 20K |
                """;
        String out = NotifyRender.forIm(text);
        assertTrue(out.contains("薪资：20K"), out);
        assertFalse(out.contains("城市："), "空单元格不该输出字段名:\n" + out);
    }
}
