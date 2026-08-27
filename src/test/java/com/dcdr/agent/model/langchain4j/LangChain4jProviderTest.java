package com.dcdr.agent.model.langchain4j;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.dto.ChatChunk;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.rag.embedding.LangChain4jEmbeddingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.1 框架化改造: LangChain4j Provider 集成测试。
 * <p>
 * 不依赖 Spring 上下文, 直接用与 application.yml 一致的配置实例化 Provider,
 * 真调 DashScope 验证四条关键链路:
 * <ol>
 *   <li>invoke 同步调用(普通文本)</li>
 *   <li>stream 流式调用(chunk/usage 契约, AgentOrchestrator 依赖)</li>
 *   <li>invoke + responseFormat=tool_call 结构化输出(IntentRouter 依赖, 验证 ToolChoice.REQUIRED)</li>
 *   <li>embedBatch 向量化(1536 维, RAG 依赖)</li>
 * </ol>
 * 仅在 DASHSCOPE_API_KEY 存在时执行, 未设置时跳过。
 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class LangChain4jProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static LangChain4jChatProvider chatProvider;

    @BeforeAll
    static void setUp() {
        AgentProperties.ModelConfig config = new AgentProperties.ModelConfig();
        config.setName("qwen-chat");
        config.setProvider("langchain4j");
        config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setModel("qwen3.7-max-2026-06-08");
        config.setCapabilities(List.of("function_calling"));
        chatProvider = new LangChain4jChatProvider(config);
    }

    private static ChatRequest simpleRequest(String message) {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        return request;
    }

    @Test
    void chatInvoke() {
        ChatResponse response = chatProvider.invoke(
                simpleRequest("请只回复两个字: 你好"), List.of());

        assertNotNull(response.getContent());
        assertFalse(response.getContent().isBlank());
        assertEquals("qwen3.7-max-2026-06-08", response.getModel());
        System.out.println("[invoke] content = " + response.getContent());
    }

    @Test
    void chatStream() {
        List<ChatChunk> chunks = chatProvider
                .stream(simpleRequest("用一句话介绍 Java"), List.of())
                .collectList()
                .block(Duration.ofSeconds(120));

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        // 契约: 至少有文本 chunk 与收尾 usage 块(AgentOrchestrator 采集 token 消耗)
        assertTrue(chunks.stream().anyMatch(c -> "chunk".equals(c.getType()) && c.getContent() != null),
                "缺少文本 chunk: " + chunks);
        assertTrue(chunks.stream().anyMatch(c -> "usage".equals(c.getType())),
                "缺少 usage 块: " + chunks);
        String text = chunks.stream()
                .filter(c -> "chunk".equals(c.getType()) && c.getContent() != null)
                .map(ChatChunk::getContent)
                .reduce("", String::concat);
        assertFalse(text.isBlank());
        System.out.println("[stream] text = " + text);
    }

    /** 思维链链路: returnThinking 开启后 reasoning_content 应经 chunk.reasoningContent 流出(前端"深度思考"展示依赖) */
    @Test
    void chatStreamThinking() {
        List<ChatChunk> chunks = chatProvider
                .stream(simpleRequest("9.11 和 9.9 哪个大? 请仔细思考后回答"), List.of())
                .collectList()
                .block(Duration.ofSeconds(120));

        assertNotNull(chunks);
        String thinking = chunks.stream()
                .filter(c -> "chunk".equals(c.getType()) && c.getReasoningContent() != null)
                .map(ChatChunk::getReasoningContent)
                .reduce("", String::concat);
        assertFalse(thinking.isBlank(), "未收到思维链(reasoning_content)内容, returnThinking 未生效: " + chunks);
        System.out.println("[stream-thinking] thinking(前120字) = "
                + thinking.substring(0, Math.min(120, thinking.length())));
    }

    /** 用户级思考开关: thinkingEnabled=false 时真关闭(enable_thinking=false), 不应有思维链流出且正文正常 */
    @Test
    void chatStreamThinkingDisabled() {
        ChatRequest request = simpleRequest("9.11 和 9.9 哪个大? 请仔细思考后回答");
        request.setThinkingEnabled(false);

        List<ChatChunk> chunks = chatProvider
                .stream(request, List.of())
                .collectList()
                .block(Duration.ofSeconds(120));

        assertNotNull(chunks);
        String thinking = chunks.stream()
                .filter(c -> "chunk".equals(c.getType()) && c.getReasoningContent() != null)
                .map(ChatChunk::getReasoningContent)
                .reduce("", String::concat);
        assertTrue(thinking.isBlank(), "thinkingEnabled=false 后仍有思维链流出, 关闭参数未生效: " + thinking);
        // 关闭思考不影响正文产出
        String text = chunks.stream()
                .filter(c -> "chunk".equals(c.getType()) && c.getContent() != null)
                .map(ChatChunk::getContent)
                .reduce("", String::concat);
        assertFalse(text.isBlank(), "关闭思考后正文为空");
        System.out.println("[stream-thinking-off] text(前80字) = "
                + text.substring(0, Math.min(80, text.length())));
    }

    /** 意图路由链路: tool_call 结构化输出经 ToolChoice.REQUIRED 强制, 结果从 arguments 取 */
    @Test
    void chatInvokeToolCallMode() throws Exception {
        ChatRequest request = simpleRequest("当前用户在打招呼, 请判定意图");
        request.setResponseFormat("tool_call");
        request.setJsonSchemaName("route_result");
        request.setJsonSchemaDescription("提交意图路由判断结果(对话/工作流/Agent 三选一)");
        request.setJsonSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                        "intent", Map.of("type", "string",
                                "enum", List.of("chat", "workflow", "agent"),
                                "description", "意图类型"),
                        "confidence", Map.of("type", "number", "description", "置信度 0~1"),
                        "reason", Map.of("type", "string", "description", "判断理由")),
                "required", List.of("intent", "confidence"),
                "additionalProperties", false));

        ChatResponse response = chatProvider.invoke(request, List.of());
        String content = response.getContent();
        assertNotNull(content);
        assertFalse(content.isBlank());

        JsonNode json = MAPPER.readTree(content);
        assertTrue(json.has("intent"), "结构化结果缺少 intent 字段: " + content);
        assertTrue(json.has("confidence"), "结构化结果缺少 confidence 字段: " + content);
        List<String> allowed = List.of("chat", "workflow", "agent");
        assertTrue(allowed.contains(json.get("intent").asText()),
                "intent 不在枚举内: " + content);
        System.out.println("[tool_call] structured = " + content);
    }

    /** RAG 链路: 向量化维度必须与 schema VECTOR(1536) 对齐 */
    @Test
    void embedBatch() {
        RagProperties.EmbeddingProviderConfig config = new RagProperties.EmbeddingProviderConfig();
        config.setName("qwen-embedding");
        config.setProvider("langchain4j");
        config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setModel("qwen3.7-text-embedding");
        config.setDimensions(1536);
        config.setBatchSize(10);
        LangChain4jEmbeddingProvider embeddingProvider = new LangChain4jEmbeddingProvider(config);

        List<float[]> vectors = embeddingProvider.embedBatch(List.of("你好世界", "知识库检索测试"));
        assertEquals(2, vectors.size());
        assertEquals(1536, vectors.get(0).length);
        assertEquals(1536, vectors.get(1).length);
        // 向量非全零(真实返回)
        boolean nonZero = false;
        for (float v : vectors.get(0)) {
            if (v != 0f) { nonZero = true; break; }
        }
        assertTrue(nonZero, "向量为全零, 疑似空响应");
        System.out.println("[embedding] dims = " + vectors.get(0).length);
    }
}
