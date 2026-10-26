package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.WorkspaceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 变更日志测试 —— 撤回的正确性完全建立在这份日志上, 所以结构、顺序、序号连续性都要锁住。
 */
class WorkspaceJournalTest {

    @TempDir
    Path tempDir;

    private WorkspaceProperties props;
    private WorkspaceJournal journal;
    private WorkspaceEntity workspace;

    @BeforeEach
    void setUp() throws Exception {
        props = new WorkspaceProperties();
        props.setDataDir(tempDir.resolve("data").toString());
        journal = new WorkspaceJournal(props);

        Path root = tempDir.resolve("ws");
        Files.createDirectories(root);
        workspace = new WorkspaceEntity();
        workspace.setId("ws_test");
        workspace.setRootPath(root.toRealPath().toString());
        workspace.setAccessLevel(WorkspaceAccess.FULL);
    }

    @Test
    void begin与commit成对落盘() {
        String opId = journal.begin(workspace, "sess_a", "MODIFY", "a.txt", true, "sha1", "sha2", 10);
        journal.commit(workspace, "sess_a", opId, "done");

        List<WorkspaceJournal.Entry> entries = journal.read(workspace, "sess_a");
        assertEquals(1, entries.size(), "commit 记录不带 path, 不应算作可回滚条目");
        assertEquals("a.txt", entries.get(0).path());
        assertEquals("MODIFY", entries.get(0).type());
        assertTrue(entries.get(0).existed());
        assertEquals("sha1", entries.get(0).beforeSha());
    }

    @Test
    void 序号单调递增且重启后不回退() {
        journal.begin(workspace, "sess_b", "CREATE", "x.txt", false, null, "s", 1);
        journal.begin(workspace, "sess_b", "CREATE", "y.txt", false, null, "s", 1);

        long firstMax = journal.read(workspace, "sess_b").stream()
                .mapToLong(WorkspaceJournal.Entry::seq).max().orElse(0);
        assertEquals(2, firstMax);

        // 模拟进程重启: 新实例, 序号缓存是空的
        WorkspaceJournal restarted = new WorkspaceJournal(props);
        restarted.begin(workspace, "sess_b", "CREATE", "z.txt", false, null, "s", 1);

        long newMax = restarted.read(workspace, "sess_b").stream()
                .mapToLong(WorkspaceJournal.Entry::seq).max().orElse(0);
        assertTrue(newMax > firstMax, "重启后序号必须从文件里的最大值继续, 不能回到 1");
    }

    @Test
    void 多会话互不干扰() {
        journal.begin(workspace, "sess_c", "CREATE", "a.txt", false, null, "s", 1);
        journal.begin(workspace, "sess_d", "CREATE", "b.txt", false, null, "s", 1);

        assertEquals(1, journal.read(workspace, "sess_c").size());
        assertEquals(1, journal.read(workspace, "sess_d").size());
    }

    @Test
    void 非法会话ID被拒绝() {
        assertThrows(BusinessException.class,
                () -> journal.begin(workspace, "../../etc", "CREATE", "a.txt", false, null, "s", 1));
    }

    @Test
    void 无历史时返回空列表() {
        assertTrue(journal.read(workspace, "sess_empty").isEmpty());
        assertFalse(journal.hasHistory(workspace, "sess_empty"));
    }
}
