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

    @Override
    public String getName() {
        return "image_generation";
    }

    @Override
    public String getDescription() {
        return "根据文本提示词生成图片。当用户明确要求画图、生成图片、创作插画、设计图、出图等文生图意图时使用。";
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
