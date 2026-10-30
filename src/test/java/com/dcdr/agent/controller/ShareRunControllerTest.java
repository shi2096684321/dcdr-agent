package com.dcdr.agent.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 详情页的 HTML 转义测试。
 * <p>
 * 正文来自 Agent 输出，可能包含 {@code <script>}。详情页是免登录的，一旦漏转义
 * 就是一个任何人都能触发的存储型 XSS —— Agent 只要把抓取到的网页原文吐出来就够触发。
 */
class ShareRunControllerTest {

    @Test
    @DisplayName("脚本标签被转义，不会在页面里执行")
    void escapesScriptTag() {
        String out = ShareRunController.esc("<script>alert('x')</script>");
        assertFalse(out.contains("<script>"));
        assertEquals("&lt;script&gt;alert('x')&lt;/script&gt;", out);
    }

    @Test
    @DisplayName("& 先转义，避免二次转义后出现 &amp;lt; 这种可见残留")
    void escapesAmpersandFirst() {
        assertEquals("&amp;lt;", ShareRunController.esc("&lt;"));
        assertEquals("&amp;amp;", ShareRunController.esc("&amp;"));
    }

    @Test
    @DisplayName("双引号转义，避免属性逃逸")
    void escapesQuote() {
        assertEquals("&quot;", ShareRunController.esc("\""));
    }

    @Test
    @DisplayName("null 与空串安全")
    void handlesNull() {
        assertEquals("", ShareRunController.esc(null));
        assertEquals("", ShareRunController.esc(""));
    }

    @Test
    @DisplayName("普通中文与换行原样保留")
    void keepsPlainText() {
        assertEquals("第一行\n第二行", ShareRunController.esc("第一行\n第二行"));
    }
}
