package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.SessionEntity;
import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 上下文解析：谁有资格用这个工作区。
 * <p>
 * 这里锁的是一个真实踩过的边角：admin 打开<b>别人</b>的会话去做文件操作时，
 * 如果按"当前请求者"校验工作区归属，会被判 404 —— 但 admin 恰恰是被允许代操作的那类用户。
 * 正确的问题是"这个会话能不能用这个工作区"，所以归属要按会话主人判。
 */
class WorkspaceContextResolverTest {

    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final WorkspaceContextResolver resolver =
            new WorkspaceContextResolver(sessionRepository, workspaceService);

    private SessionEntity session;

    @BeforeEach
    void setUp() {
        session = new SessionEntity();
        session.setId("s1");
        session.setUserId("u_owner");
        session.setWorkspaceId("ws_1");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
    }

    @Test
    @DisplayName("admin 操作别人的会话: 按会话主人校验工作区, 不按请求者")
    void adminUsesSessionOwner() {
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setId("ws_1");
        ws.setUserId("u_owner");
        when(workspaceService.requireOwned("ws_1", "u_owner", false)).thenReturn(ws);

        WorkspaceEntity got = WorkspaceContextHolder.callWith("u_admin", "s1", resolver::current);

        assertSame(ws, got);
        verify(workspaceService).requireOwned("ws_1", "u_owner", false);
        // 不能拿 admin 自己的 id 去比, 那样必然 404
        verify(workspaceService, never()).requireOwned(eq("ws_1"), eq("u_admin"), anyBoolean());
    }

    @Test
    @DisplayName("会话里没有 userId(存量数据)时退回请求者本人")
    void fallsBackToRequestUserWhenSessionHasNoOwner() {
        session.setUserId(null);
        WorkspaceEntity ws = new WorkspaceEntity();
        when(workspaceService.requireOwned("ws_1", "u_admin", false)).thenReturn(ws);

        WorkspaceEntity got = WorkspaceContextHolder.callWith("u_admin", "s1", resolver::current);

        assertSame(ws, got);
    }

    @Test
    @DisplayName("会话没绑工作区: 给出可操作的引导语, 不是干巴巴的 400")
    void unboundSessionGivesGuidance() {
        session.setWorkspaceId(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> WorkspaceContextHolder.callWith("u_owner", "s1", resolver::current));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("选择要操作的目录"));
    }

    @Test
    @DisplayName("上下文里没有会话 ID 时同样 fail-closed")
    void noSessionInContext() {
        BusinessException e = assertThrows(BusinessException.class, resolver::current);
        assertEquals(400, e.getCode());
    }
}
