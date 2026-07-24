package com.dcdr.agent.model.openai;

import com.dcdr.agent.dto.ChatChunk;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI 兼容格式对话模型 Provider 抽象基类。
 * OpenAI / 智谱 / 通义(兼容模式) 均使用此格式。
 */
@Slf4j
public abstract class OpenAiCompatibleChatProvider implements ChatModelProvider {

    protected final WebClient webClient;
    protected final ObjectMapper mapper = new ObjectMapper();
    protected final String name;
    protected final String label;
    protected final String provider;
    protected final String model;
    protected final Double temperature;
    protected final Integer maxTokens;
    protected final Set<String> capabilities;

    /**
     * 同步调用总超时: 超过该时长仍未返回完整响应即中断并抛异常。
     * 同步模式只有一次发射, 故为"总等待时长"。可按需调整, 后续可提升为可配置字段。
     */
    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(120);

    /**
     * 流式空闲超时: 自上一次收到数据(或订阅)起, 超过该时长仍无新 chunk 即中断。
     * 这是"空闲/无响应超时"而非"总时长", 长文本持续生成不会触发; 用于防止模型服务假死挂起。
     */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(120);

    protected OpenAiCompatibleChatProvider(String baseUrl, String apiKey, String name,
                                           String label, String provider, String model,
                                           Double temperature, Integer maxTokens, List<String> capabilities) {
        this.webClient = WebClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json").build();
        this.name = name;
        this.label = label;
        this.provider = provider;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.capabilities = capabilities != null ? Set.copyOf(capabilities) : Set.of();
    }

    protected String getChatPath() {
        return "/v1/chat/completions";
    }

    @Override
    public ModelType getType() {
        return ModelType.CHAT;
    }

    @Override
    public String getName() {
        return name;
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

    /**
     * 构建请求体
     */
    protected String buildRequestBody(ChatRequest request, List<Message> history, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        if (temperature != null)
            body.put("temperature", request.getTemperature() != null ? request.getTemperature() : temperature);
        if (maxTokens != null) body.put("max_tokens", maxTokens);

        ArrayNode messages = body.putArray("messages");
        if (history != null) {
            for (Message msg : history) {
                if (msg.getContent() == null || msg.getContent().isBlank()) continue;
                ObjectNode mNode = messages.addObject();
                mNode.put("role", msg.getRole());
                List<String> imgs = msg.getImages();
                if (imgs != null && !imgs.isEmpty() && supportsVision() && "user".equals(msg.getRole())) {
                    ArrayNode content = mNode.putArray("content");
                    content.addObject().put("type", "text").put("text", msg.getContent());
                    for (String img : imgs) {
                        content.addObject().put("type", "image_url")
                                .putObject("image_url").put("url", img);
                    }
                } else {
                    mNode.put("content", msg.getContent());
                }
            }
        }
        // 当前用户消息
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        List<String> images = request.getImages();
        if (images != null && !images.isEmpty() && supportsVision()) {
            ArrayNode content = userMsg.putArray("content");
            content.addObject().put("type", "text").put("text", request.getMessage());
            for (String img : images) {
                content.addObject().put("type", "image_url")
                        .putObject("image_url").put("url", img);
            }
        } else {
            if (images != null && !images.isEmpty() && !supportsVision()) {
                log.warn("[{}] 当前模型不支持视觉能力, 已忽略 {} 张上传图片", name, images.size());
            }
            userMsg.put("content", request.getMessage());
        }
        return body.toString();
    }

    @Override
    public ChatResponse invoke(ChatRequest request, List<Message> history) {
        String json = buildRequestBody(request, history, false);
        try {
        String resp = webClient.post().uri(getChatPath()).bodyValue(json)
                .retrieve().bodyToMono(String.class).block(SYNC_TIMEOUT);
            JsonNode root = mapper.readTree(resp);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            ChatResponse response = new ChatResponse();
            response.setContent(content);
            response.setModel(model);
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
        String json = buildRequestBody(request, history, true);
        AtomicLong rawLines = new AtomicLong();
        AtomicLong dataLines = new AtomicLong();
        AtomicLong produced = new AtomicLong();
        log.info("{} 发起流式请求, body={}", name, json);
        return webClient.post().uri(getChatPath()).bodyValue(json)
                .retrieve().bodyToFlux(String.class)
                .timeout(STREAM_TIMEOUT)
                .doOnNext(line -> {
                    long n = rawLines.incrementAndGet();
                    log.debug("[RAW] 收到字符串行({}): {}", n, line);
                })
                .map(line -> {
                    // 兼容两种格式: 标准 SSE 带 "data:" 前缀, 或裸 JSON 行(无前缀)
                    String trimmed = line.trim();
                    if (trimmed.startsWith("data:")) {
                        trimmed = trimmed.substring(5).trim();
                    }
                    return trimmed;
                })
                .filter(data -> !data.isEmpty() && !"[DONE]".equals(data))
                .doOnNext(d -> dataLines.incrementAndGet())
                .mapNotNull(data -> {
                    try {
                        JsonNode root = mapper.readTree(data);
                        // 1. usage 节点(流式最后一块, choices 可能为空): 优先识别并回传真实 token 消耗
                        //    注意: 该块同时携带 finish_reason, 需一并读取(不要 early-return 漏掉)
                        JsonNode usageNode = root.path("usage");
                        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                            int prompt = usageNode.path("prompt_tokens").asInt(0);
                            int completion = usageNode.path("completion_tokens").asInt(0);
                            int total = usageNode.path("total_tokens").asInt(0);
                            log.debug("[USAGE] 解析到 token 消耗: prompt={}, completion={}, total={}", prompt, completion, total);
                            ChatChunk usageChunk = ChatChunk.usage(prompt, completion, total);
                            JsonNode finishNode = root.path("choices").path(0).path("finish_reason");
                            if (!finishNode.isMissingNode() && !finishNode.isNull()) {
                                String fr = finishNode.asText();
                                if (!fr.isEmpty()) {
                                    usageChunk.setFinishReason(fr);
                                    log.debug("[FINISH] 解析到结束原因: {}", fr);
                                }
                            }
                            return usageChunk;
                        }
                        JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                        JsonNode reasoningContent = root.path("choices").path(0).path("delta").path("reasoning_content");
                        // reasoning_content(思维链) 与 content(正文) 是模型返回的两个互斥阶段,
                        // 每个 chunk 只携带当前实际存在的那个字段, 另一个保持为 null 不下发
                        String contentText = (!delta.isMissingNode() && !delta.isNull()) ? delta.asText() : null;
                        String reasoningText = (!reasoningContent.isMissingNode() && !reasoningContent.isNull()) ? reasoningContent.asText() : null;
                        boolean hasContent = contentText != null && !contentText.isEmpty();
                        boolean hasReasoning = reasoningText != null && !reasoningText.isEmpty();
                        if (hasContent || hasReasoning) {
                            produced.incrementAndGet();
                            log.debug("[CONTENT] 解析到推理内容: {}，[CONTENT] 解析到文本块: {}", reasoningText, contentText);
                            return ChatChunk.chunk(hasContent ? contentText : null, hasReasoning ? reasoningText : null);
                        }
                        return null;
                    } catch (Exception e) {
                        log.warn("解析SSE失败: {}", data);
                        return null;
                    }
                })
                .doOnComplete(() -> log.info("{} 流式接收完成: 原始字符串行={}, 有效data行={}, 生成文本块={}",
                        name, rawLines.get(), dataLines.get(), produced.get()))
                .onErrorResume(e -> {
                    log.error("{} 流式调用失败", name, e);
                    return Flux.just(ChatChunk.error("流式调用失败: " + e.getMessage(), "MODEL_ERROR"));
                });
    }
}
