package com.dcdr.agent.agent.creative;

import com.dcdr.agent.agent.session.ProjectManager;
import com.dcdr.agent.common.util.ReferImageStorage;
import com.dcdr.agent.dto.GenerationDTO;
import com.dcdr.agent.dto.ImageRequest;
import com.dcdr.agent.dto.VideoRequest;
import com.dcdr.agent.model.provider.VideoModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创作引擎 - 编排图片/视频生成(创作模式)
 */
@Slf4j
@Component
public class CreativeEngine {

    private final ModelRegistry registry;
    private final ProjectManager projectManager;
    private final ImageGenerationWorker imageGenerationWorker;
    private final ReferImageStorage referImageStorage;
    private final ObjectMapper objectMapper;

    public CreativeEngine(ModelRegistry registry, ProjectManager projectManager,
                          ImageGenerationWorker imageGenerationWorker, ReferImageStorage referImageStorage,
                          ObjectMapper objectMapper) {
        this.registry = registry;
        this.projectManager = projectManager;
        this.imageGenerationWorker = imageGenerationWorker;
        this.referImageStorage = referImageStorage;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交图片生成任务(异步)。
     * <p>
     * 立即创建一条 pending 记录并返回, 真正的模型调用在后台线程池执行,
     * 完成后回写状态。前端凭返回的记录 ID 轮询 {@code GET /api/generations/{id}} 获取结果。
     */
    public List<GenerationDTO> generateImage(ImageRequest request) {
        log.info("提交图片生成任务, 模型: {}, 提示词: {}", request.getModel(), request.getPrompt());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }

        String projectId = request.getProjectId();
        if (projectId == null) {
            projectId = projectManager.createProject("未命名项目", "image", request.getModel()).getId();
        }

        // 官方接口仅支持单次单张生成, 应用层将 n>1 拆分为 n 个互相独立的任务,
        // 每个任务单线程处理一张图(各自一条 pending 记录), 效果等同逐张生成。
        int n = (request.getN() == null || request.getN() < 1) ? 1 : request.getN();
        // 参考图与参数在各任务间共享, 提前计算一次避免重复落盘
        String referImageUrl = referImageStorage.save(request.getReferImage());
        String paramsJson = buildParamsJson(request);

        List<GenerationDTO> records = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ImageRequest single = cloneWithN(request, projectId, 1);
            GenerationDTO record = createPendingRecord(single, projectId, referImageUrl, paramsJson);
            projectManager.addGeneration(projectId, record);
            // 后台异步执行(各自独立线程), 不阻塞当前请求线程
            imageGenerationWorker.process(record.getId(), single);
            records.add(record);
        }

        log.info("图片生成任务已提交, 共{}个独立任务, 状态=pending", records.size());
        return records;
    }

    /** 复制请求并将生成数量固定为 1(单任务单张) */
    private ImageRequest cloneWithN(ImageRequest src, String projectId, int n) {
        ImageRequest r = new ImageRequest();
        r.setProjectId(projectId);
        r.setModel(src.getModel());
        r.setPrompt(src.getPrompt());
        r.setReferImage(src.getReferImage());
        r.setEngineType(src.getEngineType());
        r.setSize(src.getSize());
        r.setAspectRatio(src.getAspectRatio());
        r.setStyle(src.getStyle());
        r.setNegativePrompt(src.getNegativePrompt());
        r.setN(n);
        r.setPromptExtend(src.getPromptExtend());
        r.setWatermark(src.getWatermark());
        r.setSeed(src.getSeed());
        r.setSafeMode(src.isSafeMode());
        return r;
    }

    /** 构造一条 pending 状态的生成记录(不触发实际模型调用) */
    private GenerationDTO createPendingRecord(ImageRequest request, String projectId,
                                               String referImageUrl, String paramsJson) {
        GenerationDTO record = new GenerationDTO();
        record.setId("gen_" + UUID.randomUUID().toString().replace("-", ""));
        record.setProjectId(projectId);
        record.setType(request.getEngineType() != null ? request.getEngineType() : "image");
        record.setPrompt(request.getPrompt());
        record.setSize(request.getSize());
        record.setReferImageUrl(referImageUrl);
        record.setModel(request.getModel());
        record.setParamsJson(paramsJson);
        record.setCostCredits(calculateCredits(request.getPrompt()));
        record.setStatus("pending");
        record.setCreatedAt(LocalDateTime.now());
        return record;
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

    /** 将提交给模型的扩展参数序列化进 paramsJson, 便于后续回显与审计 */
    private String buildParamsJson(ImageRequest request) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
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
            log.warn("生成参数序列化失败, 将不写入 paramsJson", e);
            return null;
        }
    }
}
