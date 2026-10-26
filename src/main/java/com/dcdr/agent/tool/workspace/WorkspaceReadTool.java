package com.dcdr.agent.tool.workspace;

import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.workspace.WorkspaceContextResolver;
import com.dcdr.agent.workspace.WorkspaceFileService;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按行读取工作区内的文本文件(V3.3 F-49)。
 * <p>
 * 分页是强制的: 默认 200 行、单次回灌字符上限 8000。不让模型一口气读完整文件 ——
 * 一个几万行的日志能直接把上下文撑爆, 而它其实只需要看头部特征和尾部结论。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.workspace.enabled", havingValue = "true")
public class WorkspaceReadTool extends AbstractWorkspaceTool {

    public static final String NAME = "workspace_read";

    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceFileService fileService;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "读取工作区内的文本文件, 按行分页返回(带行号)。"
                + "内容超长时结果末尾会提示剩余行数, 用 offset 继续读。"
                + "二进制文件与大文件会被拒绝, 请改用 workspace_search 定位。" + PATH_NOTE;
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("path", JsonStringSchema.builder()
                        .description("要读取的文件相对路径").build())
                .addProperty("offset", JsonIntegerSchema.builder()
                        .description("起始行号, 从 1 开始, 默认 1").build())
                .addProperty("limit", JsonIntegerSchema.builder()
                        .description("本次读取行数, 默认 200, 上限 1000").build())
                .required(List.of("path"))
                .build();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> params) {
        return run(() -> {
            WorkspaceEntity ws = contextResolver.current();
            String path = getString(params, "path");
            Integer offset = params.get("offset") == null ? null : (int) getDouble(params, "offset");
            Integer limit = params.get("limit") == null ? null : (int) getDouble(params, "limit");
            return fileService.read(ws, path, offset, limit);
        });
    }
}
