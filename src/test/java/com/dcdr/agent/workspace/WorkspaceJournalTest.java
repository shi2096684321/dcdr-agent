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

    @Test
    void commit后的状态回填到条目上() {
        String opId = journal.begin(workspace, "sess_s1", "MODIFY", "a.txt", true, "sha1", "sha2", 10);
        journal.commit(workspace, "sess_s1", opId, "done");

        // commit 写的是不带 path 的短记录, 若不回填, 调用方看到的永远是 begin
        assertEquals("done", journal.read(workspace, "sess_s1").get(0).status(),
                "状态必须按 opId 归并, 否则判断不出这条操作是否已成功/已撤销");
    }

    @Test
    void 撤回标记可被读回() {
        String opId = journal.begin(workspace, "sess_s2", "DELETE", "b.txt", true, "sha1", null, 0);
        journal.commit(workspace, "sess_s2", opId, "done");
        journal.markReverted(workspace, "sess_s2", List.of(opId));

        assertEquals("reverted", journal.read(workspace, "sess_s2").get(0).status());
    }

    @Test
    void 共享opId的多条记录状态一致() {
        // 递归删除整目录: 多个文件共用同一个 opId, 撤销时才是一次操作
        String op1 = journal.begin(workspace, "sess_s3", "DELETE", "dir/a.txt", true, "s1", null, 0);
        journal.begin(workspace, "sess_s3", "DELETE", "dir/b.txt", true, "s2", null, 0, op1);
        journal.commit(workspace, "sess_s3", op1, "done");

        List<WorkspaceJournal.Entry> entries = journal.read(workspace, "sess_s3");
        assertEquals(2, entries.size());
        assertEquals(op1, entries.get(0).opId());
        assertEquals(op1, entries.get(1).opId(), "第二条记录必须复用同一个 opId");
        assertTrue(entries.stream().allMatch(e -> "done".equals(e.status())));
    }

    @Test
    void 失败的操作状态可区分() {
        String opId = journal.begin(workspace, "sess_s4", "MODIFY", "c.txt", true, "s1", "s2", 3);
        journal.commit(workspace, "sess_s4", opId, "failed");

        // 失败的操作没改过文件, 撤销时应当被识别并跳过, 而不是白跑一遍回滚
        assertEquals("failed", journal.read(workspace, "sess_s4").get(0).status());
    }

    @Test
    void op标记可往返提取() {
        String text = WorkspaceJournal.markOp("已修改 a.txt", "op_123");
        assertTrue(text.contains("已修改 a.txt"));
        assertEquals("op_123", WorkspaceJournal.extractOpId(text));
    }

    @Test
    void 无标记或空内容时提取为null() {
        assertNull(WorkspaceJournal.extractOpId(null));
        assertNull(WorkspaceJournal.extractOpId("普通工具结果, 没有标记"));
        assertNull(WorkspaceJournal.extractOpId("[workspace-op:]"));
    }
}
