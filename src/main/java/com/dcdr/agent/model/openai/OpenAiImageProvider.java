package com.dcdr.agent.model.openai;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.model.provider.ImageModelProvider;
import com.dcdr.agent.dto.ImageRequest;
import com.dcdr.agent.dto.ImageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI 图片生成 Provider (DALL-E 3)
 */
@Slf4j
public class OpenAiImageProvider implements ImageModelProvider {

    /** 单次生成请求的最大等待时长, 超时判定为失败以释放线程池线程 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String name = "openai-image";
    private final String model;
    private final String defaultSize;

    public OpenAiImageProvider(String baseUrl, String apiKey, String model, String defaultSize) {
        this.webClient = WebClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json").build();
        this.model = model;
        this.defaultSize = defaultSize != null ? defaultSize : "1024x1024";
    }

    @Override public String getName() { return name; }
    @Override public ModelType getType() { return ModelType.IMAGE; }
    @Override public String getLabel() { return "DALL-E 3"; }
    @Override public String getProvider() { return "openai"; }

    @Override
    public ImageResponse generate(ImageRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", request.getPrompt());
        body.put("n", 1);
        body.put("size", request.getSize() != null ? request.getSize() : defaultSize);
        if (request.getStyle() != null && !request.getStyle().isBlank()) {
            body.put("style", request.getStyle());
        }
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
                log.error("OpenAI图片生成超时({}s), 判定失败", REQUEST_TIMEOUT.getSeconds(), e);
                throw new RuntimeException("图片生成超时, 请稍后重试", e);
            }
            log.error("OpenAI图片生成失败", e);
            throw new RuntimeException("图片生成失败: " + e.getMessage(), e);
        }
    }

    /** 判断异常链中是否包含超时(超时给出独立的错误提示) */
    private boolean isTimeout(Throwable e) {
        while (e != null) {
            if (e instanceof TimeoutException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }
}
