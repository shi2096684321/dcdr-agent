package com.dcdr.agent.agent.creative;

import com.dcdr.agent.agent.session.ProjectManager;
import com.dcdr.agent.common.util.GeneratedVideoStorage;
import com.dcdr.agent.dto.GenerationDTO;
import com.dcdr.agent.dto.VideoRequest;
import com.dcdr.agent.model.provider.VideoModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视频生成异步执行器 (V1.1)
 * <p>
 * 与 {@link ImageGenerationWorker} 对称, 独立成 Bean 让 {@link Async} 代理生效。
 * 在后台线程池中执行真正的视频模型调用(同步阻塞, 耗时较长), 完成后回写生成记录状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoGenerationWorker {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ModelRegistry registry;
    private final ProjectManager projectManager;
    private final GeneratedVideoStorage generatedVideoStorage;

    /**
     * 异步执行视频生成, 完成/失败后更新生成记录。
     *
     * @param generationId 生成记录 ID(提交阶段已入库, 状态为 pending)
     * @param request      视频生成请求
     */
    @Async("videoExecutor")
    public void process(String generationId, VideoRequest request) {
        long start = System.currentTimeMillis();
        GenerationDTO update = new GenerationDTO();
        update.setId(generationId);
        try {
            VideoModelProvider provider = registry.getVideoProvider(request.getModel());
            GenerationDTO result = provider.generate(request);

            // 下载视频落盘到本地, 转为长期可访问的内网 URL(避免厂商临时链接过期)
            String localUrl = generatedVideoStorage.store(result.getResultUrl());

            update.setStatus("completed");
            update.setResultUrl(localUrl);
            update.setThumbnailUrl(localUrl);
            update.setType("video");
            update.setModel(result.getModel());
            update.setPrompt(result.getPrompt());
            update.setParamsJson(buildParamsJson(request, result.getModel()));
            log.info("视频异步生成完成, id={}, 耗时 {}ms", generationId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            update.setStatus("failed");
            update.setErrorMessage(e.getMessage());
            log.error("视频异步生成失败, id={}", generationId, e);
        }
        update.setDurationMs(System.currentTimeMillis() - start);
        projectManager.updateGeneration(update);
    }

    /** 序列化视频生成参数, 便于详情回显与套用(首帧/尾帧仅记录是否使用, 不存 base64) */
    private String buildParamsJson(VideoRequest request, String model) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", model);
            if (request.getPrompt() != null && !request.getPrompt().isBlank()) {
                params.put("prompt", request.getPrompt());
            }
            params.put("duration", request.getDuration());
            params.put("aspectRatio", request.getAspectRatio());
            params.put("hasReferImage", request.getReferImage() != null && !request.getReferImage().isBlank());
            params.put("hasEndImage", request.getEndImage() != null && !request.getEndImage().isBlank());
            if (request.getNegativePrompt() != null && !request.getNegativePrompt().isBlank()) {
                params.put("negativePrompt", request.getNegativePrompt());
            }
            if (request.getSeed() != null) {
                params.put("seed", request.getSeed());
            }
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return null;
        }
    }
}
