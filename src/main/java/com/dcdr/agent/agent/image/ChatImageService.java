package com.dcdr.agent.agent.image;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.common.util.GeneratedImageStorage;
import com.dcdr.agent.dto.ImageRequest;
import com.dcdr.agent.dto.ImageResponse;
import com.dcdr.agent.model.provider.ImageModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话内文生图服务: 复用创作模式已配置的生图模型, 生成后下载落盘到本地,
 * 返回长期可访问的内网 URL。供「按钮触发」与「AI 自动意图(function calling)」两条路径共用。
 */
@Slf4j
@Service
public class ChatImageService {

    private final ModelRegistry registry;
    private final GeneratedImageStorage storage;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatImageService(ModelRegistry registry, GeneratedImageStorage storage) {
        this.registry = registry;
        this.storage = storage;
    }

    /** 生成图片, 返回本地可长期访问的 URL 列表 */
    public List<String> generate(String prompt, String size, String style) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(400, "文生图提示词不能为空", "EMPTY_PROMPT");
        }
        ImageModelProvider provider = registry.getDefaultImageProvider();
        if (provider == null) {
            throw new BusinessException(500, "未配置图片生成模型, 无法使用文生图", "NO_IMAGE_MODEL");
        }
        ImageRequest ir = new ImageRequest();
        ir.setModel(provider.getName());
        ir.setPrompt(prompt);
        if (size != null && !size.isBlank()) {
            ir.setSize(size);
        }
        if (style != null && !style.isBlank()) {
            ir.setStyle(style);
        }
        ImageResponse resp = provider.generate(ir);
        List<String> urls = new ArrayList<>();
        if (resp.getImageUrls() != null) {
            for (String u : resp.getImageUrls()) {
                urls.add(storage.store(u));
            }
        }
        if (urls.isEmpty() && resp.getImageUrl() != null) {
            urls.add(storage.store(resp.getImageUrl()));
        }
        if (urls.isEmpty()) {
            throw new BusinessException(500, "图片生成失败: 模型未返回图片", "NO_IMAGE_RETURNED");
        }
        return urls;
    }

    /** 返回可直接注入 ChatRequest.tools 的 OpenAI function calling 工具定义(Map) */
    public Map<String, Object> getToolDefinition() {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "function");
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", "image_generation");
        fn.put("description", "根据文本提示词生成图片。当用户明确要求画图、生成图片、创作插画、设计图、出图等文生图意图时使用。"
                + "注意：仅生成用户本轮新请求的图片; 历史对话中已生成过的图片不要重复生成。");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("type", "string");
        prompt.put("description", "图片内容描述(提示词), 例如「一只可爱的橘猫在草地上晒太阳」");
        Map<String, Object> size = new LinkedHashMap<>();
        size.put("type", "string");
        size.put("description", "图片尺寸, 可选 1024x1024 / 1024x1792 / 1792x1024, 默认 1024x1024");
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("type", "string");
        style.put("description", "风格: vivid(生动)/natural(自然)/anime(动漫)/oil(油画)/watercolor(水彩), 默认 vivid");
        props.put("prompt", prompt);
        props.put("size", size);
        props.put("style", style);
        params.put("properties", props);
        params.put("required", Collections.singletonList("prompt"));
        fn.put("parameters", params);
        def.put("function", fn);
        return def;
    }

    /** 返回工具参数 JSON Schema 字符串(供 Tool 接口实现复用) */
    public String getParameterSchemaJson() {
        try {
            Map<String, Object> def = getToolDefinition();
            Object params = ((Map<?, ?>) def.get("function")).get("parameters");
            return mapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
