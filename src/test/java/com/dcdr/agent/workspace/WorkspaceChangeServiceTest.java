package com.dcdr.agent.workspace;

import com.dcdr.agent.audit.AuditService;
import com.dcdr.agent.entity.WorkspaceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 变更面板聚合逻辑测试 —— 覆盖三件最容易做错的事:
 * 同一文件多次改动只出一行(且对比的是"最早之前")、撤销后仍可见但标为已撤销、删除的文件也能给出 diff。
 */
class WorkspaceChangeServiceTest {

    @TempDir
    Path tempDir;

    private final AuditService auditService = mock(AuditService.class);
    private WorkspaceChangeService changes;
    private WorkspaceFileService files;
    private WorkspaceJournal journal;
    private WorkspaceEntity workspace;
    private Path root;
    private static final String SESSION = "sess_test";

    @BeforeEach
    void setUp() throws IOException {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setDataDir(tempDir.resolve("data").toString());
        WorkspacePathResolver resolver = new WorkspacePathResolver(props);
        WorkspaceTrashService trash = new WorkspaceTrashService(props);
        journal = new WorkspaceJournal(props);
        files = new WorkspaceFileService(props, resolver, trash, journal,
                new WorkspaceAuditor(auditService));
        changes = new WorkspaceChangeService(journal, trash, files, resolver);

        root = tempDir.resolve("ws");
        Files.createDirectories(root);

        workspace = new WorkspaceEntity();
        workspace.setId("ws_test");
        workspace.setRootPath(root.toRealPath().toString());
        workspace.setAccessLevel(WorkspaceAccess.FULL);
    }

    @Test
    void 新建文件给出全加号diff() {
        files.write(workspace, SESSION, "a.txt", "line1\nline2\n", false);

        WorkspaceChangeService.Changes result = changes.list(workspace, SESSION);

        assertEquals(1, result.files().size());
        WorkspaceChangeService.FileChange c = result.files().get(0);
        assertEquals("a.txt", c.path());
        assertEquals("added", c.changeType());
        assertTrue(c.active());
        assertEquals(2, c.added());
        assertEquals(0, c.removed());
        assertTrue(hasLine(c, "add", "line1"));
    }

    @Test
    void 同一文件改多次只出一行且对比最初内容() throws IOException {
        // 预置文件(不走 service): 这样日志里第一条记录就是"已存在", 才测得出"取最早 beforeSha"
        preset("a.txt", "v0\n");
        files.edit(workspace, SESSION, "a.txt", "v0", "v1", false);
        files.edit(workspace, SESSION, "a.txt", "v1", "v2", false);

        WorkspaceChangeService.Changes result = changes.list(workspace, SESSION);

        assertEquals(1, result.files().size(), "三次改动应聚合为一行");
        WorkspaceChangeService.FileChange c = result.files().get(0);
        assertTrue(hasLine(c, "del", "v0"),
                "改前应取最早的 v0 而不是上一次的 v1: " + c.diff());
        assertTrue(hasLine(c, "add", "v2"));
    }

    @Test
    void 删除的文件标为deleted并给出全减号() throws IOException {
        preset("a.txt", "x\ny\n");
        files.delete(workspace, SESSION, "a.txt", false);

        WorkspaceChangeService.Changes result = changes.list(workspace, SESSION);

        assertEquals(1, result.files().size());
        WorkspaceChangeService.FileChange c = result.files().get(0);
        assertEquals("deleted", c.changeType());
        assertEquals(0, c.added());
        assertEquals(2, c.removed());
        assertFalse(Files.exists(root.resolve("a.txt")));
    }

    @Test
    void 撤销后仍可看到但标记为已撤销() {
        String opId = extractOp(files.write(workspace, SESSION, "a.txt", "hello\n", false));
        journal.markReverted(workspace, SESSION, java.util.List.of(opId));

        WorkspaceChangeService.Changes result = changes.list(workspace, SESSION);

        assertEquals(1, result.files().size(), "已撤销的改动仍要能看到(勾选后才显示)");
        assertFalse(result.files().get(0).active());
        assertEquals(0, result.activeCount());
    }

    @Test
    void 全部失败的操作不出现在面板里() {
        String opId = extractOp(files.write(workspace, SESSION, "a.txt", "hello\n", false));
        journal.commit(workspace, SESSION, opId, "failed");

        WorkspaceChangeService.Changes result = changes.list(workspace, SESSION);

        assertTrue(result.files().isEmpty(), "磁盘没被动过的失败操作不该出现在变更列表");
    }

    @Test
    void 按文件取并展开全文时整个文件都在且没有折叠() throws IOException {
        StringBuilder before = new StringBuilder();
        for (int i = 1; i <= 40; i++) before.append("line").append(i).append('\n');
        preset("a.txt", before.toString());
        files.edit(workspace, SESSION, "a.txt", "line20", "CHANGED", false);

        // 默认: 只有改动附近的行, 其余折叠
        var def = changes.list(workspace, SESSION).files().get(0);
        assertTrue(def.diff().stream().anyMatch(l -> "skip".equals(l.type())),
                "默认上下文下应有折叠标记");

        // 单文件 + 全文: 40 行全在
        var full = changes.list(workspace, SESSION, "a.txt", 1000, 2000).files().get(0);
        assertFalse(full.diff().stream().anyMatch(l -> "skip".equals(l.type())),
                "展开全文时不该有折叠标记");
        // 40 行 + 替换产生的 1 条新增行
        assertEquals(41, full.diff().size(), "整个文件的行都应输出");

        // 指定不存在的文件 → 空清单, 而不是把全部文件返回
        assertTrue(changes.list(workspace, SESSION, "nope.txt", 3, 200).files().isEmpty());
    }

    @Test
    void 未绑定任何改动时返回空清单() {
        WorkspaceChangeService.Changes result = changes.list(workspace, SESSION);

        assertTrue(result.files().isEmpty());
        assertEquals(0, result.activeCount());
        assertFalse(result.truncated());
    }

    @Test
    void 二进制文件不生成diff但要说明原因() throws IOException {
        Path bin = root.resolve("logo.png");
        Files.write(bin, new byte[]{0x00, 0x01, 0x02, 0x03});

        // stash 既建快照又把原文件移走, 正好模拟"覆盖了一个二进制文件后当场删掉它"
        WorkspaceProperties props = new WorkspaceProperties();
        props.setDataDir(tempDir.resolve("data").toString());
        WorkspaceTrashService trash = new WorkspaceTrashService(props);
        String sha = trash.stash(workspace, bin);
        String opId = journal.begin(workspace, SESSION, "MODIFY", "logo.png", true, sha, null, 0);
        journal.commit(workspace, SESSION, opId, "done");

        WorkspaceChangeService.Changes result = changes.list(workspace, SESSION);

        WorkspaceChangeService.FileChange c = result.files().get(0);
        assertTrue(c.binary(), "含 NUL 的内容应判定为二进制: note=" + c.note());
        assertTrue(c.note().contains("二进制"));
    }

    @Test
    void 快照被清理时说明无法对比() throws IOException {
        // 预置文件后再覆盖, 日志里才会有"改前快照"; 然后删掉 blob 模拟保留期清理
        preset("a.txt", "old\n");
        files.write(workspace, SESSION, "a.txt", "new\n", true);

        WorkspaceProperties props = new WorkspaceProperties();
        props.setDataDir(tempDir.resolve("data").toString());
        WorkspaceTrashService trash = new WorkspaceTrashService(props);
        Path blobDir = trash.baseDir(workspace).resolve("blobs");
        assertTrue(Files.exists(blobDir), "前置条件: 快照目录应已建立");
        try (var walk = Files.walk(blobDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(blobDir))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 清理失败就让断言去暴露
                        }
                    });
        } catch (IOException e) {
            fail("清理快照失败: " + e.getMessage());
        }

        WorkspaceChangeService.Changes result = changes.list(workspace, SESSION);

        WorkspaceChangeService.FileChange c = result.files().get(0);
        assertTrue(c.snapshotMissing(), "快照没了要标记出来: note=" + c.note());
        assertEquals("改前内容已被清理, 无法对比", c.note(),
                "missing=" + c.snapshotMissing() + ", type=" + c.changeType() + ", binary=" + c.binary());
    }

    @Test
    void 目录与符号链接记录也能出现在清单里且不生成diff() throws IOException {
        Path sub = root.resolve("sub");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("f.txt"), "hi");
        files.delete(workspace, SESSION, "sub", true);

        WorkspaceChangeService.Changes result = changes.list(workspace, SESSION);

        assertFalse(result.files().isEmpty());
        assertTrue(result.files().stream().allMatch(c -> c.note() != null || !c.diff().isEmpty()),
                "每条都应要么有 diff 要么有说明");
    }

    /** 从工具结果里取 opId, 与前端走的同一套标记 */
    private static String extractOp(String toolResult) {
        String opId = WorkspaceJournal.extractOpId(toolResult);
        assertNotNull(opId, "工具结果里应带 opId 标记: " + toolResult);
        return opId;
    }

    /** 结构化 diff 里是否存在某条指定类型与内容的行为 */
    private static boolean hasLine(WorkspaceChangeService.FileChange c, String type, String text) {
        return c.diff().stream().anyMatch(l -> type.equals(l.type()) && text.equals(l.text()));
    }

    /**
     * 直接往磁盘写文件, 不经 service —— 这样变更日志里第一条记录就是"文件已存在",
     * 才能测出"改前内容取自最早的 beforeSha"这类逻辑。通过 write() 新建时 existed=false,
     * 改前状态本来就是"不存在", 测不出东西。
     */
    private void preset(String relPath, String content) throws IOException {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
