package com.dcdr.agent.tool.result;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.memory.TokenEstimator;
import com.dcdr.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧工具结果微压缩测试。
 *
 * <p><b>要锁住的核心收益</b>: 微压缩不是"让历史变短"这么简单 —— 它是让
 * {@code allowanceForRound} 重新给出宽松额度。因为窗口预算只在"历史工具结果总量超预算"时
 * 收紧本轮, 而<b>旧的从不释放</b>。于是多轮工具循环一旦触顶, 之后每轮的新结果都被压到
 * 800 字符, 模型越往后看到的信息越少, 而日志上一切正常。
 *
 * <p>所以第 4 条用例(清理后额度放开)才是这个功能存在的理由, 前三条只是防止误伤。
 */
class MicrocompactTest {

    private AgentProperties properties;
    private ToolResultCompressor compressor;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        AgentProperties.ResultConfig cfg = properties.getTools().getResult();
        cfg.setWindowTokenBudget(1_000);
        cfg.setWindowFloorLength(800);
        cfg.getMicrocompact().setEnabled(true);
        cfg.getMicrocompact().setKeepRecent(2);
        compressor = new ToolResultCompressor(properties, new TokenEstimator(properties), null, null, null);
    }

    @Test
    @DisplayName("超过保留条数的只读工具结果被清理, 保留最近的几条")
    void clearsOldReadonlyResults() {
        List<Message> history = new ArrayList<>();
        // 上一轮: 旧结果 3 条
        history.add(roundStart());
        for (int i = 1; i <= 3; i++) {
            history.add(toolMessage("web_search", "旧结果" + i));
        }
        // 本轮: 2 条, 必须原样保留
        history.add(roundStart());
        history.add(toolMessage("web_search", "本轮结果1"));
        history.add(toolMessage("web_search", "本轮结果2"));

        int cleared = compressor.microcompact(history);

        assertEquals(3, cleared, "总 5 条、保留最近一轮 2 条 → 清掉更早的 3 条");
        assertEquals("[旧工具结果已清理以释放上下文，内容不再回灌。需要时重新调用该工具即可。]",
                history.get(1).getContent(), "最旧的应被替换成占位文本");
        assertEquals("本轮结果1", history.get(5).getContent(), "最近一轮必须保持原样");
        assertEquals("本轮结果2", history.get(6).getContent());
    }

    @Test
    @DisplayName("带副作用的工具结果永不清: 生图/写文件重放一次就是另一份结果")
    void neverClearsIrreversibleTools() {
        List<Message> history = new ArrayList<>();
        history.add(roundStart());
        history.add(toolMessage("image_generation", "已生成图片：/img/a.png"));
        history.add(roundStart());
        for (int i = 1; i <= 4; i++) {
            history.add(toolMessage("web_search", "结果" + i));
        }
        history.add(roundStart());
        history.add(toolMessage("web_search", "本轮"));

        compressor.microcompact(history);

        assertEquals("已生成图片：/img/a.png", history.get(1).getContent(),
                "生图结果不可复现, 清掉模型就再也拿不到那个地址");
    }

    @Test
    @DisplayName("条数不足保留量时什么都不动")
    void doesNothingBelowKeepRecent() {
        List<Message> history = new ArrayList<>();
        history.add(toolMessage("web_search", "a"));
        history.add(toolMessage("web_extract", "b"));

        assertEquals(0, compressor.microcompact(history));
        assertEquals("a", history.get(0).getContent());
    }

    @Test
    @DisplayName("清理后窗口额度真的放开 —— 这才是它的意义")
    void freesWindowBudgetForLaterRounds() {
        List<Message> history = new ArrayList<>();
        // 前两轮共 6 条各 2000 字符的英文内容, 足以把 1000 token 的窗口预算撑爆;
        // 最近一轮只有 2 条, 微压缩不会动它们
        for (int round = 1; round <= 3; round++) {
            history.add(roundStart());
            for (int i = 0; i < 3; i++) {
                if (round == 3 && i == 2) break;
                history.add(toolMessage("web_search", "content ".repeat(250)));
            }
        }
        int before = compressor.allowanceForRound(history);
        assertTrue(before > 0, "前置条件: 历史已超窗口预算, 本轮额度应被收紧到下限");

        compressor.microcompact(history);
        int after = compressor.allowanceForRound(history);

        assertEquals(0, after, "清掉旧结果后预算应重新可用, 本轮不该再被压到 800 字符");
        assertTrue(before > after, "清理前后额度必须变宽松: " + before + " -> " + after);
    }

    @Test
    @DisplayName("关闭开关时完全不介入")
    void respectsEnabledSwitch() {
        properties.getTools().getResult().getMicrocompact().setEnabled(false);
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            history.add(toolMessage("web_search", "结果" + i));
        }

        assertEquals(0, compressor.microcompact(history));
        assertEquals("结果0", history.get(0).getContent());
    }

    /** 真实 history 里每一轮工具结果前都有一条 assistant(tool_calls), 微压缩靠它判断"最近一轮"的边界 */
    private static Message roundStart() {
        Message m = new Message();
        m.setRole("assistant");
        m.setToolCalls(List.of(java.util.Map.of("id", java.util.UUID.randomUUID().toString(),
                "name", "web_search", "arguments", "{}")));
        return m;
    }

    private static Message toolMessage(String toolName, String content) {
        Message m = new Message();
        m.setRole("tool");
        m.setName(toolName);
        m.setContent(content);
        return m;
    }
}
