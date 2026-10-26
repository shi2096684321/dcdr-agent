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
 * 删除工作区内的文件或目录(V3.3 F-49)。
 * <p>
 * 需要 {@code FULL} 档。实现上是<b>移入回收区</b>而非真删, 目录必须显式 {@code recursive=true} ——
 * 一句"清理一下"就把 src 删光的场景, 靠这条拦。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.workspace.enabled", havingValue = "true")
public class WorkspaceDeleteTool extends AbstractWorkspaceTool {

    public static final String NAME = "workspace_delete";

    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceFileService fileService;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "删除工作区内的文件或目录。文件会被移入回收区而不是真正删除, 可在界面上撤销。"
                + "删除目录必须显式传 recursive=true, 且单次最多删除 100 个文件。"
                + "只有在用户明确要求删除时才使用本工具。" + PATH_NOTE;
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("path", JsonStringSchema.builder()
                        .description("要删除的文件或目录的相对路径").build())
                .addProperty("recursive", JsonBooleanSchema.builder()
                        .description("目标是目录时必须传 true 才会递归删除, 默认 false").build())
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
            String sessionId = contextResolver.currentSessionId();
            boolean recursive = Boolean.TRUE.equals(params.get("recursive"));
            return fileService.delete(ws, sessionId, getString(params, "path"), recursive);
        });
    }
}
