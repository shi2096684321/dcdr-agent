package com.dcdr.agent.model.zhipu;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.model.provider.ImageModelProvider;
import com.dcdr.agent.dto.ImageRequest;
import com.dcdr.agent.dto.ImageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 智谱 CogView 图片生成 Provider
 */
@Slf4j
public class ZhipuImageProvider implements ImageModelProvider {

    /** 单次生成请求的最大等待时长, 超时判定为失败以释放线程池线程 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String name = "zhipu-image";
    private final String model;

    public ZhipuImageProvider(String baseUrl, String apiKey, String model) {
        this.webClient = WebClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json").build();
        this.model = model;
    }

    @Override public String getName() { return name; }
    @Override public ModelType getType() { return ModelType.IMAGE; }
    @Override public String getLabel() { return "CogView-3"; }
    @Override public String getProvider() { return "zhipu"; }

    @Override
    public ImageResponse generate(ImageRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", request.getPrompt());
        try {
            String resp = webClient.post().uri("/images/generations")
                    .bodyValue(body.toString())
                    .retrieve().bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();
            JsonNode root = mapper.readTree(resp);
            String imageUrl = root.path("data").path(0).path("url").asText();
            ImageResponse response = new ImageResponse();
            response.setId("gen_" + System.currentTimeMillis());
            response.setImageUrl(imageUrl);
            response.setThumbnailUrl(imageUrl);
            response.setPrompt(request.getPrompt());
            response.setModel(model);
            return response;
        } catch (Exception e) {
            if (isTimeout(e)) {
                log.error("智谱图片生成超时({}s), 判定失败", REQUEST_TIMEOUT.getSeconds(), e);
                throw new RuntimeException("图片生成超时, 请稍后重试", e);
            }
            String raw = extractResponseBody(e);
            String bizMsg = parseBusinessError(raw);
            if (bizMsg != null) {
                // 平台明确拒绝的业务错误(鉴权/参数/IP侵权等): 直接抛出让前端提示失败, 不返回占位假图
                log.error("智谱图片生成被平台拒绝: {}", bizMsg);
                throw new RuntimeException("图片生成失败: " + bizMsg, e);
            }
            log.error("智谱图片生成失败, 进入演示模式{}", raw, e);
            return demoResponse(request);
        }
    }

    /** 从 WebClient 异常中提取服务端返回的原始错误体(纯文本), 便于排查 401 等 */
    private String extractResponseBody(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof WebClientResponseException wcre) {
                String b = wcre.getResponseBodyAsString();
                if (b != null && !b.isBlank()) {
                    return " [服务端返回: " + b + "]";
                }
                return " [HTTP " + wcre.getStatusCode() + "]";
            }
            t = t.getCause();
        }
        return "";
    }

    /** 解析平台返回的结构化业务错误(code+message); 非业务错误(如网络异常)返回 null, 继续走演示兜底 */
    private String parseBusinessError(String detail) {
        if (detail == null || detail.isBlank() || !detail.startsWith(" [服务端返回: ")) {
            return null;
        }
        String json = detail.substring(" [服务端返回: ".length(), detail.length() - 1);
        try {
            JsonNode root = mapper.readTree(json);
            if (root.has("code") && root.path("code").asText(null) != null) {
                return root.path("message").asText(root.path("code").asText());
            }
        } catch (Exception ignore) {
            // 非 JSON, 视为非结构化错误, 继续演示兜底
        }
        return null;
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

    private ImageResponse demoResponse(ImageRequest request) {
        ImageResponse response = new ImageResponse();
        response.setId("gen_" + System.currentTimeMillis());
        int seed = Math.abs(request.getPrompt().hashCode()) % 1000;
        response.setImageUrl("https://picsum.photos/seed/" + seed + "/1024/1024");
        response.setThumbnailUrl("https://picsum.photos/seed/" + seed + "/200/200");
        response.setPrompt(request.getPrompt());
        response.setModel(model);
        return response;
    }
}
