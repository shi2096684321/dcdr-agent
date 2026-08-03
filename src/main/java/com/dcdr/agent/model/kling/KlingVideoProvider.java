package com.dcdr.agent.model.kling;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.GenerationDTO;
import com.dcdr.agent.dto.VideoRequest;
import com.dcdr.agent.model.provider.VideoModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 可灵 (Kling) 视频生成 Provider
 * <p>
 * 文生视频流程:
 * 1. POST /v1/videos/text2video 创建任务, 返回 task_id
 * 2. 轮询 GET /v1/videos/text2video/{taskId} 直到 task_status = succeed
 * 3. 从 task_result.videos[0].url 取视频地址
 * <p>
 * 注意: 可灵官方鉴权需用 access_key/secret_key 生成 JWT, 此处先用 Bearer 简化框架,
 * 实际接入时替换为 JWT 鉴权。未配置有效 Key 时进入演示模式返回兜底视频。
 * <p>
 * 本方法为同步阻塞调用(耗时 30s~数分钟), 由 {@link com.dcdr.agent.agent.creative.VideoGenerationWorker}
 * 在后台线程池中异步执行。
 */
@Slf4j
public class KlingVideoProvider implements VideoModelProvider {

    /** 单次生成最大等待时长 */
    private static final Duration MAX_WAIT = Duration.ofMinutes(5);
    /** 轮询间隔 */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String name;
    private final String model;
    private final boolean demo;

    public KlingVideoProvider(String baseUrl, String apiKey, String model, String name) {
        this.webClient = WebClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.model = model;
        this.name = name;
        this.demo = apiKey == null || apiKey.isBlank() || apiKey.contains("demo");
    }

    @Override public String getName() { return name; }
    @Override public ModelType getType() { return ModelType.VIDEO; }
    @Override public String getLabel() { return "可灵 Kling"; }
    @Override public String getProvider() { return "kuaishou"; }

    @Override
    public List<String> capabilities() {
        return List.of(CAP_END_IMAGE);
    }

    @Override
    public GenerationDTO generate(VideoRequest request) {
        if (demo) {
            log.warn("可灵视频处于演示模式(API Key 未配置), 返回兜底视频");
            return demoResult(request);
        }

        try {
            // 1. 创建视频生成任务
            String taskId = createTask(request);
            log.info("可灵视频任务已创建, taskId={}, model={}", taskId, request.getModel());

            // 2. 轮询任务状态(文生视频与图生视频的轮询端点前缀不同)
            String pollPrefix = (request.getReferImage() != null && !request.getReferImage().isBlank())
                    ? "/v1/videos/image2video/" : "/v1/videos/text2video/";
            String videoUrl = pollTask(taskId, pollPrefix);
            log.info("可灵视频生成完成, taskId={}, videoUrl={}", taskId, videoUrl);

            // 3. 构造结果
            GenerationDTO result = new GenerationDTO();
            result.setType("video");
            result.setPrompt(request.getPrompt());
            result.setModel(request.getModel());
            result.setResultUrl(videoUrl);
            result.setThumbnailUrl(videoUrl);
            result.setStatus("completed");
            result.setCreatedAt(LocalDateTime.now());
            return result;
        } catch (Exception e) {
            log.error("可灵视频生成失败", e);
            throw new RuntimeException("视频生成失败: " + e.getMessage(), e);
        }
    }

    /** 创建视频任务(支持文生视频与图生视频), 返回任务 ID */
    private String createTask(VideoRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model_name", model);
        body.put("prompt", request.getPrompt());
        if (request.getDuration() != null) {
            body.put("duration", parseDuration(request.getDuration()));
        }
        if (request.getAspectRatio() != null) {
            body.put("aspect_ratio", request.getAspectRatio());
        }

        // 首帧参考图(图生视频): 非空则走 image2video 端点, 否则文生视频
        String uri;
        if (request.getReferImage() != null && !request.getReferImage().isBlank()) {
            // 可灵 image2video 用 media 数组表达首尾帧
            ArrayNode media = body.putArray("media");
            ObjectNode first = media.addObject();
            first.put("type", "first_frame");
            first.put("file", request.getReferImage());
            // 尾帧参考图(首尾帧模式): 配合首帧生效
            if (request.getEndImage() != null && !request.getEndImage().isBlank()) {
                ObjectNode last = media.addObject();
                last.put("type", "last_frame");
                last.put("file", request.getEndImage());
                log.info("可灵使用首尾帧模式(首帧+尾帧), model={}", model);
            } else {
                log.info("可灵使用图生视频(仅首帧), model={}", model);
            }
            uri = "/v1/videos/image2video";
        } else {
            uri = "/v1/videos/text2video";
        }

        String resp = webClient.post().uri(uri)
                .bodyValue(body.toString())
                .retrieve().bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();
        try {
            JsonNode root = mapper.readTree(resp);
            String taskId = root.path("data").path("task_id").asText();
            if (taskId.isEmpty()) {
                throw new RuntimeException("创建视频任务未返回 task_id: " + resp);
            }
            return taskId;
        } catch (Exception e) {
            throw new RuntimeException("解析视频任务响应失败: " + e.getMessage(), e);
        }
    }

    /** 轮询任务状态直到完成, 返回视频 URL */
    private String pollTask(String taskId, String pollPrefix) throws InterruptedException {
        long deadline = System.currentTimeMillis() + MAX_WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String resp = webClient.get().uri(pollPrefix + taskId)
                    .retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            try {
                JsonNode root = mapper.readTree(resp);
                String status = root.path("data").path("task_status").asText();
                log.debug("可灵视频任务状态: taskId={}, status={}", taskId, status);
                if ("succeed".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) {
                    String url = root.path("data").path("task_result").path("videos").path(0).path("url").asText();
                    if (url.isEmpty()) {
                        throw new RuntimeException("视频任务完成但未返回 URL: " + resp);
                    }
                    return url;
                }
                if ("failed".equalsIgnoreCase(status)) {
                    String msg = root.path("data").path("task_status_desc").asText("视频生成失败");
                    throw new RuntimeException("视频任务失败: " + msg);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.warn("解析视频任务状态响应失败, 继续轮询", e);
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new RuntimeException("视频生成超时(>" + MAX_WAIT.toMinutes() + "分钟)");
    }

    /** "5s" -> 5 */
    private int parseDuration(String duration) {
        if (duration == null) return 5;
        String num = duration.replaceAll("[^0-9]", "");
        return num.isEmpty() ? 5 : Integer.parseInt(num);
    }

    /** 演示模式兜底: 返回一个示例视频占位 */
    private GenerationDTO demoResult(VideoRequest request) {
        GenerationDTO result = new GenerationDTO();
        result.setType("video");
        result.setPrompt(request.getPrompt());
        result.setModel(request.getModel());
        // 占位视频(演示用, 实际不可播放)
        result.setResultUrl("https://www.w3schools.com/html/mov_bbb.mp4");
        result.setThumbnailUrl(result.getResultUrl());
        result.setStatus("completed");
        result.setCreatedAt(LocalDateTime.now());
        return result;
    }
}
