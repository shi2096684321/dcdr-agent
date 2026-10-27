package com.dcdr.agent.workspace;

import com.dcdr.agent.agent.memory.longterm.LongTermMemoryService;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.MessageEntity;
import com.dcdr.agent.entity.SessionEntity;
import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.repository.MessageRepository;
import com.dcdr.agent.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 撤回服务的边界行为测试(V3.3 F-49)。
 * <p>
 * 这里锁的是两类最容易出错、又不会自己报错的逻辑:
 * <ul>
 *   <li><b>L1 重复撤销</b>: 已撤销的操作被再点一次, 若不识别, 会把文件退回到更早的状态;</li>
 *   <li><b>冲突跳过</b>: 用户手改过文件后点撤销, 若照盖不误, 等于替他删掉了自己的改动。</li>
 * </ul>
 */
class SessionRewindServiceTest {

    @TempDir
    Path tempDir;

    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final WorkspacePathResolver pathResolver = mock(WorkspacePathResolver.class);
    private final WorkspaceJournal journal = mock(WorkspaceJournal.class);
    private final WorkspaceFileService fileService = mock(WorkspaceFileService.class);
    private final LongTermMemoryService memoryService = mock(LongTermMemoryService.class);

    private final SessionRewindService service = new SessionRewindService(
            sessionRepository, messageRepository, workspaceService,
            pathResolver, journal, fileService, memoryService);

    private WorkspaceEntity workspace;
    private Path target;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new WorkspaceEntity();
        workspace.setId("ws_1");
        workspace.setRootPath(tempDir.toString());

        target = tempDir.resolve("a.txt");
        Files.writeString(target, "hello", StandardCharsets.UTF_8);

        SessionEntity session = new SessionEntity();
        session.setId("s1");
        session.setUserId("u1");
        session.setWorkspaceId("ws_1");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(workspaceService.requireOwned(eq("ws_1"), any(), anyBoolean())).thenReturn(workspace);
        when(pathResolver.resolve(any(), eq("a.txt"), any())).thenReturn(target);
    }

    @Test
    @DisplayName("找不到 opId 时报 404, 而不是静默成功")
    void undoUnknownOpFails() {
        when(journal.read(any(), anyString())).thenReturn(List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.undoOperation("s1", "op_missing", "u1", false));
        assertEquals(404, e.getCode());
    }

    @Test
    @DisplayName("已撤销过的操作不再重复执行")
    void undoTwiceIsNoop() {
        when(journal.read(any(), anyString())).thenReturn(List.of(
                entry("op_1", "a.txt", true, "sha_before", sha(target), "reverted")));

        SessionRewindService.Report rep = service.undoOperation("s1", "op_1", "u1", false);

        assertTrue(rep.reverted().isEmpty());
        assertEquals(1, rep.skipped().size());
        assertTrue(rep.skipped().get(0).reason().contains("已撤销"));
        verify(fileService, never()).restore(any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("当初就失败的操作: 明说没改动过, 不去碰文件")
    void undoFailedOpSkips() {
        when(journal.read(any(), anyString())).thenReturn(List.of(
                entry("op_2", "a.txt", true, "sha_before", null, "failed")));

        SessionRewindService.Report rep = service.undoOperation("s1", "op_2", "u1", false);

        assertTrue(rep.reverted().isEmpty());
        assertTrue(rep.skipped().get(0).reason().contains("失败"));
        verify(fileService, never()).restore(any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("正常撤销: 按 before 快照恢复, 并把该 op 标记为已撤回")
    void undoRestoresSnapshot() {
        when(journal.read(any(), anyString())).thenReturn(List.of(
                entry("op_3", "a.txt", true, "sha_before", sha(target), "done")));

        SessionRewindService.Report rep = service.undoOperation("s1", "op_3", "u1", false);

        assertEquals(List.of("a.txt"), rep.reverted());
        verify(fileService).restore(workspace, "a.txt", true, "sha_before");
        verify(journal).markReverted(workspace, "s1", List.of("op_3"));
    }

    @Test
    @DisplayName("文件在撤销前被外部改过: 跳过并说明, 不覆盖用户的手改")
    void undoSkipsWhenFileChangedExternally() {
        when(journal.read(any(), anyString())).thenReturn(List.of(
                entry("op_4", "a.txt", true, "sha_before", "sha_of_agent_version", "done")));

        SessionRewindService.Report rep = service.undoOperation("s1", "op_4", "u1", false);

        assertTrue(rep.reverted().isEmpty());
        assertTrue(rep.skipped().get(0).reason().contains("外部修改"));
        verify(fileService, never()).restore(any(), anyString(), anyBoolean(), any());
        // 跳过的不能标 reverted, 否则用户处理掉手改之后也无法再撤一次
        verify(journal, never()).markReverted(any(), anyString(), anyList());
    }

    @Test
    @DisplayName("删除后的位置又出现同名新文件: 跳过, 不拿旧快照盖掉新内容")
    void undoDeleteSkipsWhenSameNameReappeared() {
        when(journal.read(any(), anyString())).thenReturn(List.of(
                // DELETE 记录的 afterSha 为空, 只能靠"目标位置是否存在"判断冲突
                entry("op_5", "a.txt", true, "sha_before", null, "done")));

        SessionRewindService.Report rep = service.undoOperation("s1", "op_5", "u1", false);

        assertTrue(rep.reverted().isEmpty());
        assertTrue(rep.skipped().get(0).reason().contains("同名文件"));
        verify(fileService, never()).restore(any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("撤回对话时连带归档该轮之后的记忆, 纯文件回滚不清记忆")
    void rewindAllArchivesMemories() {
        MessageEntity anchor = new MessageEntity();
        anchor.setId(10L);
        anchor.setSessionId("s1");
        anchor.setRole("user");
        anchor.setSeq(5);
        anchor.setCreatedAt(LocalDateTime.now());
        when(messageRepository.findById(10L)).thenReturn(Optional.of(anchor));
        when(messageRepository.countBySessionIdAndSeqGreaterThanEqual("s1", 5)).thenReturn(2L);
        when(journal.read(any(), anyString())).thenReturn(List.of());
        when(memoryService.archiveBySession("u1", "s1", 5)).thenReturn(3);

        SessionRewindService.Report rep = service.rewind("s1", "10", SessionRewindService.Scope.ALL);

        assertEquals(2, rep.messagesDeleted());
        assertEquals(3, rep.memoriesArchived());
        verify(memoryService).archiveBySession("u1", "s1", 5);

        // scope=FILES 只回滚文件, 对话与记忆都不该被动
        clearInvocations(memoryService);
        when(memoryService.archiveBySession(any(), anyString(), anyInt())).thenReturn(0);
        service.rewind("s1", "10", SessionRewindService.Scope.FILES);
        verify(memoryService, never()).archiveBySession(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("递归删除共用一个 opId: 部分路径回滚成功时不能整片标记已撤回")
    void groupOpPartiallyRevertedStaysRewindable() throws Exception {
        Path other = tempDir.resolve("b.txt");
        Files.writeString(other, "用户后来改过", StandardCharsets.UTF_8);
        when(pathResolver.resolve(any(), eq("b.txt"), any())).thenReturn(other);

        when(journal.read(any(), anyString())).thenReturn(List.of(
                entry("op_g", "a.txt", true, "sha_before_a", sha(target), "done"),
                // afterSha 与磁盘现状不符 → 冲突跳过; 但同一个 opId 的另一条已经回滚成功
                entry("op_g", "b.txt", true, "sha_before_b", "sha_of_agent_version", "done")));

        SessionRewindService.Report rep = service.undoOperation("s1", "op_g", "u1", false);

        assertEquals(List.of("a.txt"), rep.reverted());
        assertEquals(1, rep.skipped().size());
        // 整片标记 reverted 会让 b.txt 永久失去撤销入口
        verify(journal, never()).markReverted(any(), anyString(), anyList());
    }

    private static WorkspaceJournal.Entry entry(String opId, String path, boolean existed,
                                                String beforeSha, String afterSha, String status) {
        return new WorkspaceJournal.Entry(1L, "2026-10-27T09:00:00Z", opId, "s1",
                existed ? "MODIFY" : "CREATE", path, existed, beforeSha, afterSha, 5, status);
    }

    private static String sha(Path file) {
        try {
            return WorkspaceTrashService.sha256(file);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
