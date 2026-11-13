package com.dcdr.agent.model.provider;

import com.dcdr.agent.agent.memory.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「分段合并(split)」口径的规则测试 —— 前缀缓存能否生效完全取决于这一层。
 *
 * <p>归一化发生在唯一的协议出口, 因此<b>编排层的注入顺序只有经过这里才真正决定发出去的样子</b>。
 * 旧口径(全量置顶)会把每轮变化的内容(长期记忆 / 当前时间 / RAG)也提到最前面, 公共前缀在那里断掉,
 * 其后的全部对话历史都无法复用 —— 编排层再怎么重排都没用。
 *
 * <p>这里锁两件事:
 * <ol>
 *   <li>稳定片段置顶、每轮变量留末尾 —— 形状正确;</li>
 *   <li><b>三轮回合前缀单调增长</b> —— 上一轮的整条消息列表必须是下一轮消息列表的前缀
 *       (末尾变量段除外), 这是"缓存命中率提升"的充分条件, 也是最容易被后续改动悄悄破坏的性质。</li>
 * </ol>
 */
class MessageNormalizerSplitTest {

    /** 逐条构造: 断言失败时能看到是哪一条不一致 */
    private static Message system(String name, String text) {
        return Message.system(name, text);
    }

    /** 前 n 条 role+content 完全一致的消息条数(前缀长度) */
    private static int commonPrefix(List<Message> a, List<Message> b) {
        int n = Math.min(a.size(), b.size());
        int i = 0;
        for (; i < n; i++) {
            Message x = a.get(i);
            Message y = b.get(i);
            if (!String.valueOf(x.getRole()).equals(String.valueOf(y.getRole()))) break;
            if (!String.valueOf(x.getContent()).equals(String.valueOf(y.getContent()))) break;
            if (!String.valueOf(x.getName()).equals(String.valueOf(y.getName()))) break;
        }
        return i;
    }

    @Test
    @DisplayName("split: 头部 system 合成一条, 尾部 system 合成一条留在末尾")
    void splitsHeadAndTail() {
        List<Message> history = new ArrayList<>();
        history.add(system(null, "平台规则"));
        history.add(system("runtime:static", "多轮规范"));
        history.add(system("agent:persona", "人设"));
        history.add(Message.user("第一问"));
        history.add(Message.assistant("第一答", null));
        history.add(Message.user("第二问"));
        history.add(system("memory:long-term", "记忆片段"));
        history.add(system("runtime:current-time", "当前时间 10:00:01"));

        List<Message> out = MessageNormalizer.mergeSystemMessages(history, false);

        assertEquals(5, out.size(), "3 条头部 system → 1 条; 尾部 2 条 → 1 条; 3 条非 system 保持");
        assertEquals("system", out.get(0).getRole());
        String head = out.get(0).getContent();
        assertTrue(head.contains("平台规则") && head.contains("多轮规范") && head.contains("人设"),
                "头部稳定片段应合并且在最前: " + head);
        assertTrue(head.indexOf("平台规则") < head.indexOf("多轮规范")
                        && head.indexOf("多轮规范") < head.indexOf("人设"),
                "合并顺序即注入顺序, 不能被打乱: " + head);

        assertEquals("user", out.get(1).getRole());
        assertEquals("assistant", out.get(2).getRole());
        assertEquals("user", out.get(3).getRole());

        assertEquals("system", out.get(4).getRole(), "每轮变量必须在末尾, 不能在历史之前");
        String tail = out.get(4).getContent();
        assertTrue(tail.contains("记忆片段") && tail.contains("当前时间 10:00:01"),
                "尾部变量片段应合并且在末尾: " + tail);
        assertTrue(tail.indexOf("记忆片段") < tail.indexOf("当前时间 10:00:01"), "尾部顺序应保持");
    }

    @Test
    @DisplayName("split: 已是目标形状时原样返回, 不产生拷贝(每请求必经路径)")
    void noCopyWhenAlreadyShaped() {
        List<Message> history = new ArrayList<>();
        history.add(system(null, "平台规则"));
        history.add(Message.user("你好"));

        assertSame(history, MessageNormalizer.mergeSystemMessages(history, false));
    }

    @Test
    @DisplayName("声明 single_system 的严格端点: 两参重载 true 仍走全量置顶")
    void hoistModeStillAvailable() {
        List<Message> history = new ArrayList<>();
        history.add(system(null, "平台规则"));
        history.add(Message.user("问题"));
        history.add(system("runtime:current-time", "当前时间"));

        List<Message> hoisted = MessageNormalizer.mergeSystemMessages(history, true);

        assertEquals(2, hoisted.size());
        assertEquals("system", hoisted.get(0).getRole());
        assertTrue(hoisted.get(0).getContent().contains("当前时间"), "hoist 口径下时间被提到最前");
        assertEquals("user", hoisted.get(1).getRole());
    }

    @Test
    @DisplayName("三轮回合: 上一轮整条列表是下一轮的前缀(除末尾变量段) —— 缓存命中率提升的充分条件")
    void threeTurnsShareGrowingPrefix() {
        // 第 1 轮: 稳定前缀 + 首问 + 本轮变量
        List<Message> t1 = new ArrayList<>();
        t1.add(system(null, "平台规则"));
        t1.add(system("runtime:static", "多轮规范/外部数据边界/生图策略"));
        t1.add(system("agent:persona", "人设"));
        t1.add(system("skill:catalog", "技能目录"));
        t1.add(Message.user("第一问"));
        t1.add(system("memory:long-term", "记忆 A"));
        t1.add(system("runtime:current-time", "10:00:01"));

        // 第 2 轮: 历史(含上一轮问答) + 新提问 + 本轮变量(记忆/时间都变了)
        List<Message> t2 = new ArrayList<>(t1.subList(0, 5));
        t2.add(Message.assistant("第一答", null));
        t2.add(Message.user("第二问"));
        t2.add(system("memory:long-term", "记忆 B"));
        t2.add(system("runtime:current-time", "10:00:09"));

        // 第 3 轮(历史取前两轮的稳定部分 + 问答, 不含上一轮的变量段)
        List<Message> t3 = new ArrayList<>(t2.subList(0, 7));
        t3.add(Message.assistant("第二答", null));
        t3.add(Message.user("第三问"));
        t3.add(system("memory:long-term", "记忆 C"));
        t3.add(system("runtime:current-time", "10:00:21"));

        List<Message> w1 = MessageNormalizer.mergeSystemMessages(t1, false);
        List<Message> w2 = MessageNormalizer.mergeSystemMessages(t2, false);
        List<Message> w3 = MessageNormalizer.mergeSystemMessages(t3, false);

        // 第 1 轮 = [system(稳定)] [user] [system(变量)]
        assertEquals(3, w1.size());
        // 前缀应覆盖到 w1 的倒数第二条(即「稳定 system + 第一问」), 只有末尾变量段不可复用
        assertEquals(w1.size() - 1, commonPrefix(w1, w2),
                "第 1 轮除末尾变量段外必须整体成为第 2 轮的前缀");
        assertEquals(w2.size() - 1, commonPrefix(w2, w3),
                "第 2 轮除末尾变量段外必须整体成为第 3 轮的前缀");

        // 反证: 旧口径(全量置顶)下, 每轮变化的内容被提到最前, 公共前缀只剩那条 system 之前的部分,
        // 整段历史都不可复用 —— 这正是重排 + split 要消除的损失
        List<Message> h1 = MessageNormalizer.mergeSystemMessages(t1, true);
        List<Message> h2 = MessageNormalizer.mergeSystemMessages(t2, true);
        List<Message> h3 = MessageNormalizer.mergeSystemMessages(t3, true);
        assertEquals(0, commonPrefix(h1, h2),
                "hoist 口径下合并后的 system 本身就随轮次变化, 连第一条都无法复用");
        assertEquals(0, commonPrefix(h2, h3),
                "hoist 口径下合并后的 system 本身就随轮次变化, 连第一条都无法复用");
        assertTrue(commonPrefix(w2, w3) > commonPrefix(h2, h3),
                "split 口径的公共前缀必须显著长于 hoist 口径, 否则这次重排没有意义");
    }

    @Test
    @DisplayName("split: 只有尾部 system 时不动头部(不会凭空造出 system 消息)")
    void onlyTailSystemKeepsHead() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("问题"));
        history.add(system("runtime:current-time", "当前时间"));

        List<Message> out = MessageNormalizer.mergeSystemMessages(history, false);

        assertEquals(2, out.size());
        assertEquals("user", out.get(0).getRole());
        assertEquals("system", out.get(1).getRole());
        assertTrue(out.get(1).getContent().contains("当前时间"));
        assertNotEquals("system", out.get(0).getRole(), "头部不应被插入 system");
    }
}
