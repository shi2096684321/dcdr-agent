package com.dcdr.agent.tool.result;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.memory.TokenEstimator;
import com.dcdr.agent.config.AgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.1 F-47 工具结果体积治理测试(纯单元测试, 不启 Spring 上下文)。
 * <p>
 * 覆盖四类风险点:
 * <ol>
 *   <li><b>压缩口径</b>: 未超限原样、超限头尾保留、尾比 0 退回纯头截断、token 预算真正生效</li>
 *   <li><b>落盘</b>: 超阈值落盘且落盘内容是完整原文、回灌只给"路径 + 预览"</li>
 *   <li><b>窗口预算</b>: 未触顶不收紧、触顶收紧到下限</li>
 *   <li><b>安全</b>: 路径穿越被拒、跨会话读取被拒、分页读取可续读</li>
 * </ol>
 * 摘要路径(F-47.4)不在本测试内: 它需要真实模型调用, 默认关闭,
 * 构造时显式传 null 的 ModelRegistry / CostTracker 即表达"本测试不触达该分支"。
 */
class ToolResultGovernanceTest {

    private static final String SESSION = "sess_test";

    /** 超长样本: 头部标识串 + 中段填充 + 尾部哨兵(总长 10000+ 字符) */
    private static final String HEAD_RUN = "H".repeat(5000) + "SENTINEL_HEAD_MARK ";
    private static final String MIDDLE_RUN = "M".repeat(4900);
    private static final String TAIL_SENTINEL = "SENTINEL_END";

    private AgentProperties properties;
    private ToolResultSpillStore spillStore;
    private ToolResultCompressor compressor;
    private Path spillRoot;

    @BeforeEach
    void setUp() throws Exception {
        spillRoot = Files.createTempDirectory("f47-spill");
        properties = new AgentProperties();
        AgentProperties.ResultConfig cfg = properties.getTools().getResult();
        cfg.setMaxLength(6000);
        cfg.setMaxTokenBudget(0);
        cfg.setKeepTailRatio(0.3);
        cfg.getSpill().setDir(spillRoot.toString());
        cfg.getSpill().setThreshold(32_768);
        cfg.getSpill().setRetainDays(0); // 测试不做过期清理, 避免干扰文件数断言

        spillStore = new ToolResultSpillStore(properties);
        compressor = new ToolResultCompressor(properties, new TokenEstimator(properties), spillStore, null, null);
        ToolResultContextHolder.set("u_test", SESSION);
    }

    @AfterEach
    void tearDown() {
        ToolResultContextHolder.clear();
    }

    // ===== 1. 压缩口径 =====

    @Test
    void shortResultPassesThrough() {
        ToolResultCompressor.Compressed c = compressor.compress("calculator", "1+1=2");
        assertEquals("1+1=2", c.text());
        assertFalse(c.changed(), "未超限不应发生任何改动");
    }

    @Test
    void overLongKeepsBothHeadAndTail() {
        // 尾部哨兵: 用"原文末尾的独特串"判断尾部是否被保留 ——
        // 若只用重复字符, 头部区间本身就会包含它, 断言会失效(实测踩过)
        String raw = HEAD_RUN + MIDDLE_RUN + TAIL_SENTINEL;
        ToolResultCompressor.Compressed c = compressor.compress("web_search", raw);

        assertTrue(c.truncated());
        assertTrue(c.text().startsWith("HHHH"), "头部应保留");
        assertTrue(c.text().endsWith(TAIL_SENTINEL),
                "尾部应保留 —— V3.0 只保头部, 会丢掉结论/总计行/表格末尾");
        assertTrue(c.text().contains("工具结果过长已截断"));
        assertTrue(c.keptChars() <= 6200, "保留内容不应超出上限(截断标记另计), 实际=" + c.keptChars());
        assertEquals(raw.length(), c.originalChars());
    }

    @Test
    void zeroTailRatioFallsBackToHeadOnly() {
        properties.getTools().getResult().setKeepTailRatio(0);
        String raw = HEAD_RUN + MIDDLE_RUN + TAIL_SENTINEL;
        ToolResultCompressor.Compressed c = compressor.compress("web_search", raw);

        assertFalse(c.text().contains(TAIL_SENTINEL), "尾比为 0 时应退回纯头截断(兼容 V3.0 行为)");
        assertTrue(c.text().contains("工具结果过长已截断"));
    }

    @Test
    void tokenBudgetActuallyTakesEffect() {
        properties.getTools().getResult().setMaxTokenBudget(300);
        TokenEstimator estimator = new TokenEstimator(properties);
        String raw = repeat('中', 5000); // 中文约 1 字 1 token, 远超 300

        ToolResultCompressor.Compressed c = compressor.compress("web_search", raw);
        int actual = estimator.estimate(c.text());
        assertTrue(c.changed());
        assertTrue(actual <= 300, "token 预算必须真正收敛(而不是迭代几轮就放弃), 实际=" + actual);
    }

    // ===== 2. 落盘 =====

    @Test
    void overSpillThresholdSpillsWholeContentAndFeedsBackHint() throws Exception {
        String raw = IntStream.rangeClosed(1, 2000)
                .mapToObj(i -> "row-" + i + " " + repeat('X', 15))
                .collect(Collectors.joining("\n"));
        assertTrue(raw.length() > 32_768, "样本必须超过落盘阈值, 实际=" + raw.length());

        ToolResultCompressor.Compressed c = compressor.compress("web_search", raw);

        assertTrue(c.spilled());
        assertTrue(c.text().contains("tool_result_read"), "应告知模型如何取回全文");
        assertTrue(c.text().contains(SESSION + "/web_search-"), "提示里应带相对路径");
        assertTrue(c.text().contains("row-1 "), "应带前若干行预览");
        assertTrue(c.keptChars() < raw.length() / 10, "回灌体积应为原文的零头, 实际=" + c.keptChars());

        List<Path> files = listFiles();
        assertEquals(1, files.size(), "应落盘一个文件");
        assertEquals(raw, Files.readString(files.get(0), StandardCharsets.UTF_8),
                "落盘内容必须是完整原文, 不能是压缩后的");
    }

    // ===== 3. 窗口预算 =====

    @Test
    void windowBudgetNotExhaustedKeepsDefaultLimit() {
        properties.getTools().getResult().setWindowTokenBudget(10_000);
        assertEquals(0, compressor.allowanceForRound(historyWithToolResult("短内容")));
    }

    @Test
    void windowBudgetExhaustedTightensToFloor() {
        AgentProperties.ResultConfig cfg = properties.getTools().getResult();
        cfg.setWindowTokenBudget(100);
        cfg.setWindowFloorLength(800);
        assertEquals(800, compressor.allowanceForRound(historyWithToolResult(repeat('中', 500))));
    }

    // ===== 4. 落盘读写安全 =====

    @Test
    void pathTraversalIsRejected() {
        assertNotNull(spillStore.read("../../etc/passwd", 1, 10).error(), "路径穿越必须被拒");
        assertNotNull(spillStore.read(SESSION + "/../../secret.txt", 1, 10).error(), "归一化后越界也必须被拒");
        assertNotNull(spillStore.read("", 1, 10).error(), "空路径必须被拒");
    }

    @Test
    void otherSessionFileCannotBeRead() {
        String path = spillStore.spill("web_search", "会话 A 的私有内容");
        ToolResultContextHolder.set("u_other", "sess_other");

        ToolResultSpillStore.ReadResult r = spillStore.read(path, 1, 10);
        assertNotNull(r.error(), "跨会话读取必须被拒 —— 否则一个读文件工具就泄露了他人工具结果");
    }

    @Test
    void pagedReadReturnsRangeHeaderAndContinuation() {
        String content = IntStream.rangeClosed(1, 300)
                .mapToObj(i -> "line" + i)
                .collect(Collectors.joining("\n"));
        String path = spillStore.spill("web_search", content);

        ToolResultSpillStore.ReadResult first = spillStore.read(path, 1, 100);
        assertNull(first.error());
        assertEquals(300, first.totalLines());
        assertTrue(first.text().contains("第 1-100 行 / 共 300 行"));
        assertTrue(first.text().contains("仍有 200 行"));
        assertTrue(first.text().contains("line1"));

        ToolResultSpillStore.ReadResult next = spillStore.read(path, 201, 100);
        assertTrue(next.text().contains("line201"), "应能从 offset 续读");
    }

    @Test
    void readToolRejectsMissingPathAndServesValidPath() {
        ToolResultReadTool tool = new ToolResultReadTool(spillStore, properties);

        assertEquals("tool_result_read", tool.getName());
        assertTrue(tool.supportsStreaming(), "落盘发生在流式工具循环里, 该工具必须对流式可见");
        assertTrue(tool.execute(Map.of()).contains("请提供 path"));

        String path = spillStore.spill("calculator", "line1\nline2");
        assertTrue(tool.execute(Map.of("path", path)).contains("line2"), "应返回文件内容");
    }

    // ===== 辅助 =====

    private List<Path> listFiles() throws Exception {
        Path dir = spillRoot.resolve(SESSION);
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.collect(Collectors.toList());
        }
    }

    private static List<Message> historyWithToolResult(String content) {
        Message m = new Message();
        m.setRole("tool");
        m.setContent(content);
        List<Message> history = new ArrayList<>();
        history.add(m);
        return history;
    }

    private static String repeat(char c, int times) {
        return String.valueOf(c).repeat(times);
    }
}
