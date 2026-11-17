package com.dcdr.agent.model.provider;

import com.dcdr.agent.agent.memory.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * system 归一化的规则测试（{@link MessageNormalizer#normalize}）。
 *
 * <p>锁的是"上游只会收到一条 system 且位于索引 0"这条硬约束 —— 自部署 Qwen3 系
 * (vLLM chat_template)对第二条 system 直接 400, 而编排层本就按片段注入多条,
 * 所以这里回归一次就等于全链路再次不可用。
 *
 * <p>同时锁住"稳定片段置顶、每轮变量折进 user"的分工: 折错方向(变量提到头部)会让
 * 精确到秒的"当前时间"把第 0 条消息每轮都改掉, 前缀缓存直接归零; 该折没折(变量留成中段 system)
 * 则回到 400。两边都不能越界。
 */
class MessageNormalizerTest {

    private static Message sys(String name, String text) {
        return Message.system(name, text);
    }

    @Test
    @DisplayName("首段多条 system 合并成一条并置顶, 顺序即优先级")
    void mergesLeadingSystemToHead() {
        List<Message> history = new ArrayList<>();
        history.add(sys(null, "平台规则"));
        history.add(sys("runtime:static", "多轮规范"));
        history.add(Message.user("你好"));
        history.add(Message.assistant("你好, 有什么可以帮你?", null));

        List<Message> out = MessageNormalizer.normalize(history);

        assertEquals(3, out.size(), "两条 system 合成一条, 两条对话消息不丢");
        assertEquals("system", out.get(0).getRole());
        String head = out.get(0).getContent();
        assertTrue(head.indexOf("平台规则") < head.indexOf("多轮规范"), "保持原顺序: " + head);
        assertEquals("user", out.get(1).getRole());
        assertEquals("assistant", out.get(2).getRole());
        assertEquals("你好", out.get(1).getContent(), "没有被折叠的 user 消息正文不能被改动");
        assertEquals(4, history.size(), "入参不被修改");
    }

    @Test
    @DisplayName("中段 system(记忆/时间/RAG)折进前面最近的 user, 头部只放稳定片段")
    void foldsMiddleSystemIntoUser() {
        List<Message> history = new ArrayList<>();
        history.add(sys(null, "平台规则"));
        history.add(Message.user("帮我看看这个接口"));
        history.add(sys("memory:long-term", "用户偏好 Go"));
        history.add(sys("runtime:current-time", "现在是 2026-11-16 16:40"));

        List<Message> out = MessageNormalizer.normalize(history);

        assertEquals(2, out.size(), "折叠后只剩 头部 system + 一条 user");
        String head = out.get(0).getContent();
        assertFalse(head.contains("用户偏好 Go"),
                "每轮变量不能进头部, 否则精确到秒的时间会让整段前缀每轮失效: " + head);

        Message user = out.get(1);
        assertEquals("user", user.getRole());
        String content = user.getContent();
        assertTrue(content.startsWith("帮我看看这个接口"), "用户原话在前: " + content);
        assertTrue(content.contains("<context>") && content.contains("</context>"), "上下文要包在标记里");
        assertTrue(content.contains("[memory:long-term]"), "折叠后仍要保留来源名, 否则查不出这段是哪来的");
        assertTrue(content.indexOf("用户偏好 Go") < content.indexOf("现在是 2026-11-16"), "片段顺序保持");
    }

    @Test
    @DisplayName("多轮工具往返: 变量折进本轮 user, 后面的 assistant/tool 消息顺序不变")
    void foldsIntoCurrentTurnOnly() {
        List<Message> history = new ArrayList<>();
        history.add(sys(null, "平台规则"));
        history.add(Message.user("第一问"));
        history.add(Message.assistant("第一答", null));
        history.add(Message.user("第二问"));
        history.add(sys("runtime:rag-context", "检索到的片段"));
        history.add(Message.assistant(null, null));
        Message tool = new Message();
        tool.setRole("tool");
        tool.setToolCallId("call_1");
        tool.setContent("工具结果");
        history.add(tool);

        List<Message> out = MessageNormalizer.normalize(history);

        assertEquals(6, out.size(), "一条头部 system + 原来的 5 条非 system");
        assertTrue(out.get(3).getContent().contains("检索到的片段"), "应折进第二问, 而不是第一问");
        assertFalse(out.get(1).getContent().contains("检索到的片段"));
        assertEquals("tool", out.get(5).getRole());
        assertEquals("工具结果", out.get(5).getContent(), "工具消息不能被折叠逻辑动到");
    }

    @Test
    @DisplayName("已经是目标形状: 原样返回, 不拷贝")
    void keepsCompliantShapeUntouched() {
        List<Message> history = new ArrayList<>();
        history.add(sys(null, "平台规则"));
        history.add(Message.user("问题"));
        history.add(Message.assistant("回答", null));

        assertSame(history, MessageNormalizer.normalize(history));
    }

    @Test
    @DisplayName("没有 system: 原样返回")
    void passesThroughWithoutSystem() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("问题"));
        history.add(Message.assistant("回答", null));

        assertSame(history, MessageNormalizer.normalize(history));
    }

    @Test
    @DisplayName("空片段跳过: 不产生空 context 块, 也不占头部位置")
    void skipsBlankFragments() {
        List<Message> history = new ArrayList<>();
        history.add(sys("runtime:static", "  "));
        history.add(Message.user("问题"));
        history.add(sys("runtime:rag-context", ""));

        List<Message> out = MessageNormalizer.normalize(history);

        assertEquals(1, out.size(), "全是空片段时只留 user, 连头部 system 都不产生");
        assertEquals("问题", out.get(0).getContent(), "没有有效片段就不该加 <context> 空块");
    }

    @Test
    @DisplayName("折叠保留图片等字段: 多模态轮不能因为折叠丢了图片")
    void keepsImagesWhenFolding() {
        List<Message> history = new ArrayList<>();
        history.add(sys(null, "平台规则"));
        history.add(Message.user("这张图里是什么", List.of("https://cdn.example.com/a.png")));
        history.add(sys("runtime:current-time", "现在是 16:40"));

        List<Message> out = MessageNormalizer.normalize(history);

        Message folded = out.get(1);
        assertEquals(1, folded.getImages().size());
        assertEquals("https://cdn.example.com/a.png", folded.getImages().get(0));
        assertTrue(folded.getContent().contains("这张图里是什么"));
    }

    @Test
    @DisplayName("全是 system(极端情况): 合并成一条, 保证上游只收到一条")
    void mergesAllSystemHistory() {
        List<Message> history = new ArrayList<>();
        history.add(sys(null, "平台规则"));
        history.add(sys("runtime:static", "多轮规范"));

        List<Message> out = MessageNormalizer.normalize(history);

        assertEquals(1, out.size());
        assertEquals("system", out.get(0).getRole());
        assertTrue(out.get(0).getContent().contains("多轮规范"));
    }

    @Test
    @DisplayName("null 历史: 返回空列表而不是 null, 调用方无需判空")
    void handlesNullHistory() {
        assertTrue(MessageNormalizer.normalize(null).isEmpty());
    }

    @Test
    @DisplayName("回归: 归一化后 system 只有一条且位于索引 0 —— 严格端点唯一接受的形状")
    void alwaysProducesSingleLeadingSystem() {
        List<Message> history = new ArrayList<>();
        history.add(sys(null, "平台规则"));
        history.add(sys("agent:persona", "人设"));
        history.add(Message.user("第一问"));
        history.add(Message.assistant("第一答", null));
        history.add(Message.user("第二问"));
        history.add(sys("memory:long-term", "记忆"));
        history.add(sys("runtime:current-time", "时间"));
        history.add(sys("runtime:rag-context", "片段"));

        List<Message> out = MessageNormalizer.normalize(history);

        assertEquals("system", out.get(0).getRole());
        long systems = out.stream().filter(m -> "system".equals(m.getRole())).count();
        assertEquals(1, systems, "vLLM/Qwen3 收到第二条 system 直接 400: "
                + out.stream().map(Message::getRole).toList());
        assertEquals("user", out.get(out.size() - 1).getRole(), "最后一条必须是非 system 消息");
        assertTrue(out.get(0).getContent().contains("人设"), "首段片段仍要合并进那一条");
    }

    @Test
    @DisplayName("折叠只换正文: user 的 seq/type/图片等元数据不能静默丢失")
    void foldingKeepsUserMetadata() {
        List<Message> history = new ArrayList<>();
        history.add(sys(null, "平台规则"));
        Message user = Message.user("问题", List.of("https://cdn.example.com/a.png"));
        user.setSeq(7);
        user.setType("image");
        user.setName("来源名");
        history.add(user);
        history.add(sys("runtime:current-time", "现在 16:40"));

        Message folded = MessageNormalizer.normalize(history).get(1);

        assertEquals(7, folded.getSeq());
        assertEquals("image", folded.getType());
        assertEquals("来源名", folded.getName());
        assertEquals(1, folded.getImages().size());
        assertTrue(folded.getContent().contains("现在 16:40"));
    }

    @Test
    @DisplayName("三轮回合: 稳定历史逐字复用, 每轮变量只落在末条 user —— 前缀缓存不被折叠破坏")
    void stableHistorySurvivesAcrossTurns() {
        // 模拟真实 history 生命周期: system 片段由编排层逐轮注入、不落库;
        // 下一轮从记忆层拿回的是纯 user/assistant 消息 + 本轮新注入的 system 片段。
        List<Message> t1 = new ArrayList<>();
        t1.add(sys(null, "平台规则"));
        t1.add(sys("agent:persona", "人设"));
        t1.add(Message.user("第一问"));
        t1.add(sys("runtime:current-time", "10:00:01"));

        List<Message> t2 = new ArrayList<>();
        t2.add(sys(null, "平台规则"));
        t2.add(sys("agent:persona", "人设"));
        t2.add(Message.user("第一问"));
        t2.add(Message.assistant("第一答", null));
        t2.add(Message.user("第二问"));
        t2.add(sys("runtime:current-time", "10:00:09"));

        List<Message> t3 = new ArrayList<>();
        t3.add(sys(null, "平台规则"));
        t3.add(sys("agent:persona", "人设"));
        t3.add(Message.user("第一问"));
        t3.add(Message.assistant("第一答", null));
        t3.add(Message.user("第二问"));
        t3.add(Message.assistant("第二答", null));
        t3.add(Message.user("第三问"));
        t3.add(sys("runtime:current-time", "10:00:21"));

        List<Message> w1 = MessageNormalizer.normalize(t1);
        List<Message> w2 = MessageNormalizer.normalize(t2);
        List<Message> w3 = MessageNormalizer.normalize(t3);

        // 剥掉末条 user 的 <context> 块: 供应商能复用的是前面这些逐字一致的 token,
        // 末条消息本来就不在缓存范围内。上一轮的稳定部分必须整体成为下一轮的前缀。
        assertEquals(stablePrefix(w1), stablePrefix(w2).subList(0, stablePrefix(w1).size()));
        assertEquals(stablePrefix(w2), stablePrefix(w3).subList(0, stablePrefix(w2).size()));

        // 首条 system 逐字相同 —— 变量若被提到头部, 这里立刻挂
        assertEquals(w1.get(0).getContent(), w2.get(0).getContent());
        assertEquals(w2.get(0).getContent(), w3.get(0).getContent());
        // 变量只在末条 user 上, 不会渗进历史消息
        assertTrue(w2.get(w2.size() - 1).getContent().contains("10:00:09"));
        assertFalse(w2.get(1).getContent().contains("10:00:01"));
    }

    /** 末条 user 去掉本轮 <context> 块后的 role + content 序列（供应商可复用的部分） */
    private static List<String> stablePrefix(List<Message> wire) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < wire.size(); i++) {
            Message m = wire.get(i);
            String content = m.getContent() == null ? "" : m.getContent();
            if (i == wire.size() - 1) {
                int p = content.indexOf("\n\n<context>");
                if (p >= 0) content = content.substring(0, p);
            }
            parts.add(m.getRole() + ":" + content);
        }
        return parts;
    }
}
