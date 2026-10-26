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

import java.util.Map;

/**
 * 列出工作区内目录结构(V3.3 F-49)。
 * <p>
 * 只读工具, {@code READ} 档即可用。默认忽略 node_modules/.git/target 等目录,
 * 避免模型对着几万条构建产物浪费上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.workspace.enabled", havingValue = "true")
public class WorkspaceListTool extends AbstractWorkspaceTool {

    public static final String NAME = "workspace_list";

    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceFileService fileService;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "列出工作区内的目录与文件。用它先摸清结构, 再决定读哪个文件。" + PATH_NOTE;
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("path", JsonStringSchema.builder()
                        .description("相对路径, 不传表示工作区根目录").build())
                .addProperty("depth", JsonIntegerSchema.builder()
                        .description("向下展开的层数, 1-3, 默认 1").build())
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
            int depth = params.get("depth") == null ? 1 : (int) getDouble(params, "depth");
            return fileService.list(ws, path, depth);
        });
    }
}
