package com.dcdr.agent.tool.workspace;

import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.workspace.WorkspaceContextResolver;
import com.dcdr.agent.workspace.WorkspaceFileService;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按内容搜索工作区(V3.3 F-49)。
 * <p>
 * 命中过多时只回前 30 处并提示缩小范围 —— 否则模型会拿到几百行匹配,
 * 既费 token 又什么都没看懂。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.workspace.enabled", havingValue = "true")
public class WorkspaceSearchTool extends AbstractWorkspaceTool {

    public static final String NAME = "workspace_search";

    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceFileService fileService;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "在工作区内按正则搜索文件内容, 返回 `文件:行号: 内容`。"
                + "适合回答\"哪里用了 X\"这类问题; 只想找文件名可用 glob 过滤(*.java)。" + PATH_NOTE;
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("pattern", JsonStringSchema.builder()
                        .description("正则表达式, 如 RedisTemplate|StringRedisTemplate").build())
                .addProperty("path", JsonStringSchema.builder()
                        .description("搜索起点目录的相对路径, 不传表示根目录").build())
                .addProperty("glob", JsonStringSchema.builder()
                        .description("文件名通配过滤, 如 *.java 或 *.md").build())
                .required(List.of("pattern"))
                .build();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean untrustedOutput() {
        // 命中的是文件里的片段(含上下文行), 与 workspace_read 同源
        return true;
    }

    @Override
    public String execute(Map<String, Object> params) {
        return run(() -> {
            WorkspaceEntity ws = contextResolver.current();
            return fileService.search(ws, getString(params, "pattern"),
                    getString(params, "path"), getString(params, "glob"));
        });
    }
}
