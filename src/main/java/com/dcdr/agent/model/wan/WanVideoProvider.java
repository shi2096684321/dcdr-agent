package com.dcdr.agent.model.wan;

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
 * 阿里云百炼 通义万相 2.7 文生视频 Provider (wan2.7-t2v-...)。
 * <p>
 * 适配<b>新版协议</b>(仅支持 wan2.7 系列) 的异步视频生成流程:
 * 1. POST {baseUrl}/api/v1/services/aigc/video-generation/video-synthesis 创建任务(需 X-DashScope-Async: enable)
 * 2. GET  {baseUrl}/api/v1/tasks/{task_id} 轮询直到 task_status = SUCCEEDED
 * 3. 从 output.video_url 取视频地址(24h 有效, 由 VideoGenerationWorker 下载转存为长期链接)
 * <p>
 * 由 {@link com.dcdr.agent.agent.creative.VideoGenerationWorker} 在后台线程池中异步执行(同步阻塞等待)。
 */
@Slf4j
public class WanVideoProvider implements VideoModelProvider {

    /** 万相视频合成统一端点 */
    private static final String SYNTHESIS_URI = "/api/v1/services/aigc/video-generation/video-synthesis";
    /** 异步任务轮询端点前缀: 拼接 task_id */
    private static final String TASK_URI_PREFIX = "/api/v1/tasks/";
    /** 单次生成最大等待时长(wan2.7 最长 15s 视频, 通常 1~5 分钟) */
    private static final Duration MAX_WAIT = Duration.ofMinutes(15);
    /** 轮询间隔(官方建议约 15s) */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(15);
    /** 单次 HTTP 请求超时 */
    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String name;
    private final String model;
    /** 图生视频(i2v)专用模型名, 为空时回退到 model */
    private final String modelI2v;
    private final boolean demo;

    public WanVideoProvider(String baseUrl, String apiKey, String model, String name, String modelI2v) {
        this.webClient = WebClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.model = model;
        this.modelI2v = (modelI2v != null && !modelI2v.isBlank()) ? modelI2v : model;
        this.name = name != null ? name : "wan-video";
        this.demo = apiKey == null || apiKey.isBlank() || apiKey.contains("demo");
    }

    @Override public String getName() { return name; }
    @Override public ModelType getType() { return ModelType.VIDEO; }
    @Override public String getLabel() { return "通义万相2.7"; }
    @Override public String getProvider() { return "wan"; }

    @Override
    public List<String> capabilities() {
        return List.of(CAP_END_IMAGE, CAP_NEGATIVE_PROMPT, CAP_SEED);
    }

    @Override
    public GenerationDTO generate(VideoRequest request) {
        if (demo) {
            log.warn("万相视频处于演示模式(API Key 未配置), 返回兜底视频");
            return demoResult(request);
        }

        try {
            // 1. 创建文生视频任务
            String taskId = createTask(request);
            log.info("万相视频任务已创建, taskId={}, model={}", taskId, model);

            // 2. 轮询任务状态
            String videoUrl = pollTask(taskId);
            log.info("万相视频生成完成, taskId={}, videoUrl={}", taskId, videoUrl);

            // 3. 构造结果
            String actualModel = (request.getReferImage() != null && !request.getReferImage().isBlank())
                    ? modelI2v : model;
            GenerationDTO result = new GenerationDTO();
            result.setType("video");
            result.setPrompt(request.getPrompt());
            result.setModel(actualModel);
            result.setResultUrl(videoUrl);
            result.setThumbnailUrl(videoUrl);
            result.setStatus("completed");
            result.setCreatedAt(LocalDateTime.now());
            return result;
        } catch (Exception e) {
            log.error("万相视频生成失败", e);
            throw new RuntimeException("视频生成失败: " + e.getMessage(), e);
        }
    }

    /** 创建视频任务, 返回任务 ID。
     *  文生视频用配置的 model(t2v); 图生视频(有首帧)使用显式配置的 modelI2v 模型,
     *  首帧/尾帧通过 input.media 数组传入(万相 i2v 接口专用字段)。 */
    private String createTask(VideoRequest request) {
        boolean image2video = request.getReferImage() != null && !request.getReferImage().isBlank();
        // 图生视频: 使用显式配置的 i2v 模型名(不再做字符串替换)
        String useModel = image2video ? modelI2v : model;
        if (image2video) {
            log.info("万相图生视频: 使用 i2v 模型 {}", useModel);
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", useModel);

        ObjectNode input = body.putObject("input");
        input.put("prompt", request.getPrompt());
        // 反向提示词(可选): 万相支持, 描述不希望出现的内容
        if (request.getNegativePrompt() != null && !request.getNegativePrompt().isBlank()) {
            input.put("negative_prompt", request.getNegativePrompt());
            log.info("万相使用反向提示词, model={}", useModel);
        }
        // 首帧参考图(图生视频): 万相 i2v 接口用 input.media 数组(first_frame), 而非 input.image
        if (image2video) {
            ArrayNode media = input.putArray("media");
            ObjectNode first = media.addObject();
            first.put("type", "first_frame");
            first.put("url", request.getReferImage());
            // 尾帧参考图(首尾帧模式): 配合首帧生效
            if (request.getEndImage() != null && !request.getEndImage().isBlank()) {
                ObjectNode last = media.addObject();
                last.put("type", "last_frame");
                last.put("url", request.getEndImage());
                log.info("万相使用首尾帧模式(首帧+尾帧), model={}", useModel);
            } else {
                log.info("万相使用图生视频(仅首帧), model={}", useModel);
            }
        }

        ObjectNode params = body.putObject("parameters");
        if (request.getAspectRatio() != null && !request.getAspectRatio().isBlank()) {
            params.put("ratio", request.getAspectRatio());
        }
        if (request.getDuration() != null && !request.getDuration().isBlank()) {
            params.put("duration", parseDuration(request.getDuration()));
        }
        // 随机种子(可选): 用于复现, 仅万相支持
        if (request.getSeed() != null) {
            params.put("seed", request.getSeed());
            log.info("万相使用固定种子复现: {}", request.getSeed());
        }
        // 智能改写 prompt(默认开); 不加水印(默认 false)
        params.put("prompt_extend", true);
        params.put("watermark", false);

        String resp = webClient.post().uri(SYNTHESIS_URI)
                .header("X-DashScope-Async", "enable")
                .bodyValue(body.toString())
                .retrieve().bodyToMono(String.class)
                .timeout(REQ_TIMEOUT)
                .block();
        try {
            JsonNode root = mapper.readTree(resp);
            // DashScope 错误响应形如 {"code":"...","message":"..."}
            if (root.has("code") && root.path("code").asText(null) != null) {
                throw new RuntimeException("创建视频任务失败: " + root.path("message").asText(root.path("code").asText()));
            }
            String taskId = root.path("output").path("task_id").asText();
            if (taskId.isEmpty()) {
                throw new RuntimeException("创建视频任务未返回 task_id: " + resp);
            }
            return taskId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析视频任务响应失败: " + e.getMessage(), e);
        }
    }

    /** 轮询任务状态直到完成, 返回视频 URL */
    private String pollTask(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + MAX_WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String resp = webClient.get().uri(TASK_URI_PREFIX + taskId)
                    .retrieve().bodyToMono(String.class)
                    .timeout(REQ_TIMEOUT)
                    .block();
            try {
                JsonNode root = mapper.readTree(resp);
                if (root.has("code") && root.path("code").asText(null) != null) {
                    throw new RuntimeException("查询视频任务失败: " + root.path("message").asText(root.path("code").asText()));
                }
                String status = root.path("output").path("task_status").asText();
                log.debug("万相视频任务状态: taskId={}, status={}", taskId, status);
                if ("SUCCEEDED".equalsIgnoreCase(status)) {
                    String url = root.path("output").path("video_url").asText();
                    if (url.isEmpty()) {
                        throw new RuntimeException("视频任务完成但未返回 URL: " + resp);
                    }
                    return url;
                }
                if ("FAILED".equalsIgnoreCase(status)) {
                    String msg = root.path("output").path("message").asText("视频生成失败");
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

    /** "5s" -> 5 (秒) */
    private int parseDuration(String duration) {
        String num = duration.replaceAll("[^0-9]", "");
        return num.isEmpty() ? 5 : Integer.parseInt(num);
    }

    /** 演示模式兜底: 返回一个示例视频占位 */
    private GenerationDTO demoResult(VideoRequest request) {
        GenerationDTO result = new GenerationDTO();
        result.setType("video");
        result.setPrompt(request.getPrompt());
        result.setModel(model);
        result.setResultUrl("https://www.w3schools.com/html/mov_bbb.mp4");
        result.setThumbnailUrl(result.getResultUrl());
        result.setStatus("completed");
        result.setCreatedAt(LocalDateTime.now());
        return result;
    }
}
