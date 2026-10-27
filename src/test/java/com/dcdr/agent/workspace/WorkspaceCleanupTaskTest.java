package com.dcdr.agent.workspace;

import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回收区清理的保留策略测试(V3.3 F-49 §6.4.5)。
 * <p>
 * 这块最容易出"两种都错"的极端:
 * <ul>
 *   <li>引用判定太宽 → 每条记录的 beforeSha 都在日志里, blob 永远不过期, 保留天数形同虚设;</li>
 *   <li>引用判定太窄 → 删掉还能用来撤回的快照, 表现为"撤回成功但文件内容没了"。</li>
 * </ul>
 * 所以默认必须是"什么都不删", 只有显式打开 {@code release-reverted-snapshots} 才收缩。
 */
class WorkspaceCleanupTaskTest {

    @TempDir
    Path tempDir;

    private WorkspaceProperties props;
    private WorkspaceEntity workspace;
    private WorkspaceJournal journal;
    private WorkspaceTrashService trash;
    private WorkspaceCleanupTask task;

    @BeforeEach
    void setUp() {
        props = new WorkspaceProperties();
        props.setDataDir(tempDir.resolve("data").toString());
        props.setTrashLocation("app-data");

        workspace = new WorkspaceEntity();
        workspace.setId("ws_1");
        workspace.setRootPath(tempDir.resolve("ws").toString());

        journal = new WorkspaceJournal(props);
        trash = new WorkspaceTrashService(props);

        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        when(repository.findAll()).thenReturn(List.of(workspace));
        task = new WorkspaceCleanupTask(props, repository, journal);
    }

    @Test
    @DisplayName("默认开关: 已撤回的操作占用的快照也不删")
    void keepsRevertedSnapshotByDefault() throws IOException {
        String sha = shaOf("A");
        Path blob = expiredBlob(sha);
        String op = journal.begin(workspace, "sess_1", "MODIFY", "a.txt", true, sha, null, 1);
        journal.markReverted(workspace, "sess_1", List.of(op));

        task.cleanup();

        assertTrue(Files.exists(blob), "默认保守策略下不应删除任何被日志引用的快照");
    }

    @Test
    @DisplayName("打开开关: 已撤回的操作不再保护其快照, 过期后删掉")
    void releasesRevertedSnapshotWhenEnabled() throws IOException {
        props.setReleaseRevertedSnapshots(true);
        String sha = shaOf("B");
        Path blob = expiredBlob(sha);
        String op = journal.begin(workspace, "sess_1", "MODIFY", "a.txt", true, sha, null, 1);
        journal.markReverted(workspace, "sess_1", List.of(op));

        task.cleanup();

        assertFalse(Files.exists(blob), "已撤回且过期的快照应被回收");
    }

    @Test
    @DisplayName("打开开关: 未撤回的操作其快照绝不能被删")
    void keepsSnapshotOfLiveOperation() throws IOException {
        props.setReleaseRevertedSnapshots(true);
        String sha = shaOf("C");
        Path blob = expiredBlob(sha);
        String op = journal.begin(workspace, "sess_1", "MODIFY", "a.txt", true, sha, null, 1);
        journal.commit(workspace, "sess_1", op, "done");

        task.cleanup();

        assertTrue(Files.exists(blob), "还能撤回的操作, 其 before 快照必须保留");
    }

    @Test
    @DisplayName("未过保留期的一律不动, 即使已撤回")
    void keepsFreshBlob() throws IOException {
        props.setReleaseRevertedSnapshots(true);
        String sha = shaOf("D");
        Path blob = trash.blobPath(workspace, sha);
        Files.createDirectories(blob.getParent());
        Files.writeString(blob, "fresh");
        String op = journal.begin(workspace, "sess_1", "MODIFY", "a.txt", true, sha, null, 1);
        journal.markReverted(workspace, "sess_1", List.of(op));

        task.cleanup();

        assertTrue(Files.exists(blob));
    }

    /** 造一个 40 天前的 blob(默认保留 30 天) */
    private Path expiredBlob(String sha) throws IOException {
        Path blob = trash.blobPath(workspace, sha);
        Files.createDirectories(blob.getParent());
        Files.writeString(blob, "content");
        Files.setLastModifiedTime(blob, FileTime.from(Instant.now().minus(40, ChronoUnit.DAYS)));
        return blob;
    }

    private static String shaOf(String text) {
        return WorkspaceTrashService.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
