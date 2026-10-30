package com.dcdr.agent.tool;

import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.workspace.WorkspaceAccess;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具注入期的授权档位过滤测试(V3.3 F-49)。
 * <p>
 * 守的是"模型看不到就不该有的选择": READ 档下写/删工具不能出现在注入列表里。
 * 两个出口(框架原生 spec / OpenAI 兼容 Map)必须给出一致的集合 —— 换个 Provider 就换权限
 * 是这类实现最隐蔽的 bug, 所以两边都断言。
 */
class ToolRegistryWorkspaceAccessTest {

    private static Tool stub(String name) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public String execute(Map<String, Object> params) {
                return "ok";
            }

            @Override
            public boolean supportsStreaming() {
                return true;
            }
        };
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry(new ToolsProperties());
        registry.register(stub("workspace_read"));
        registry.register(stub("workspace_write"));
        registry.register(stub("workspace_delete"));
        registry.register(stub("web_search"));
        return registry;
    }

    private static Set<String> specNames(List<ToolSpecification> specs) {
        return specs.stream().map(ToolSpecification::name).collect(Collectors.toSet());
    }

    private static Set<String> defNames(List<Map<String, Object>> defs) {
        return defs.stream()
                .map(d -> (Map<?, ?>) d.get("function"))
                .map(f -> f.get("name").toString())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("只读档: 写/删工具不注入, 读工具与非工作区工具照常")
    void readLevelHidesWriteAndDelete() {
        ToolRegistry registry = registry();

        Set<String> specs = specNames(registry.getStreamingToolSpecificationsFiltered(null, WorkspaceAccess.READ));
        assertEquals(Set.of("workspace_read", "web_search"), specs);

        Set<String> defs = defNames(registry.getStreamingToolDefinitionsFiltered(null, WorkspaceAccess.READ));
        assertEquals(specs, defs, "两个出口必须给出一致的工具集合");
    }

    @Test
    @DisplayName("可写档: 有 write, 仍无 delete")
    void writeLevelHidesDelete() {
        ToolRegistry registry = registry();

        assertEquals(Set.of("workspace_read", "workspace_write", "web_search"),
                specNames(registry.getStreamingToolSpecificationsFiltered(null, WorkspaceAccess.WRITE)));
        assertEquals(Set.of("workspace_read", "workspace_write", "web_search"),
                defNames(registry.getStreamingToolDefinitionsFiltered(null, WorkspaceAccess.WRITE)));
    }

    @Test
    @DisplayName("全权档与不传档位: 全部工具都在(不传档位不等于没权限)")
    void fullAndNullKeepEverything() {
        ToolRegistry registry = registry();
        Set<String> all = Set.of("workspace_read", "workspace_write", "workspace_delete", "web_search");

        assertEquals(all, specNames(registry.getStreamingToolSpecificationsFiltered(null, WorkspaceAccess.FULL)));
        assertEquals(all, specNames(registry.getStreamingToolSpecificationsFiltered(null, (WorkspaceAccess) null)));
        assertEquals(all, specNames(registry.getStreamingToolSpecificationsFiltered(null)),
                "老签名(不带档位)必须保持全放行, 否则启动期自检与 Agent 校验会误判");
        assertEquals(all, defNames(registry.getStreamingToolDefinitionsFiltered(null, WorkspaceAccess.FULL)));
    }

    @Test
    @DisplayName("档位过滤叠加在 Agent 白名单之上, 不会把白名单外的工具放回来")
    void accessFilterDoesNotBypassWhitelist() {
        ToolRegistry registry = registry();

        Set<String> specs = specNames(registry.getStreamingToolSpecificationsFiltered(
                List.of("workspace_delete", "web_search"), WorkspaceAccess.READ));
        assertEquals(Set.of("web_search"), specs,
                "白名单里有 workspace_delete, 但 READ 档不允许 —— 两个条件是与的关系");
    }
}
