package com.dcdr.agent.tool.result;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolResultCompressor#microcompact(String, List)} 的策略测试(V3.5)。
 *
 * <p>锁的是「什么时候值得牺牲前缀缓存」这条判断:
 * <ul>
 *   <li><b>compact-first</b>(旧行为): 每轮都清, 上下文最省 —— 必须仍然可用;</li>
 *   <li><b>cache-first</b>(默认): 缓存窗口内不清(改写历史中段会让其后前缀全部失效),
 *       只在会话空闲超过 TTL、缓存已确定过期时才清。</li>
 * </ul>
 * 这两条分支都可能"看起来正常运行"却做错事: 前者清多了不会报错, 后者不清也不会报错,
 * 只会在账单与首字延迟上体现 —— 故必须显式锁定。
 */
class ToolResultCompressorMicrocompactTest {

    /** microcompact 只用到 properties, 其余依赖传 null 即可(方法内不触碰) */
    private static ToolResultCompressor compressor(boolean enabled, String strategy, int ttlSeconds) {
        AgentProperties props = new AgentProperties();
        AgentProperties.MicrocompactConfig cfg = props.getTools().getResult().getMicrocompact();
        cfg.setEnabled(enabled);
        cfg.setStrategy(strategy);
        cfg.setKeepRecent(2);
        cfg.setCacheTtlSeconds(ttlSeconds);
        return new ToolResultCompressor(props, null, null, null, null);
    }

    /**
     * 4 条可回收工具结果, 分两批: 前一批属于较早的轮次(可清), 尾部两条是"最近一轮"(必须保留)。
     * 尾部连续 tool 消息会被 {@code tailToolCount} 无条件保护, 所以中间必须有非 tool 消息。
     */
    private static List<Message> historyWithToolResults() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("查一下 A 和 B"));
        history.add(toolMessage("web_search", "A 的结果"));
        history.add(toolMessage("web_search", "B 的结果"));
        history.add(Message.assistant("A 与 B 的结果如上", null));
        history.add(Message.user("再看 C 和 D"));
        history.add(toolMessage("web_search", "C 的结果"));
        history.add(toolMessage("web_search", "D 的结果"));
        return history;
    }

    private static Message toolMessage(String name, String content) {
        Message m = new Message();
        m.setRole("tool");
        m.setName(name);
        m.setToolCallId("call_" + content.hashCode());
        m.setContent(content);
        return m;
    }

    @Test
    @DisplayName("compact-first: 保持旧行为, 每轮都清")
    void compactFirstClearsEveryTime() {
        ToolResultCompressor c = compressor(true, "compact-first", 300);
        List<Message> history = historyWithToolResults();

        int cleared = c.microcompact("s1", history);

        assertEquals(2, cleared, "4 条中保留最近 2 条, 应清前两条");
        assertEquals("[旧工具结果已清理以释放上下文，内容不再回灌。需要时重新调用该工具即可。]",
                history.get(1).getContent());
        assertEquals("C 的结果", history.get(5).getContent(), "最近一轮的结果不得被清");
    }

    @Test
    @DisplayName("cache-first: 缓存窗口内不清 —— 清一次就是让其后前缀全部作废")
    void cacheFirstSkipsWhileCacheAlive() {
        ToolResultCompressor c = compressor(true, "cache-first", 300);
        List<Message> history = historyWithToolResults();

        assertEquals(0, c.microcompact("s1", history), "首次进入工具循环时缓存正热, 不应清理");
        assertEquals(0, c.microcompact("s1", history), "紧接着的下一轮仍在窗口内, 仍不应清理");
        assertEquals("A 的结果", history.get(1).getContent(), "内容必须原样保留");
    }

    @Test
    @DisplayName("cache-first: 空闲超过 TTL 后缓存已确定过期, 此时才真正清理")
    void cacheFirstClearsAfterIdleTtl() throws Exception {
        ToolResultCompressor c = compressor(true, "cache-first", 1);
        List<Message> history = historyWithToolResults();

        assertEquals(0, c.microcompact("s1", history), "第一次调用只记录时间戳, 不清");
        Thread.sleep(1_100L);   // 越过 ttl=1s
        int cleared = c.microcompact("s1", history);

        assertEquals(2, cleared, "缓存已过期, 清理不再额外损失缓存, 应放行");
    }

    @Test
    @DisplayName("关闭开关时任何策略都不清")
    void disabledNeverClears() {
        ToolResultCompressor c = compressor(false, "compact-first", 300);
        List<Message> history = historyWithToolResults();

        assertEquals(0, c.microcompact("s1", history));
        assertTrue(history.get(1).getContent().contains("A 的结果"), "关闭时不得改写历史");
    }

    @Test
    @DisplayName("有副作用的工具不在清理白名单内(结果不可重放)")
    void sideEffectToolsAreUntouchable() {
        ToolResultCompressor c = compressor(true, "compact-first", 300);
        List<Message> history = new ArrayList<>();
        history.add(Message.user("生成一张图再查资料"));
        history.add(toolMessage("image_generation", "生成了一张图"));
        history.add(toolMessage("web_search", "结果1"));
        history.add(toolMessage("web_search", "结果2"));
        history.add(Message.assistant("好了", null));
        history.add(Message.user("再查一次"));
        history.add(toolMessage("web_search", "结果3"));

        int cleared = c.microcompact("s1", history);

        assertEquals(1, cleared, "可回收的 3 条里保留最近 2 条, 只清最旧的那条 web_search");
        assertEquals("生成了一张图", history.get(1).getContent(),
                "生图结果不可重放, 清掉就真丢了 —— 白名单必须挡住它");
        assertTrue(history.get(2).getContent().startsWith("[旧工具结果已清理"),
                "最旧的 web_search 应被清成占位文本, 实际: " + history.get(2).getContent());
    }
}
