package com.dcdr.agent.agent.definition;

import com.dcdr.agent.agent.governance.CostSource;
import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.AgentDtos;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 专家 AI 生成的测试 (V3.2)。
 *
 * <p>这条链路的失败方式大多<b>不会报错</b>, 只会安静地产出一份奇怪的配置:
 * 模型编了个不存在的工具名、温度给了 5、标签重复三条、角色设定其实是空的。
 * 所以归一化规则(白名单求交、夹取、去重、截断)必须逐条钉住 ——
 * 用户点"生成"之后看到的应当是"真正会生效的配置", 而不是模型的原始输出。
 */
class AgentDraftGeneratorTest {

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
    private final CostTracker costTracker = mock(CostTracker.class);
    private final ChatModelProvider provider = mock(ChatModelProvider.class);
    /**
     * 用真实实例: 受限工具清单是安全边界, mock 掉就测不出"普通用户拿不到沙箱"。
     * 构造函数传 null 是安全的 —— {@link AgentDefinitionService#filterAdminOnlyTools} 不碰任何依赖。
     */
    private final AgentDefinitionService definitionService =
            new AgentDefinitionService(null, null, null, null, null);

    private AgentDraftGenerator generator() {
        when(modelRegistry.getDefaultChatProvider()).thenReturn(provider);
        when(provider.getName()).thenReturn("test-model");
        when(toolRegistry.getToolSpecifications()).thenReturn(List.of(
                spec("calculator", "做算术"),
                spec("web_search", "联网搜索"),
                spec("code_interpreter", "在沙箱里执行 Python")));
        return new AgentDraftGenerator(definitionService, toolRegistry, modelRegistry, costTracker);
    }

    private static ToolSpecification spec(String name, String desc) {
        return ToolSpecification.builder().name(name).description(desc).build();
    }

    private void stubModel(String content) {
        ChatResponse resp = new ChatResponse();
        resp.setContent(content);
        when(provider.invoke(any(), any())).thenReturn(resp);
    }

    private static String draftJson(String extra) {
        return """
                {"name":"合同审查员","profession":"资深劳动法律师",
                 "description":"审查劳动合同, 关注试用期与违约责任",
                 "categoryId":"法律财务","tags":["合同审查","劳动法","合同审查"],
                 "quickPrompts":["帮我看看这份合同有什么坑"],
                 "persona":"你是一名劳动法律师, 先看主体条款, 再看违约责任…",
                 "tools":["calculator","code_interpreter","file_reader"],
                 "temperature":0.2%s}
                """.formatted(extra == null ? "" : extra);
    }

    @Test
    @DisplayName("生成: 回填全部字段, 并按注册表与权限双重过滤工具")
    void generateFillsDraftAndFiltersTools() {
        stubModel(draftJson(null));

        AgentDtos.AgentDraft d = generator().generate("帮我做一个审查劳动合同的专家", null, "u1", false);

        assertEquals("合同审查员", d.getName());
        assertEquals("资深劳动法律师", d.getProfession());
        // 单元素断言是重点: code_interpreter 是受限工具(普通用户不可用), file_reader 是模型编造的
        assertEquals(List.of("calculator"), d.getTools());
        // 重复标签只留一条
        assertEquals(List.of("合同审查", "劳动法"), d.getTags());
        assertEquals(List.of("帮我看看这份合同有什么坑"), d.getQuickPrompts());
        assertEquals(0.2, d.getTemperature().doubleValue(), 1e-9);
        assertEquals("test-model", d.getGeneratedBy());
        verify(costTracker).record(eq("u1"), isNull(), isNull(),
                eq(CostSource.AGENT_GENERATE), eq("test-model"), isNull());
    }

    @Test
    @DisplayName("生成: 管理员可以拿到受限工具")
    void adminKeepsSandboxTool() {
        stubModel(draftJson(null));

        AgentDtos.AgentDraft d = generator().generate("做一个能跑代码的专家", null, "admin", true);

        assertTrue(d.getTools().contains("code_interpreter"));
        assertFalse(d.getTools().contains("file_reader"));   // 编造的名字对谁都过滤
    }

    @Test
    @DisplayName("生成: 发给模型的 prompt 里不能出现普通用户用不了的受限工具")
    void promptHidesSandboxToolFromNonAdmin() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(provider.invoke(captor.capture(), any())).thenReturn(modelReply(draftJson(null)));

        generator().generate("做一个审合同的专家", null, "u1", false);

        String prompt = captor.getValue().getMessage();
        assertTrue(prompt.contains("calculator"));
        assertFalse(prompt.contains("code_interpreter"),
                "受限工具不该出现在普通用户看到的候选里, 否则生成结果必然被建议一个用不了的工具");
    }

    @Test
    @DisplayName("解析: 温度超界夹到 [0,2], 缺失回落 0.7")
    void temperatureIsClamped() {
        AgentDraftGenerator g = generator();
        assertEquals(2.0, g.parseDraft(draftJson("").replace("0.2}", "5}"),
                List.of("calculator")).getTemperature().doubleValue(), 1e-9);
        assertEquals(0.0, g.parseDraft(draftJson("").replace("0.2}", "-3}"),
                List.of("calculator")).getTemperature().doubleValue(), 1e-9);
        assertEquals(0.7, g.parseDraft(draftJson("").replace("\"temperature\":0.2", "\"temperature\":null"),
                List.of("calculator")).getTemperature().doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("解析: 兼容 ```json 包裹与前后多余文字")
    void tolerantToCodeFence() {
        String raw = "好的, 配置如下:\n```json\n" + draftJson(null) + "\n```\n希望有帮助。";

        AgentDtos.AgentDraft d = generator().parseDraft(raw, List.of("calculator"));

        assertEquals("合同审查员", d.getName());
    }

    @Test
    @DisplayName("解析: 推荐问法为空时给一条开场引导, 避免专家详情页空白")
    void quickPromptsFallback() {
        String raw = draftJson("").replace("\"quickPrompts\":[\"帮我看看这份合同有什么坑\"]",
                "\"quickPrompts\":[]");

        AgentDtos.AgentDraft d = generator().parseDraft(raw, List.of("calculator"));

        assertEquals(1, d.getQuickPrompts().size());
        assertFalse(d.getQuickPrompts().get(0).isBlank());
    }

    @Test
    @DisplayName("解析: 缺角色设定 → 502, 不交出空壳专家")
    void missingPersonaFails() {
        String raw = draftJson("").replace(
                "\"persona\":\"你是一名劳动法律师, 先看主体条款, 再看违约责任…\",", "");

        BusinessException e = assertThrows(BusinessException.class,
                () -> generator().parseDraft(raw, List.of("calculator")));
        assertEquals(502, e.getCode());
    }

    @Test
    @DisplayName("解析: 输出里没有 JSON → 502(而不是 NullPointerException)")
    void junkOutputFails() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> generator().parseDraft("抱歉, 我无法完成这个请求", List.of()));
        assertEquals(502, e.getCode());
    }

    @Test
    @DisplayName("生成: 上游模型异常统一转成 502, 不把原始报错透给用户")
    void modelFailureWrapped() {
        when(provider.invoke(any(), any())).thenThrow(new IllegalStateException("upstream timeout"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> generator().generate("做一个审合同的专家", null, "u1", false));
        assertEquals(502, e.getCode());
    }

    @Test
    @DisplayName("生成: 需求为空 → 400, 且不白花一次模型调用")
    void emptyBriefRejected() {
        AgentDraftGenerator g = generator();

        BusinessException e = assertThrows(BusinessException.class,
                () -> g.generate("   ", null, "u1", false));
        assertEquals(400, e.getCode());
    }

    private static ChatResponse modelReply(String content) {
        ChatResponse resp = new ChatResponse();
        resp.setContent(content);
        return resp;
    }
}
