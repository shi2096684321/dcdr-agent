package com.dcdr.agent.workspace;

import com.dcdr.agent.entity.WorkspaceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作区摘要注入测试(V3.3 F-49)。
 * <p>
 * 这段文本每轮常驻, 写错的两个方向都有代价: 少写了根路径(模型只能靠工具探路, 白烧一轮),
 * 或多写了未授权工具(模型反复调用一个没注册的工具, 报错重试)。
 */
class WorkspacePromptProviderTest {

    private final WorkspaceContextResolver resolver = mock(WorkspaceContextResolver.class);
    private final WorkspacePromptProvider provider = new WorkspacePromptProvider(resolver);

    @Test
    @DisplayName("未绑定工作区时不注入(绝大多数会话属于这种情况)")
    void returnsNullWhenNotBound() {
        when(resolver.resolveForSession("s1")).thenReturn(null);
        assertNull(provider.forSession("s1"));
        assertNull(provider.forSession(null));
        assertNull(provider.forSession("  "));
    }

    @Test
    @DisplayName("只读档: 摘要里不能出现 write / edit / delete")
    void readLevelHidesWriteTools() {
        when(resolver.resolveForSession("s1")).thenReturn(workspace(WorkspaceAccess.READ));
        String text = provider.forSession("s1").getText();

        assertTrue(text.contains("只读"));
        assertTrue(text.contains("workspace_read"));
        assertFalse(text.contains("workspace_write"), "只读档提到写工具会让模型反复尝试未注册的工具");
        assertFalse(text.contains("workspace_delete"));
    }

    @Test
    @DisplayName("可写档: 有 write/edit, 仍不能出现 delete")
    void writeLevelHidesDelete() {
        when(resolver.resolveForSession("s1")).thenReturn(workspace(WorkspaceAccess.WRITE));
        String text = provider.forSession("s1").getText();

        assertTrue(text.contains("可写"));
        assertTrue(text.contains("workspace_write"));
        assertFalse(text.contains("workspace_delete"));
    }

    @Test
    @DisplayName("全权档: 三个能力工具齐全, 且带根目录与防注入声明")
    void fullLevelListsEverything() {
        when(resolver.resolveForSession("s1")).thenReturn(workspace(WorkspaceAccess.FULL));
        String text = provider.forSession("s1").getText();

        assertTrue(text.contains("可写可删"));
        assertTrue(text.contains("workspace_write"));
        assertTrue(text.contains("workspace_delete"));
        assertTrue(text.contains("D:/work/demo"), "必须写出根目录, 否则模型仍要靠 workspace_list 探路");
        // 文件内容是不可信输入这条声明不能丢: 读了 README 就照着执行是这类功能最容易犯的错
        assertTrue(text.contains("不可信数据"));
        assertEquals(WorkspacePromptProvider.FRAGMENT_NAME, provider.forSession("s1").getName());
    }

    @Test
    @DisplayName("档位查询: 未绑定返回 null, 绑定后按字段, 脏数据按最小权限")
    void accessForSessionDistinguishesUnboundFromRead() {
        when(resolver.resolveForSession("none")).thenReturn(null);
        // 未绑定必须是 null 而不是 READ: 前者表示"这个会话没有工作区"(不裁剪工具),
        // 后者表示"有工作区但只读"(裁剪掉写/删工具), 交给注入层是两套不同的决策
        assertNull(provider.accessForSession("none"));
        assertNull(provider.accessForSession(null));

        when(resolver.resolveForSession("s1")).thenReturn(workspace(WorkspaceAccess.WRITE));
        assertEquals(WorkspaceAccess.WRITE, provider.accessForSession("s1"));

        when(resolver.resolveForSession("s2")).thenReturn(workspace(null));
        assertEquals(WorkspaceAccess.READ, provider.accessForSession("s2"),
                "档位字段为空时按最小权限, 不能因为脏数据放出写权限");
    }

    private static WorkspaceEntity workspace(WorkspaceAccess access) {
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setId("ws_1");
        ws.setName("demo");
        ws.setRootPath("D:/work/demo");
        ws.setAccessLevel(access);
        return ws;
    }
}
