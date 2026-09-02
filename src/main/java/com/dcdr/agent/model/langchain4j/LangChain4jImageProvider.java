package com.dcdr.agent.model.langchain4j;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.dto.ImageRequest;
import com.dcdr.agent.dto.ImageResponse;
import com.dcdr.agent.model.provider.ImageModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiImageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 LangChain4j 的图片生成 Provider (V3.1 框架化改造)。
 * <p>
 * 实现 {@link ImageModelProvider} 业务接口, 内核替换为框架的
 * {@link OpenAiImageModel}: 请求构造(/images/generations)、鉴权头、
 * 响应解析(data[].url / b64_json)全部由框架完成, 替代手写版
 * {@code QwenImageProvider}/{@code ZhipuImageProvider}/{@code OpenAiImageProvider}
 * 的 WebClient + Jackson 实现。
 * <p>
 * 适配 OpenAI 兼容生图端点:
 * <ul>
 *   <li>智谱: {@code https://open.bigmodel.cn/api/paas/v4} (cogview 系列)</li>
 *   <li>OpenAI: {@code https://api.openai.com/v1} (dall-e 系列)</li>
 * </ul>
 * 注意: DashScope compatible-mode 无 OpenAI 生图兼容端点(实测 404),
 * qwen 生图请用手写版 {@code QwenImageProvider}(engine=manual, DashScope 原生接口,
 * 双地址见配置项 base-url / manual-base-url)。
 * base-url 约定(两引擎一致): 版本段(/v1 等)由 base-url 携带, 框架在其后追加 /images/generations。
 * <p>
 * 对外契约(与手写版一致, ImageGenerationWorker 等编排层零改动):
 * <ul>
 *   <li>超时 → 抛出"图片生成超时"异常</li>
 *   <li>平台明确拒绝(HTTP 4xx/5xx 业务错误) → 抛出让前端提示失败</li>
 *   <li>其他异常(网络等) → 同样抛出, 不做演示兜底</li>
 * </ul>
 * <p>
 * 已知差异(可接受):
 * <ul>
 *   <li>框架请求体仅支持 OpenAI 标准字段(model/prompt/n/size/quality),
 *       手写版透传的 DashScope 私有参数(negative_prompt/prompt_extend/watermark/seed)
 *       与 OpenAI style 参数不再下发</li>
 *   <li>maxRetries=1 显式关闭框架自动重试: 生图是分钟级长耗时+计费请求,
 *       超时/限流自动重试会成倍放大等待与成本(手写版本就不重试)</li>
 *   <li>b64_json 响应转 data URI 返回, 前端 img 标签可直接展示</li>
 * </ul>
 */
@Slf4j
public class LangChain4jImageProvider implements ImageModelProvider {

    /** 单次生成请求的最大等待时长, 对齐手写版 QwenImageProvider 的 500s(同步长耗时接口) */
    private static final Duration TIMEOUT = Duration.ofSeconds(500);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentProperties.ModelConfig config;
    private final String name;
    private final String model;
    /** 按 size 缓存框架模型实例: 框架的 size 是构建期参数, 不同尺寸需各自实例(尺寸组合有限) */
    private final Map<String, ImageModel> modelsBySize = new ConcurrentHashMap<>();

    public LangChain4jImageProvider(AgentProperties.ModelConfig config) {
        this.config = config;
        this.name = config.getName();
        this.model = config.getModel();
    }

    @Override public String getName() { return name; }
    @Override public ModelType getType() { return ModelType.IMAGE; }
    @Override public String getLabel() { return model; }
    @Override public String getProvider() { return config.getProvider(); }

    @Override
    public ImageResponse generate(ImageRequest request) {
        try {
            String size = request.getSize() != null && !request.getSize().isBlank()
                    ? request.getSize()
                    : (config.getSize() != null ? config.getSize() : "1024x1024");
            int n = request.getN() != null ? request.getN() : 1;

            ImageModel imageModel = modelsBySize.computeIfAbsent(size, this::buildModel);
            Response<List<Image>> lcResponse = imageModel.generate(request.getPrompt(), n);

            List<String> urls = new ArrayList<>();
            for (Image img : lcResponse.content()) {
                // OpenAI images API 默认返回 URL; b64_json 响应转 data URI 兜底
                if (img.url() != null) {
                    urls.add(img.url().toString());
                } else if (img.base64Data() != null) {
                    urls.add("data:" + (img.mimeType() != null ? img.mimeType() : "image/png")
                            + ";base64," + img.base64Data());
                }
            }
            if (urls.isEmpty()) {
                throw new IllegalStateException("模型未返回图片");
            }

            ImageResponse response = new ImageResponse();
            response.setId("gen_" + System.currentTimeMillis());
            response.setImageUrl(urls.get(0));
            response.setImageUrls(urls);
            response.setThumbnailUrl(urls.get(0));
            response.setPrompt(request.getPrompt());
            response.setModel(model);
            return response;
        } catch (Exception e) {
            if (isTimeout(e)) {
                log.error("{} 图片生成超时({}s), 判定失败", name, TIMEOUT.getSeconds(), e);
                throw new RuntimeException("图片生成超时, 请稍后重试", e);
            }
            HttpException http = findHttpException(e);
            if (http != null) {
                // 平台明确拒绝的业务错误(鉴权/参数/IP侵权等): 直接抛出让前端提示失败, 不返回占位假图。
                // 框架可能把 HttpException 包装成 InvalidRequestException 等子类(cause 链), 故沿链查找
                String detail = firstNonBlank(http.getMessage(), e.toString());
                String bizMsg = parseBusinessMessage(detail);
                if (bizMsg == null || bizMsg.isBlank() || "未知错误".equals(bizMsg)) {
                    // 响应体为空(如网关 404/502 空响应): 至少带上状态码
                    bizMsg = "HTTP " + http.statusCode() + " (服务端未返回错误详情)";
                }
                log.error("{} 图片生成被平台拒绝: HTTP {} - {}", name, http.statusCode(), bizMsg, e);
                throw new RuntimeException("图片生成失败: " + bizMsg, e);
            }
            log.error("{} 图片生成失败", name, e);
            throw new RuntimeException("图片生成失败: " + e.getMessage(), e);
        }
    }

    /** 沿异常链查找 HttpException(框架 DefaultExceptionMapper 可能将其包装为子类) */
    private HttpException findHttpException(Throwable e) {
        while (e != null) {
            if (e instanceof HttpException h) {
                return h;
            }
            e = e.getCause();
        }
        return null;
    }

    /** 取第一个非空白字符串 */
    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /** 构建框架模型: baseUrl + /images/generations, 关闭自动重试 */
    private ImageModel buildModel(String size) {
        return OpenAiImageModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .size(size)
                .timeout(TIMEOUT)
                .maxRetries(1)
                .build();
    }

    /** 从异常链中识别超时(框架 TimeoutException / JDK HttpTimeoutException / Reactor TimeoutException) */
    private boolean isTimeout(Throwable e) {
        while (e != null) {
            if (e instanceof dev.langchain4j.exception.TimeoutException
                    || e instanceof java.util.concurrent.TimeoutException
                    || e instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    /**
     * 解析平台返回的结构化错误(DashScope/智谱形如 {"code":"...","message":"..."}),
     * 取 message 字段给前端; 非 JSON 时原样返回异常消息。
     */
    private String parseBusinessMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未知错误";
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root.has("message")) {
                return root.path("message").asText(root.path("code").asText(raw));
            }
            if (root.has("error")) {
                return root.path("error").path("message").asText(raw);
            }
        } catch (Exception ignore) {
            // 非 JSON 响应体, 原样返回
        }
        return raw;
    }
}
