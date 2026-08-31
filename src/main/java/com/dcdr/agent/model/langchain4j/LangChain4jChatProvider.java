package com.dcdr.agent.model.langchain4j;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.dto.ChatChunk;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.model.provider.ChatModelProvider;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 LangChain4j 的对话模型 Provider (V3.1 框架化改造)。
 * <p>
 * 实现 {@link ChatModelProvider} 业务接口, 内核替换为框架的
 * {@link OpenAiChatModel}(同步) + {@link OpenAiStreamingChatModel}(流式):
 * 请求构造、SSE 解析、tool_calls 增量聚合、reasoning_content(思维链)解析、
 * token usage 统计全部由框架完成, 替代手写版 {@code OpenAiCompatibleChatProvider}
 * 的 WebClient + Jackson 实现。
 * <p>
 * 对外契约(与手写版完全一致, AgentOrchestrator 等编排层零改动):
 * <ul>
 *   <li>stream 发出 chunk(正文/思维链) → usage(含 finishReason) → tool_call 块, 失败发 error 块</li>
 *   <li>invoke 在 responseFormat=tool_call 时取强制工具调用的 arguments 作为结构化结果</li>
 *   <li>base-url 约定一致: 框架在 baseUrl 后追加 /chat/completions, 与手写版
 *       getChatPath()="/chat/completions" 拼出的 URL 相同, 配置无需改动</li>
 * </ul>
 * <p>
 * 已知差异(可接受):
 * <ul>
 *   <li>下游取消订阅(用户中断)后框架的 HTTP 流在后台继续读完, 不再向上游主动断流</li>
 *   <li>框架默认对 429/5xx 自动重试(手写版不重试)</li>
 * </ul>
 */
@Slf4j
public class LangChain4jChatProvider implements ChatModelProvider {

    /** 同步/流式调用 HTTP 超时, 对齐手写版 SYNC_TIMEOUT/STREAM_TIMEOUT(120s) */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final String name;
    private final String label;
    private final String provider;
    private final String model;
    private final Double temperature;
    private final Set<String> capabilities;
    /** 本厂商"关闭思考"的请求参数(null=厂商不支持关闭, 忽略用户开关) */
    private final Map<String, Object> thinkingDisableParams;

    public LangChain4jChatProvider(AgentProperties.ModelConfig config) {
        // baseUrl 约定: 框架在 baseUrl 后追加 /chat/completions
        // (与手写版 getChatPath() 拼接结果一致, base-url 配置原样复用)
        var chatBuilder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .timeout(TIMEOUT);
        if (config.getTemperature() != null) {
            chatBuilder.temperature(config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            chatBuilder.maxTokens(config.getMaxTokens());
        }
        this.chatModel = chatBuilder.build();

        var streamBuilder = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .timeout(TIMEOUT)
                // 思维链开关: 框架默认 false, 开启后 delta.reasoning_content(DashScope/DeepSeek 风格)
                // 才会经 onPartialThinking 回调发出, 否则思考内容在框架内部被静默丢弃
                .returnThinking(true);
        if (config.getTemperature() != null) {
            streamBuilder.temperature(config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            streamBuilder.maxTokens(config.getMaxTokens());
        }
        this.streamingChatModel = streamBuilder.build();

        this.name = config.getName();
        this.label = config.getModel();
        this.provider = config.getProvider();
        this.model = config.getModel();
        this.temperature = config.getTemperature();
        this.capabilities = config.getCapabilities() != null ? Set.copyOf(config.getCapabilities()) : Set.of();
        this.thinkingDisableParams = resolveThinkingDisableParams(config.getBaseUrl());
    }

    /**
     * 按 base-url 识别厂商, 返回"关闭思考"的请求参数。
     * <p>各厂商参数不同(非 OpenAI 标准), 经框架 customParameters 透传:
     * <ul>
     *   <li>DashScope(Qwen3 混合推理): {@code enable_thinking=false}</li>
     *   <li>智谱(GLM-4.5+): {@code thinking={type:disabled}}</li>
     *   <li>其他(OpenAI/DeepSeek 等): 返回 null, 不干预(DeepSeek-V3 本就不思考,
     *       deepseek-reasoner 官方不支持关闭)</li>
     * </ul>
     */
    private static Map<String, Object> resolveThinkingDisableParams(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String url = baseUrl.toLowerCase();
        if (url.contains("dashscope.aliyuncs.com")) {
            return Map.of("enable_thinking", false);
        }
        if (url.contains("open.bigmodel.cn")) {
            return Map.of("thinking", Map.of("type", "disabled"));
        }
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ModelType getType() {
        return ModelType.CHAT;
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public String getProvider() {
        return provider;
    }

    @Override
    public boolean supportsVision() {
        return capabilities.contains("vision");
    }

    @Override
    public boolean supportsFunctionCalling() {
        return capabilities.contains("function_calling");
    }

    @Override
    public ChatResponse invoke(ChatRequest request, List<Message> history) {
        try {
            dev.langchain4j.model.chat.request.ChatRequest chatRequest =
                    buildChatRequest(request, history, true);
            dev.langchain4j.model.chat.response.ChatResponse lcResponse = chatModel.chat(chatRequest);
            AiMessage aiMessage = lcResponse.aiMessage();
            String content = aiMessage != null ? aiMessage.text() : null;
            // tool_call 结构化模式: 结果在强制的 tool_calls[0].function.arguments 中(而非 content)
            if ("tool_call".equals(request.getResponseFormat())
                    && aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                String arguments = aiMessage.toolExecutionRequests().get(0).arguments();
                if (arguments != null && !arguments.isBlank()) {
                    content = arguments;
                } else {
                    log.warn("[{}] tool_call 模式未返回 arguments, 回退取 content", name);
                }
            }
            ChatResponse response = new ChatResponse();
            response.setContent(content);
            response.setModel(model);
            // usage 映射: 同步路径此前缺失, 导致 chatSync/路由等调用方拿不到 token 统计
            TokenUsage usage = lcResponse.metadata() != null ? lcResponse.metadata().tokenUsage() : null;
            if (usage != null) {
                ChatResponse.TokenUsage u = new ChatResponse.TokenUsage();
                u.setPrompt(nz(usage.inputTokenCount()));
                u.setCompletion(nz(usage.outputTokenCount()));
                u.setTotal(nz(usage.totalTokenCount()));
                response.setTokenUsage(u);
            }
            return response;
        } catch (Exception e) {
            log.error("{} 同步调用失败", name, e);
            ChatResponse resp = new ChatResponse();
            resp.setContent("调用失败: " + e.getMessage());
            resp.setModel(model);
            return resp;
        }
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request, List<Message> history) {
        return stream(request, history, true);
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request, List<Message> history, boolean includeCurrentUser) {
        dev.langchain4j.model.chat.request.ChatRequest chatRequest =
                buildChatRequest(request, history, includeCurrentUser);
        // 框架流式为回调式 API(StreamingChatResponseHandler), 用 Flux.create 桥接为响应式流;
        // 回调发生在框架 HTTP 客户端线程上, FluxSink 保证线程安全
        return Flux.create(sink -> streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partialResponse) {
                sink.next(ChatChunk.chunk(partialResponse));
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                // 思维链(reasoning_content): 与正文同为 chunk 块, 以 reasoningContent 字段区分
                sink.next(ChatChunk.chunk(null, partialThinking.text()));
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                // usage 块: token 消耗 + 结束原因(与手写版流尾 usage 块契约一致, 先于 tool_call)
                TokenUsage usage = completeResponse.metadata().tokenUsage();
                if (usage != null) {
                    ChatChunk usageChunk = ChatChunk.usage(
                            nz(usage.inputTokenCount()), nz(usage.outputTokenCount()), nz(usage.totalTokenCount()));
                    String fr = mapFinishReason(completeResponse.metadata().finishReason());
                    if (fr != null) {
                        usageChunk.setFinishReason(fr);
                    }
                    sink.next(usageChunk);
                }
                // 工具调用块: 框架聚合好的完整 tool calls(与手写版流尾 concat 行为一致)
                AiMessage aiMessage = completeResponse.aiMessage();
                if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                        sink.next(ChatChunk.toolCall(toolRequest.id(), toolRequest.name(), toolRequest.arguments()));
                    }
                }
                sink.complete();
            }

            @Override
            public void onError(Throwable error) {
                log.error("{} 流式调用失败", name, error);
                sink.next(ChatChunk.error("流式调用失败: " + error.getMessage(), "MODEL_ERROR"));
                sink.complete();
            }
        }));
    }

    // ===== 请求转换: 业务模型 → LangChain4j ChatRequest =====

    /**
     * 构造框架请求。
     *
     * @param includeCurrentUser 是否把 request.getMessage() 作为当前 user 消息追加;
     *                           工具循环回灌轮须传 false(当前 user 消息已在 history 中)
     */
    private dev.langchain4j.model.chat.request.ChatRequest buildChatRequest(
            ChatRequest request, List<Message> history, boolean includeCurrentUser) {
        List<ChatMessage> messages = new ArrayList<>();
        if (history != null) {
            for (Message msg : history) {
                appendHistoryMessage(messages, msg);
            }
        }
        if (includeCurrentUser) {
            messages.add(toUserMessage(request.getMessage(), request.getImages()));
        }

        var builder = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(messages);
        Double temp = request.getTemperature() != null ? request.getTemperature() : temperature;

        String responseFormat = request.getResponseFormat();
        // tool_call 结构化输出: 仅注入"提交结果"工具并强制调用(tool_choice=required),
        // 模型唯一可调用的工具即 schema 工具, arguments 必须符合 parameters schema;
        // 等价于手写版的 tool_choice={type:function, function:{name}}。
        // 注意: 通义等模型默认开启思考模式, DashScope 思考模式下不支持 tool_choice=required/object,
        // 故经 customParameters 按请求传 enable_thinking=false 关闭思考以保证强约束成立
        // (仅影响路由等结构化调用, 普通对话的思维链能力不受影响)
        boolean forcedToolMode = "tool_call".equals(responseFormat)
                && request.getJsonSchema() != null && supportsFunctionCalling();

        if (forcedToolMode) {
            String fnName = request.getJsonSchemaName() != null
                    ? request.getJsonSchemaName() : "submit_result";
            ToolSpecification submitTool = ToolSpecification.builder()
                    .name(fnName)
                    .description(request.getJsonSchemaDescription() != null
                            ? request.getJsonSchemaDescription() : "提交处理结果")
                    .parameters(JsonSchemaConverter.toObjectSchema(request.getJsonSchema()))
                    .build();
            // 参数全部走 parameters 对象(ChatRequest 在 flat 字段全空时原样使用显式 parameters)
            var params = OpenAiChatRequestParameters.builder()
                    .toolSpecifications(List.of(submitTool))
                    .toolChoice(ToolChoice.REQUIRED)
                    .customParameters(Map.of("enable_thinking", false));
            if (temp != null) {
                params.temperature(temp);
            }
            builder.parameters(params.build());
            return builder.build();
        }

        // 普通调用路径: 参数走 flat 字段
        if (temp != null) {
            builder.temperature(temp);
        }
        // 用户级思考开关: false 时注入厂商关闭参数真关闭(省思考 token 与首字延迟);
        // null/true 不干预走模型默认。customParameters 无 flat 字段, 须走 parameters 对象
        // (框架将 flat 字段与 parameters 逐字段合并, 与 toolSpecifications 等共存无冲突)
        if (Boolean.FALSE.equals(request.getThinkingEnabled()) && thinkingDisableParams != null) {
            builder.parameters(OpenAiChatRequestParameters.builder()
                    .customParameters(thinkingDisableParams)
                    .build());
        }
        if (request.getToolSpecifications() != null && !request.getToolSpecifications().isEmpty()
                && supportsFunctionCalling()) {
            // V3.1 主路径: ToolRegistry 注册期缓存的框架原生工具规范直供, 零 Map 往返转换
            builder.toolSpecifications(request.getToolSpecifications());
        } else if (request.getTools() != null && !request.getTools().isEmpty() && supportsFunctionCalling()) {
            // 兼容路径: OpenAI 格式 JSON 定义 → ToolSpecification
            List<ToolSpecification> specs = new ArrayList<>();
            for (Map<String, Object> tool : request.getTools()) {
                ToolSpecification spec = toToolSpecification(tool);
                if (spec != null) {
                    specs.add(spec);
                }
            }
            if (!specs.isEmpty()) {
                builder.toolSpecifications(specs);
            }
        }

        // 结构化输出约束: json_object 强制合法 JSON; json_schema 按 Schema 输出
        if ("json_object".equals(responseFormat)) {
            builder.responseFormat(ResponseFormat.JSON);
        } else if ("json_schema".equals(responseFormat) && request.getJsonSchema() != null) {
            builder.responseFormat(ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder()
                            .name(request.getJsonSchemaName() != null
                                    ? request.getJsonSchemaName() : "response")
                            .rootElement(JsonSchemaConverter.toSchemaElement(request.getJsonSchema()))
                            .build())
                    .build());
        }
        return builder.build();
    }

    /** 历史 Message(role=system/user/assistant/tool) → 框架消息 */
    private void appendHistoryMessage(List<ChatMessage> messages, Message msg) {
        String role = msg.getRole();
        if ("system".equals(role)) {
            if (msg.getContent() != null && !msg.getContent().isBlank()) {
                messages.add(SystemMessage.from(msg.getContent()));
            }
            return;
        }
        if ("tool".equals(role)) {
            // 工具结果消息: 携带 tool_call_id 与工具名, 与 assistant 的 tool_calls 配对
            messages.add(ToolExecutionResultMessage.from(
                    msg.getToolCallId(), msg.getName(), msg.getContent() == null ? "" : msg.getContent()));
            return;
        }
        if ("assistant".equals(role)) {
            // 助手消息携带 tool_calls: 函数调用往返(此时 content 可能为空)
            List<Map<String, Object>> toolCalls = msg.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                List<ToolExecutionRequest> requests = new ArrayList<>(toolCalls.size());
                for (Map<String, Object> tc : toolCalls) {
                    requests.add(ToolExecutionRequest.builder()
                            .id(String.valueOf(tc.get("id")))
                            .name(String.valueOf(tc.get("name")))
                            .arguments(tc.get("arguments") == null ? "" : String.valueOf(tc.get("arguments")))
                            .build());
                }
                messages.add(AiMessage.from(msg.getContent(), requests));
                return;
            }
            if (msg.getContent() == null || msg.getContent().isBlank()) {
                return;
            }
            messages.add(AiMessage.from(msg.getContent()));
            return;
        }
        // user 消息(跳过空内容, 与手写版一致)
        if (msg.getContent() == null || msg.getContent().isBlank()) {
            return;
        }
        messages.add(toUserMessage(msg.getContent(), msg.getImages()));
    }

    /** 用户消息: 支持视觉模型时图片转为 image content, 与手写版 image_url 结构一致 */
    private UserMessage toUserMessage(String text, List<String> images) {
        if (images == null || images.isEmpty() || !supportsVision()) {
            if (images != null && !images.isEmpty()) {
                log.warn("[{}] 当前模型不支持视觉能力, 已忽略 {} 张上传图片", name, images.size());
            }
            return UserMessage.from(text);
        }
        List<Content> contents = new ArrayList<>(images.size() + 1);
        contents.add(TextContent.from(text));
        for (String img : images) {
            contents.add(ImageContent.from(img));
        }
        return UserMessage.from(contents.toArray(new Content[0]));
    }

    /** OpenAI 格式工具定义({type:function, function:{name,description,parameters}}) → ToolSpecification */
    private ToolSpecification toToolSpecification(Map<String, Object> openAiTool) {
        Object fnObj = openAiTool.get("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = fnObj instanceof Map ? (Map<String, Object>) fnObj : openAiTool;
        if (fn.get("name") == null) {
            log.warn("[{}] 跳过缺少 name 的工具定义: {}", name, openAiTool);
            return null;
        }
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(String.valueOf(fn.get("name")));
        if (fn.get("description") != null) {
            builder.description(String.valueOf(fn.get("description")));
        }
        if (fn.get("parameters") instanceof Map<?, ?> params) {
            @SuppressWarnings("unchecked")
            Map<String, Object> paramMap = (Map<String, Object>) params;
            builder.parameters(JsonSchemaConverter.toObjectSchema(paramMap));
        }
        return builder.build();
    }

    // ===== 工具方法 =====

    /** 框架 FinishReason 枚举 → OpenAI 风格字符串(编排层按 "tool_calls"/"length"/"content_filter" 判断) */
    private static String mapFinishReason(FinishReason finishReason) {
        if (finishReason == null) {
            return null;
        }
        return switch (finishReason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case TOOL_EXECUTION -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            default -> "other";
        };
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }
}
