package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.WorkspaceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回收区单测 —— 重点验证三件事:
 * 内容寻址(同内容不重复存)、stash 后原文件消失、restore 内容与哈希一致、防篡改。
 */
class WorkspaceTrashServiceTest {

    @TempDir
    Path tempDir;

    private WorkspaceTrashService trash;
    private WorkspaceEntity workspace;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setDataDir(tempDir.resolve("data").toString());
        trash = new WorkspaceTrashService(props);

        root = tempDir.resolve("ws");
        Files.createDirectories(root);

        workspace = new WorkspaceEntity();
        workspace.setId("ws_test");
        workspace.setRootPath(root.toString());
        workspace.setAccessLevel(WorkspaceAccess.FULL);
    }

    @Test
    void stash后原文件消失且快照可恢复() throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello world");

        String sha = trash.stash(workspace, file);

        assertEquals(64, sha.length());
        assertFalse(Files.exists(file), "stash 之后原位置应为空(内容已移入回收区)");
        assertTrue(trash.has(workspace, sha));

        trash.restore(workspace, sha, file);
        assertEquals("hello world", Files.readString(file));
    }

    @Test
    void 相同内容只存一份() throws IOException {
        Path a = root.resolve("a.txt");
        Path b = root.resolve("b.txt");
        Files.writeString(a, "same content");
        Files.writeString(b, "same content");

        String shaA = trash.stash(workspace, a);
        String shaB = trash.stash(workspace, b);

        assertEquals(shaA, shaB, "内容相同则哈希相同, 天然去重");
        assertTrue(Files.exists(trash.blobPath(workspace, shaA)));
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
    }

    @Test
    void 恢复会创建缺失的父目录() throws IOException {
        Path file = root.resolve("deep/nested/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");

        String sha = trash.stash(workspace, file);
        Files.deleteIfExists(file.getParent());
        Files.deleteIfExists(root.resolve("deep"));

        trash.restore(workspace, sha, file);
        assertEquals("x", Files.readString(file));
    }

    @Test
    void 快照被篡改时拒绝恢复() throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "original");
        String sha = trash.stash(workspace, file);

        // 篡改 blob 内容
        Files.writeString(trash.blobPath(workspace, sha), "tampered content");

        BusinessException e = assertThrows(BusinessException.class,
                () -> trash.restore(workspace, sha, root.resolve("restored.txt")));
        assertTrue(e.getMessage().contains("校验失败"));
    }

    @Test
    void 非法sha被拒绝() {
        assertThrows(BusinessException.class, () -> trash.has(workspace, "../../etc/passwd"));
        assertThrows(BusinessException.class, () -> trash.has(workspace, "abc"));
    }

    @Test
    void 快照不存在时给出明确错误() {
        String sha = "0".repeat(64);
        BusinessException e = assertThrows(BusinessException.class,
                () -> trash.restore(workspace, sha, root.resolve("x.txt")));
        assertTrue(e.getMessage().contains("快照不存在"));
    }

    @Test
    void 非普通文件不允许快照() throws IOException {
        Path dir = root.resolve("somedir");
        Files.createDirectories(dir);
        assertThrows(BusinessException.class, () -> trash.stash(workspace, dir));
    }
}
