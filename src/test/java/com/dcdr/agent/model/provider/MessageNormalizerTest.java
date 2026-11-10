package com.dcdr.agent.model.provider;

import com.dcdr.agent.agent.memory.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单一 system 归一化的规则测试。
 *
 * <p>锁的是"上游只会收到一条 system 且位于索引 0"这条硬约束 —— 自部署 Qwen3 系
 * (vLLM chat_template)对第二条 system 直接 400, 而编排层本就按片段注入多条,
 * 所以这里回归一次就等于全链路再次不可用。
 */
class MessageNormalizerTest {

    @Test
    @DisplayName("多条 system 合并成一条并置顶, 顺序即优先级")
    void mergesAllSystemToHead() {
        List<Message> history = new ArrayList<>();
        history.add(Message.system("平台规则"));
        history.add(Message.user("你好"));
        history.add(Message.assistant("你好, 有什么可以帮你?", null));
        history.add(Message.system("runtime:current-time", "现在是 2026-11-10"));
        history.add(Message.system("runtime:skill", "可用技能: 报表"));

        List<Message> merged = MessageNormalizer.mergeSystemMessages(history);

        assertEquals(3, merged.size(), "3 条 system 应合成 1 条, 两条非 system 消息不丢");
        assertEquals("system", merged.get(0).getRole());
        String content = merged.get(0).getContent();
        assertTrue(content.indexOf("平台规则") < content.indexOf("现在是 2026-11-10"),
                "应保持原出现顺序(越靠后越具体): " + content);
        assertTrue(content.indexOf("现在是 2026-11-10") < content.indexOf("可用技能: 报表"),
                "应保持原出现顺序: " + content);
        // 非 system 消息的相对顺序不能被打乱
        assertEquals("user", merged.get(1).getRole());
        assertEquals("assistant", merged.get(2).getRole());
        assertEquals("你好", merged.get(1).getContent());
        // 入参不被修改
        assertEquals(5, history.size());
    }

    @Test
    @DisplayName("只有一条 system 但不在首位: 需要挪到最前(此时必须拷贝)")
    void movesLoneTrailingSystemToHead() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("问题"));
        history.add(Message.system("runtime:tool-loop-stop", "工具循环已停止"));

        List<Message> merged = MessageNormalizer.mergeSystemMessages(history);

        assertEquals(2, merged.size());
        assertEquals("system", merged.get(0).getRole());
        assertEquals("工具循环已停止", merged.get(0).getContent());
        assertEquals("user", merged.get(1).getRole());
    }

    @Test
    @DisplayName("本来就只有一条且在首位: 原样返回, 不产生拷贝")
    void keepsSingleLeadingSystemUntouched() {
        List<Message> history = new ArrayList<>();
        history.add(Message.system("平台规则"));
        history.add(Message.user("你好"));

        assertSame(history, MessageNormalizer.mergeSystemMessages(history));
    }

    @Test
    @DisplayName("没有 system: 原样返回")
    void passesThroughWithoutSystem() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("你好"));
        history.add(Message.assistant("在", null));

        assertSame(history, MessageNormalizer.mergeSystemMessages(history));
    }

    @Test
    @DisplayName("空内容 system 不参与合并(与映射层\"空消息不发送\"同口径)")
    void skipsBlankSystemFragments() {
        List<Message> history = new ArrayList<>();
        history.add(Message.system("   "));
        history.add(Message.system("平台规则"));

        List<Message> merged = MessageNormalizer.mergeSystemMessages(history);

        assertEquals(1, merged.size());
        assertEquals("平台规则", merged.get(0).getContent());
    }

    @Test
    @DisplayName("null 历史: 返回空列表而不是 null, 调用方无需判空")
    void handlesNullHistory() {
        assertTrue(MessageNormalizer.mergeSystemMessages(null).isEmpty());
    }
}
