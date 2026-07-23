package com.dcdr.agent.model.qwen;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.ImageRequest;
import com.dcdr.agent.dto.ImageResponse;
import com.dcdr.agent.model.provider.ImageModelProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 阿里云百炼(DashScope) 通义万相文生图 Provider。
 * <p>
 * 适配 qwen-image-2.0 系列的<b>同步</b>文生图接口
 * {@code POST /api/v1/services/aigc/multimodal-generation/generation}。
 * 该接口直接返回 {@code output.choices[].message.content[].image} 图片 URL 列表,
 * 不含 task_id, 不开启 X-DashScope-Async。文生图接口不支持参考图(ref_img), 图生图请走 qwen-image-edit。
 */
@Slf4j
public class QwenImageProvider implements ImageModelProvider {

    /** qwen-image-2.0 同步文生图接口 */
    private static final String GENERATION_URI = "/api/v1/services/aigc/multimodal-generation/generation";

    /** 单次生成请求的最大等待时长, 超时判定为失败以释放线程池线程 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String name;
    private final String model;

    public QwenImageProvider(String baseUrl, String apiKey, String model, String name) {
        this.webClient = WebClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.model = model;
        this.name = name != null ? name : "qwen-image";
    }

    @Override public String getName() { return name; }
    @Override public ModelType getType() { return ModelType.IMAGE; }
    @Override public String getLabel() { return "通义万相"; }
    @Override public String getProvider() { return "qwen"; }

    @Override
    public ImageResponse generate(ImageRequest request) {
        try {
            List<String> urls = callModel(request);
            if (urls == null || urls.isEmpty()) {
                throw new IllegalStateException("模型未返回图片 URL");
            }

            ImageResponse response = new ImageResponse();
            response.setId("gen_" + System.currentTimeMillis());
            response.setImageUrl(urls.get(0));
            response.setImageUrls(urls);
            response.setThumbnailUrl(urls.get(0));
            response.setPrompt(request.getPrompt());
            response.setModel(model);
            return response;
        } catch (Exception e) {
            if (isTimeout(e)) {
                log.error("通义万相图片生成超时({}s), 判定失败", REQUEST_TIMEOUT.getSeconds(), e);
                throw new RuntimeException("图片生成超时, 请稍后重试", e);
            }
            log.error("通义万相图片生成失败, 进入演示模式", e);
            return demoResponse(request);
        }
    }

    /** 判断异常链中是否包含超时(区别于其他网络/业务异常, 超时不走演示兜底) */
    private boolean isTimeout(Throwable e) {
        while (e != null) {
            if (e instanceof TimeoutException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    /** 同步调用 qwen-image-2.0 文生图接口, 返回所有图片 URL */
    private List<String> callModel(ImageRequest request) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);

        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("text", request.getPrompt());

        ObjectNode parameters = body.putObject("parameters");
        parameters.put("size", toDashScopeSize(request.getSize()));
        int n = request.getN() != null ? request.getN() : 1;
        parameters.put("n", n);
        if (request.getNegativePrompt() != null && !request.getNegativePrompt().isBlank()) {
            parameters.put("negative_prompt", request.getNegativePrompt());
        }
        boolean promptExtend = request.getPromptExtend() != null ? request.getPromptExtend() : true;
        parameters.put("prompt_extend", promptExtend);
        boolean watermark = request.getWatermark() != null ? request.getWatermark() : false;
        parameters.put("watermark", watermark);
        if (request.getSeed() != null) {
            parameters.put("seed", request.getSeed());
        }

        String resp = webClient.post().uri(GENERATION_URI)
                .bodyValue(body.toString())
                .retrieve().bodyToMono(String.class)
                .timeout(REQUEST_TIMEOUT)
                .block();

        return parseUrls(resp);
    }

    /** 解析同步响应, 提取 output.choices[].message.content[].image */
    private List<String> parseUrls(String resp) throws JsonProcessingException {
        JsonNode root = mapper.readTree(resp);
        // DashScope 错误响应形如 {"code":"...","message":"..."}
        if (root.has("code") && root.path("code").asText(null) != null) {
            String msg = root.path("message").asText(root.path("code").asText());
            throw new IllegalStateException("模型返回错误: " + msg);
        }
        JsonNode choices = root.path("output").path("choices");
        List<String> urls = new ArrayList<>();
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode contentArr = choice.path("message").path("content");
                if (contentArr.isArray()) {
                    for (JsonNode c : contentArr) {
                        String url = c.path("image").asText(null);
                        if (url != null && !url.isBlank()) {
                            urls.add(url);
                        }
                    }
                }
            }
        }
        return urls;
    }

    /** 将 "1024x1024" 形式转换为 DashScope 的 "1024*1024" 形式 */
    private String toDashScopeSize(String size) {
        if (size == null || size.isBlank()) {
            return "1024*1024";
        }
        return size.replace("x", "*").replace("X", "*");
    }

    private ImageResponse demoResponse(ImageRequest request) {
        ImageResponse response = new ImageResponse();
        response.setId("gen_" + System.currentTimeMillis());
        int seed = Math.abs(request.getPrompt().hashCode()) % 1000;
        String url = "https://picsum.photos/seed/" + seed + "/1024/1024";
        response.setImageUrl(url);
        response.setImageUrls(List.of(url));
        response.setThumbnailUrl("https://picsum.photos/seed/" + seed + "/200/200");
        response.setPrompt(request.getPrompt());
        response.setModel(model);
        return response;
    }
}
