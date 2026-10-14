package com.dcdr.agent.tool;

import com.dcdr.agent.agent.image.ChatImageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文生图工具: 供 Agent 工具框架(creative / 对话自动意图)复用,
 * 实际能力委托给 {@link ChatImageService}(与对话内文生图共用同一生图模型)。
 */
@Slf4j
@Component
public class ImageGenerationTool extends AbstractTool {

    private final ChatImageService chatImageService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ImageGenerationTool(ChatImageService chatImageService) {
        this.chatImageService = chatImageService;
    }

    /**
     * 工具名。
     * <p>名称常量而不是在 {@link #getName()} 里写字面量: 编排层要按它识别"这一轮出过图"
     * (决定是否走图片 URL 回填与多模态追问)。改名只改一处的话, 图照出, 但下游识别不到 —— 无任何报错。
     */
    public static final String NAME = "image_generation";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "根据文本提示词生成图片。当用户明确要求画图、生成图片、创作插画、设计图、出图等文生图意图时使用。"
                + "注意：仅生成用户本轮新请求的图片; 历史对话中已生成过的图片不要重复生成。";
    }

    @Override
    public boolean supportsStreaming() {
        return true;   // 允许在流式对话中被模型调用(自动文生图)
    }

    @Override
    public String getParameterSchema() {
        return chatImageService.getParameterSchemaJson();
    }

    @Override
    public String execute(Map<String, Object> params) {
        String prompt = getString(params, "prompt");
        String size = getString(params, "size");
        String style = getString(params, "style");
        try {
            List<String> urls = chatImageService.generate(prompt, size, style);
            return mapper.writeValueAsString(Map.of(
                    "prompt", prompt != null ? prompt : "",
                    "urls", urls != null ? urls : new ArrayList<>()));
        } catch (Exception e) {
            log.error("文生图工具执行失败", e);
            return "文生图失败: " + e.getMessage();
        }
    }
}
