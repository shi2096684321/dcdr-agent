package com.dcdr.agent.agent.creative;

import com.dcdr.agent.agent.session.ProjectManager;
import com.dcdr.agent.common.util.GeneratedImageStorage;
import com.dcdr.agent.common.util.ReferImageStorage;
import com.dcdr.agent.dto.GenerationDTO;
import com.dcdr.agent.dto.ImageRequest;
import com.dcdr.agent.dto.ImageResponse;
import com.dcdr.agent.model.provider.ImageModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 图片生成异步执行器。
 * <p>
 * 独立成 Bean 是为了让 {@link Async} 代理生效(避免同类自调用失效),
 * 在后台线程池中执行真正的模型调用, 完成后回写生成记录状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenerationWorker {

    private final ModelRegistry registry;
    private final ProjectManager projectManager;
    private final ReferImageStorage referImageStorage;
    private final GeneratedImageStorage generatedImageStorage;
    private final ObjectMapper objectMapper;

    /**
     * 异步执行图片生成, 完成/失败后更新生成记录。
     *
     * @param generationId 生成记录 ID (提交阶段已入库, 状态为 pending)
     * @param request      图片生成请求
     */
    @Async("generationExecutor")
    public void process(String generationId, ImageRequest request) {
        long start = System.currentTimeMillis();
        GenerationDTO update = new GenerationDTO();
        update.setId(generationId);
        try {
            ImageModelProvider provider = registry.getImageProvider(request.getModel());
            ImageResponse response = provider.generate(request);

            List<String> urls = response.getImageUrls();
            if (urls == null || urls.isEmpty()) {
                urls = Collections.singletonList(response.getImageUrl());
            }
            // 逐张落盘到本地存储, 转为长期可访问的内网 URL(避免厂商临时链接过期)
            List<String> localUrls = urls.stream().map(generatedImageStorage::store).collect(Collectors.toList());

            update.setStatus("completed");
            update.setResultUrl(localUrls.get(0));
            String thumbnail = response.getThumbnailUrl();
            update.setThumbnailUrl(thumbnail != null ? generatedImageStorage.store(thumbnail) : localUrls.get(0));
            log.info("图片异步生成完成, id={}, 共{}张, 耗时 {}ms", generationId, urls.size(), System.currentTimeMillis() - start);

            // n>1 时, 额外图片各自落一条生成记录, 前端按项目历史自然展示
            for (int i = 1; i < urls.size(); i++) {
                GenerationDTO extra = buildExtraGeneration(request, urls.get(i));
                projectManager.addGeneration(request.getProjectId(), extra);
            }
        } catch (Exception e) {
            update.setStatus("failed");
            update.setErrorMessage(e.getMessage());
            log.error("图片异步生成失败, id={}", generationId, e);
        }
        update.setDurationMs(System.currentTimeMillis() - start);
        projectManager.updateGeneration(update);
    }

    /** 为 n>1 的额外图片构建一条生成记录 */
    private GenerationDTO buildExtraGeneration(ImageRequest request, String url) {
        GenerationDTO extra = new GenerationDTO();
        extra.setId("gen_" + UUID.randomUUID().toString().replace("-", ""));
        extra.setProjectId(request.getProjectId());
        // type 是生成记录的类型(image/video)。与主记录保持一致, type 固定为 "image"。
        extra.setType("image");
        extra.setPrompt(request.getPrompt());
        extra.setSize(request.getSize());
        extra.setReferImageUrl(referImageStorage.save(request.getReferImage()));
        // 与主记录一致: 下载落盘, 转为本地长期 URL(避免厂商临时链接过期)
        String localUrl = generatedImageStorage.store(url);
        extra.setResultUrl(localUrl);
        extra.setThumbnailUrl(localUrl);
        extra.setModel(request.getModel());
        extra.setParamsJson(buildParamsJson(request));
        extra.setStatus("completed");
        extra.setCostCredits(1);
        extra.setCreatedAt(LocalDateTime.now());
        return extra;
    }

    /** 与主记录保持一致的参数序列化(便于详情回显) */
        private String buildParamsJson(ImageRequest request) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            // 提示词与尺寸需入库, 否则详情/套用无法回填
            if (request.getPrompt() != null && !request.getPrompt().isBlank()) {
                params.put("prompt", request.getPrompt());
            }
            if (request.getSize() != null && !request.getSize().isBlank()) {
                params.put("size", request.getSize());
            }
            // 参考图仅记录是否使用, base64 不入库, 套用时无法回填
            params.put("hasReferImage", request.getReferImage() != null && !request.getReferImage().isBlank());
            if (request.getNegativePrompt() != null && !request.getNegativePrompt().isBlank()) {
                params.put("negativePrompt", request.getNegativePrompt());
            }
            if (request.getStyle() != null && !request.getStyle().isBlank()) {
                params.put("style", request.getStyle());
            }
            if (request.getN() != null) {
                params.put("n", request.getN());
            }
            if (request.getPromptExtend() != null) {
                params.put("promptExtend", request.getPromptExtend());
            }
            if (request.getWatermark() != null) {
                params.put("watermark", request.getWatermark());
            }
            if (request.getSeed() != null) {
                params.put("seed", request.getSeed());
            }
            return params.isEmpty() ? null : objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            log.warn("额外图片参数序列化失败, 将不写入 paramsJson", e);
            return null;
        }
    }
}
