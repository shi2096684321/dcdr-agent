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

/**
 * 智谱 CogView 图片生成 Provider
 */
@Slf4j
public class ZhipuImageProvider implements ImageModelProvider {

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
            log.error("智谱图片生成失败, 进入演示模式", e);
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
