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
 * 精确替换文件片段(V3.3 F-49)。
 * <p>
 * {@code old_string} 必须唯一, 否则报错要求补充上下文。这是"改代码"的正确姿势:
 * 改动范围可验证, 不会误伤文件其余部分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.workspace.enabled", havingValue = "true")
public class WorkspaceEditTool extends AbstractWorkspaceTool {

    public static final String NAME = "workspace_edit";

    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceFileService fileService;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "把文件中的 old_string 替换为 new_string。old_string 必须在文件中唯一出现, "
                + "否则会被拒绝(请带上更多上下文让它唯一)。改完会返回变更摘要, 并自动快照以便撤销。"
                + "新建文件请用 workspace_write。" + PATH_NOTE;
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("path", JsonStringSchema.builder()
                        .description("要修改的文件相对路径").build())
                .addProperty("old_string", JsonStringSchema.builder()
                        .description("被替换的原文, 需包含足够上下文以保证唯一(含缩进)").build())
                .addProperty("new_string", JsonStringSchema.builder()
                        .description("替换后的新文本; 传空串表示删除该片段").build())
                .addProperty("replace_all", JsonBooleanSchema.builder()
                        .description("old_string 出现多次时是否全部替换, 默认 false").build())
                .required(List.of("path", "old_string", "new_string"))
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
            boolean replaceAll = Boolean.TRUE.equals(params.get("replace_all"));
            return fileService.edit(ws, sessionId, getString(params, "path"),
                    getString(params, "old_string"), getString(params, "new_string"), replaceAll);
        });
    }
}
