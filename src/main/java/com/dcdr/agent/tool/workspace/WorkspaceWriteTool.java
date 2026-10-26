package com.dcdr.agent.tool.workspace;

import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.workspace.WorkspaceContextResolver;
import com.dcdr.agent.workspace.WorkspaceFileService;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 新建或整体覆盖文件(V3.3 F-49)。
 * <p>
 * <b>覆盖必须显式声明</b> {@code overwrite=true}: 让模型"顺手整文件重写"是丢代码的头号原因,
 * 它记不住长文件全貌, 重写时会静默丢掉中间几段。改局部请用 {@code workspace_edit}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.workspace.enabled", havingValue = "true")
public class WorkspaceWriteTool extends AbstractWorkspaceTool {

    public static final String NAME = "workspace_write";

    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceFileService fileService;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "新建文件, 或整体覆盖一个已有文件(需显式 overwrite=true)。"
                + "只改几行时请用 workspace_edit, 不要用本工具重写整个文件。"
                + "写入会沿用原文件的编码与换行符, 并在修改前自动快照, 可撤销。" + PATH_NOTE;
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("path", JsonStringSchema.builder()
                        .description("目标文件相对路径, 父目录不存在会自动创建").build())
                .addProperty("content", JsonStringSchema.builder()
                        .description("完整文件内容").build())
                .addProperty("overwrite", JsonBooleanSchema.builder()
                        .description("目标已存在时必须传 true 才覆盖, 默认 false").build())
                .required(List.of("path", "content"))
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
            String sessionId = contextResolver.currentSessionId();
            boolean overwrite = Boolean.TRUE.equals(params.get("overwrite"));
            return fileService.write(ws, sessionId, getString(params, "path"),
                    getString(params, "content"), overwrite);
        });
    }
}
