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

/**
 * OpenAI 图片生成 Provider (DALL-E 3)
 */
@Slf4j
public class OpenAiImageProvider implements ImageModelProvider {

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
            String resp = webClient.post().uri("/v1/images/generations")
                    .bodyValue(body.toString())
                    .retrieve().bodyToMono(String.class).block();
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
            log.error("OpenAI图片生成失败, 进入演示模式", e);
            return demoResponse(request);
        }
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
