package com.example.agent.agent.creative;

import com.example.agent.agent.session.ProjectManager;
import com.example.agent.dto.GenerationDTO;
import com.example.agent.dto.ImageRequest;
import com.example.agent.dto.ImageResponse;
import com.example.agent.dto.VideoRequest;
import com.example.agent.model.provider.ImageModelProvider;
import com.example.agent.model.provider.VideoModelProvider;
import com.example.agent.model.registry.ModelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 创作引擎 - 编排图片/视频生成(创作模式)
 */
@Slf4j
@Component
public class CreativeEngine {

    private final ModelRegistry registry;
    private final ProjectManager projectManager;

    public CreativeEngine(ModelRegistry registry, ProjectManager projectManager) {
        this.registry = registry;
        this.projectManager = projectManager;
    }

    /** 生成图片 */
    public ImageResponse generateImage(ImageRequest request) {
        long start = System.currentTimeMillis();
        log.info("开始生成图片, 模型: {}, 提示词: {}", request.getModel(), request.getPrompt());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }

        ImageModelProvider provider = registry.getImageProvider(request.getModel());
        ImageResponse response = provider.generate(request);

        long duration = System.currentTimeMillis() - start;
        response.setCostCredits(calculateCredits(request.getPrompt()));
        response.setCreatedAt(LocalDateTime.now());

        GenerationDTO record = new GenerationDTO();
        record.setType("image");
        record.setPrompt(request.getPrompt());
        record.setReferImageUrl(request.getReferImage());
        record.setResultUrl(response.getImageUrl());
        record.setThumbnailUrl(response.getThumbnailUrl());
        record.setModel(request.getModel());
        record.setCostCredits(response.getCostCredits());
        record.setStatus("completed");
        record.setDurationMs(duration);
        record.setCreatedAt(LocalDateTime.now());

        String projectId = request.getProjectId();
        if (projectId == null) {
            projectId = projectManager.createProject("未命名项目", "image", request.getModel()).getId();
        }
        record.setProjectId(projectId);
        projectManager.addGeneration(projectId, record);
        response.setProjectId(projectId);

        log.info("图片生成完成, 耗时 {}ms", duration);
        return response;
    }

    /** 生成视频 */
    public GenerationDTO generateVideo(VideoRequest request) {
        long start = System.currentTimeMillis();
        log.info("开始生成视频, 模型: {}", request.getModel());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }

        VideoModelProvider provider = registry.getVideoProvider(request.getModel());
        GenerationDTO result = provider.generate(request);

        long duration = System.currentTimeMillis() - start;
        result.setDurationMs(duration);
        result.setCreatedAt(LocalDateTime.now());
        result.setCostCredits(10);

        String projectId = request.getProjectId();
        if (projectId == null) {
            projectId = projectManager.createProject("未命名项目", "video", request.getModel()).getId();
        }
        result.setProjectId(projectId);
        projectManager.addGeneration(projectId, result);

        log.info("视频生成完成, 耗时 {}ms", duration);
        return result;
    }

    private int calculateCredits(String prompt) {
        return Math.max(1, (int) Math.ceil((prompt.length() * 1.0) / 50));
    }
}
