package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.memory.TokenEstimator;
import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.dto.ContextUsage;
import com.dcdr.agent.skill.SkillCatalogProvider;
import com.dcdr.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文分段统计的规则测试(V3.4)。
 *
 * <p>这里锁的是<b>归类</b>与<b>分母</b>两件事 —— 它们错了不会报错, 只会让界面安静地撒谎:
 * 技能被算进"系统提示词"(用户以为关技能没用)、改了总上下文后百分比不动(用户以为没保存)、
 * 五段之和与总数对不上(用户拿它当账单就会对不上账)。
 */
class ContextUsageServiceTest {

    private static final int LIMIT = 100_000;

    private static ContextUsageService service() {
        AgentProperties props = new AgentProperties();
        props.getMemory().setContextTokenLimit(LIMIT);
        TokenEstimator estimator = new TokenEstimator(props);
        // 空注册表: 工具定义一律归"工具及子智能体"侧(getTool 返回 null 不是连接器)
        return new ContextUsageService(estimator, new ToolRegistry(new ToolsProperties()));
    }

    private static Map<String, Object> toolDef(String name) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", "示例工具 " + name);
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "function");
        def.put("function", fn);
        return def;
    }

    private static ContextUsage.Segment seg(ContextUsage usage, String key) {
        return usage.getSegments().stream()
                .filter(s -> key.equals(s.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少分段: " + key));
    }

    @Test
    @DisplayName("五个分段固定输出且顺序固定 —— 界面按位置取色, 少一段就会整体错位")
    void alwaysFiveSegmentsInFixedOrder() {
        ContextUsage usage = service().compute(List.of(Message.user("你好")), List.of(), LIMIT);

        assertEquals(5, usage.getSegments().size());
        assertEquals(ContextUsageService.KEY_SYSTEM, usage.getSegments().get(0).getKey());
        assertEquals(ContextUsageService.KEY_TOOLS, usage.getSegments().get(1).getKey());
        assertEquals(ContextUsageService.KEY_MESSAGES, usage.getSegments().get(2).getKey());
        assertEquals(ContextUsageService.KEY_CONNECTORS, usage.getSegments().get(3).getKey());
        assertEquals(ContextUsageService.KEY_SKILLS, usage.getSegments().get(4).getKey());
        assertTrue(usage.isAvailable(), "有历史就该是 available, 否则界面显示空态");
    }

    @Test
    @DisplayName("技能目录归到「技能」而不是「系统提示词」—— 否则用户关掉技能会看不到任何变化")
    void skillCatalogDoesNotCountAsSystemPrompt() {
        List<Message> history = List.of(
                Message.system("平台提示词"),
                Message.system(SkillCatalogProvider.PROMPT_NAME, "技能目录内容"),
                Message.system(SkillCatalogProvider.MANUAL_PROMPT_NAME, "指定技能正文"),
                Message.user("问题"));

        ContextUsage usage = service().compute(history, List.of(), LIMIT);

        ContextUsage.Segment skills = seg(usage, ContextUsageService.KEY_SKILLS);
        assertEquals(2, skills.getItems().size(), "目录与正文是两笔账, 不能合成一条");
        assertTrue(skills.getTokens() > 0);

        ContextUsage.Segment system = seg(usage, ContextUsageService.KEY_SYSTEM);
        assertEquals(1, system.getItems().size(), "技能片段不该同时出现在系统提示词里");
        assertEquals("平台系统提示词", system.getItems().get(0).getLabel());
    }

    @Test
    @DisplayName("同名注入片段合并计数, 且段内按 token 降序 —— 明细里先看到最贵的那项")
    void mergesSameNameAndSortsByTokens() {
        List<Message> history = List.of(
                Message.system("memory:long-term", "很长的记忆内容 ".repeat(50)),
                Message.system("runtime:current-time", "2026年11月06日 星期五 10:00:00"),
                Message.system("memory:long-term", "再一段记忆"));

        ContextUsage usage = service().compute(history, List.of(), LIMIT);
        ContextUsage.Segment system = seg(usage, ContextUsageService.KEY_SYSTEM);

        assertEquals(2, system.getItems().size(), "同名片段应合并成一条");
        assertEquals("长期记忆", system.getItems().get(0).getLabel(), "降序: 记忆比时间约束长");
        assertEquals(2, system.getItems().get(0).getCount(), "合并项数要如实交代是几条拼起来的");
    }

    @Test
    @DisplayName("工具定义归「工具及子智能体」, 未接入 MCP 时连接器段为 0 但不缺席")
    void toolDefinitionsGoToToolsSegment() {
        List<Map<String, Object>> defs = List.of(toolDef("calculator"), toolDef("web_search"));
        ContextUsage usage = service().compute(List.of(Message.user("hi")), defs, LIMIT);

        ContextUsage.Segment tools = seg(usage, ContextUsageService.KEY_TOOLS);
        assertEquals(2, tools.getItems().size());
        assertTrue(tools.getTokens() > 0, "工具定义是每轮重发的固定成本, 必须计入");
        assertEquals(0, seg(usage, ContextUsageService.KEY_CONNECTORS).getTokens());
    }

    @Test
    @DisplayName("总数等于五段之和, 分段占比与总占比自洽")
    void totalsAreConsistent() {
        List<Message> history = List.of(
                Message.system("平台提示词"),
                Message.system(SkillCatalogProvider.PROMPT_NAME, "技能目录".repeat(20)),
                Message.user("一条提问"),
                Message.assistant("一条回答", null));

        ContextUsage usage = service().compute(history, List.of(toolDef("calculator")), LIMIT);

        int sum = usage.getSegments().stream().mapToInt(ContextUsage.Segment::getTokens).sum();
        assertEquals(sum, usage.getTotalTokens(), "几段之和必须等于总数, 否则明细与圆环对不上账");

        double segPercentSum = usage.getSegments().stream()
                .mapToDouble(ContextUsage.Segment::getPercent).sum();
        // 各自 round 到 0.1 后再相加, 允许 0.5 的累计误差
        assertTrue(Math.abs(segPercentSum - 100) <= 0.5,
                "分段占比应合计 100%, 实际 " + segPercentSum);
    }

    @Test
    @DisplayName("百分比 = 已用 / 生效上限, 且读到快照时按新分母重算")
    void percentFollowsCurrentLimit() {
        ContextUsageService svc = service();
        // 分母刻意取小(100)而不是 1 万: 这里的分子只有几个 token(单条消息 + 协议包装),
        // 在 1 万的分母下百分比小到一位小数, 0.1 与 0.0 会因为舍入互相跳变 ——
        // 用例要锁的是"按新分母重算", 不是舍入行为。
        ContextUsage usage = svc.compute(List.of(Message.user("你好")), List.of(), 100);
        assertEquals(usage.getTotalTokens() * 100.0 / 100, usage.getPercent(), 0.05);

        String json = svc.toJson(usage);
        assertNotNull(json);
        // 用户把总上下文翻倍: 分子不变, 分母翻倍 → 百分比减半
        ContextUsage reloaded = svc.fromJson(json, 200);
        assertEquals(200, reloaded.getLimitTokens());
        assertEquals(Math.round(usage.getPercent() / 2 * 10) / 10.0, reloaded.getPercent(), 0.06,
                "改了总上下文后必须按新分母重算, 否则界面会一直显示旧比例");
    }

    @Test
    @DisplayName("思维链不计入占用 —— 本项目不回传历史 reasoning, 算进去会让思考型模型的占用凭空翻倍")
    void reasoningContentIsNotCountedAsContext() {
        String longReasoning = "先分析用户意图再决定怎么答。".repeat(60);
        int withReasoning = service().compute(
                List.of(Message.user("一个问题"),
                        Message.assistant("一个回答", longReasoning, "qwen", 100)),
                List.of(), LIMIT).getTotalTokens();
        int withoutReasoning = service().compute(
                List.of(Message.user("一个问题"),
                        Message.assistant("一个回答", null, "qwen", 100)),
                List.of(), LIMIT).getTotalTokens();

        assertEquals(withoutReasoning, withReasoning,
                "占用口径是「下一轮会被重发的内容」, 历史思维链不在其中");
    }

    @Test
    @DisplayName("没有可信实测时校准原样返回 —— 不能把估算值凭空改小")
    void calibrateWithoutMeasuredUsageKeepsEstimate() {
        ContextUsageService svc = service();
        ContextUsage usage = svc.compute(List.of(Message.user("你好")), List.of(), LIMIT);
        int before = usage.getTotalTokens();

        svc.calibrateTo(usage, 0);

        assertEquals(before, usage.getTotalTokens(), "targetTokens<=0 表示没有实测, 应保持估算值");
    }

    @Test
    @DisplayName("按实测总用量校准: 分子直接等于模型返回的 total, 不再叠加估算")
    void calibrateToMeasuredTotal() {
        ContextUsageService svc = service();
        ContextUsage usage = svc.compute(
                List.of(Message.user("你好"), Message.assistant("一条回答", null)),
                List.of(toolDef("calculator")), LIMIT);
        int target = usage.getTotalTokens() + 777;

        svc.calibrateTo(usage, target);

        assertEquals(target, usage.getTotalTokens(), "有实测 usage 时分子就是它");
        assertEquals(target, usage.getSegments().stream().mapToInt(ContextUsage.Segment::getTokens).sum(),
                "五段之和仍须等于总数");
    }

    @Test
    @DisplayName("无快照/快照损坏都降级为空态, 并把当前上限带给界面")
    void degradesToEmptyState() {
        ContextUsageService svc = service();

        ContextUsage none = svc.fromJson(null, LIMIT);
        assertFalse(none.isAvailable());
        assertEquals(LIMIT, none.getLimitTokens(), "空态也要能显示「上限多少」");
        assertTrue(none.getSegments().isEmpty());

        ContextUsage broken = svc.fromJson("{不是合法 JSON", LIMIT);
        assertFalse(broken.isAvailable(), "老版本快照解析不了时应降级, 不能把异常抛给前端");
    }
}
