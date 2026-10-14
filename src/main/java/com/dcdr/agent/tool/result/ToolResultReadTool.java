package com.dcdr.agent.tool.result;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.tool.AbstractTool;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * V3.1 F-47.5 工具结果分页读取工具。
 * <p>
 * 与 {@link ToolResultCompressor} 的落盘能力配对: 当某个工具结果被压缩并提示"已落盘"时,
 * 模型用本工具按行分页取回全文 —— 这就是"体积治理"与"信息不丢"的平衡点:
 * 硬截断只能丢弃, 落盘 + 按需读取则把信息留在可及范围内。
 * <p>
 * <b>安全边界</b>(在 {@link ToolResultSpillStore} 内实现): 只能读取落盘根目录下
 * <b>当前会话</b>的目录, 既防 {@code ../} 路径穿越, 也防跨会话读取他人工具结果。
 * <p>
 * {@code supportsStreaming()=true}: 落盘恰恰发生在流式 agentic 工具循环里,
 * 若本工具不暴露给流式对话, 模型就没有手段取回完整内容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResultReadTool extends AbstractTool {

    private static final int DEFAULT_OFFSET = 1;

    private final ToolResultSpillStore spillStore;
    private final AgentProperties properties;

    /**
     * 工具名。
     * <p>名称常量而不是在 {@link #getName()} 里写字面量: {@code ToolRegistry} 用它把本工具
     * 排除在 Agent 白名单之外 —— 白名单里只要没列它, "超长结果落盘 + 分页读取"这条链路就是断的。
     * 散着写的话, 改名不会有任何编译错误, 只会让这条链路静默失效。
     */
    public static final String NAME = "tool_result_read";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "分页读取被压缩的工具结果全文。当某条工具结果出现「工具结果已落盘」提示时, "
                + "用本工具按行分段读取完整内容; 参数 path 取自该提示, offset 为起始行号(从 1 开始), "
                + "limit 为本次读取行数。内容不长时应直接使用已回灌的内容, 无需调用本工具。";
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("path", JsonStringSchema.builder()
                        .description("落盘提示中给出的相对路径, 例如 sess_xxx/web_search-101530123.txt")
                        .build())
                .addProperty("offset", JsonIntegerSchema.builder()
                        .description("起始行号, 从 1 开始(默认 1)")
                        .build())
                .addProperty("limit", JsonIntegerSchema.builder()
                        .description("本次读取行数(默认 200, 上限 1000)")
                        .build())
                .required(List.of("path"))
                .build();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> params) {
        String path = getString(params, "path");
        if (path == null || path.isBlank()) {
            return "请提供 path 参数(取自「工具结果已落盘」提示中的路径)";
        }
        ToolResultSpillStore.ReadResult result = spillStore.read(path, intParam(params, "offset"), intParam(params, "limit"));
        if (result.error() != null) {
            return "读取失败: " + result.error();
        }
        return result.text();
    }

    private int intParam(Map<String, Object> params, String key) {
        if ("offset".equals(key)) {
            int v = (int) getDouble(params, key);
            return v > 0 ? v : DEFAULT_OFFSET;
        }
        int v = (int) getDouble(params, key);
        return v > 0 ? v : properties.getTools().getResult().getSpill().getPageSize();
    }
}
