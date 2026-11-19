package com.dcdr.agent.agent.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 会话级知识库挂载(kb_ids)的解析口径。
 *
 * <p>为什么值得单独锁: 这一列是"逗号分隔的 TEXT", 脏值(多余空格/连续逗号/null)会变成无效的
 * 知识库 ID 直接传给检索层 —— 而检索层对不存在的 ID <b>不报错</b>, 只是静默少命中几条。
 * 这类问题在界面上表现为"引用了库但答案没依据", 没有任何日志会指到这里。
 */
class SessionKbIdsTest {

    @Test
    @DisplayName("null / 空白 → 空列表(未挂载), 不抛异常")
    void blankMeansNoKb() {
        assertEquals(List.of(), SessionManager.parseKbIds(null));
        assertEquals(List.of(), SessionManager.parseKbIds(""));
        assertEquals(List.of(), SessionManager.parseKbIds("   "));
    }

    @Test
    @DisplayName("去空格、去空段、去重, 顺序保持")
    void trimsDedupesAndKeepsOrder() {
        assertEquals(List.of("kb_a", "kb_b"), SessionManager.parseKbIds(" kb_a , kb_b ,, kb_a "));
        assertEquals(List.of("kb_b", "kb_a"), SessionManager.parseKbIds("kb_b,kb_a"));
    }
}
