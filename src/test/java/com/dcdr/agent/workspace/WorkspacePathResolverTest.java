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
 * 路径解析器单测 —— 本设计唯一真正危险的一段代码, 所以先写测试再谈别的。
 * <p>
 * 覆盖: 穿越 / 绝对路径 / 盘符 / UNC / 保留名 / 敏感路径 / 档位 / 根目录 / 软链接逃逸。
 */
class WorkspacePathResolverTest {

    @TempDir
    Path tempDir;

    private WorkspacePathResolver resolver;
    private WorkspaceEntity workspace;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        WorkspaceProperties props = new WorkspaceProperties();
        resolver = new WorkspacePathResolver(props);

        root = tempDir.resolve("ws");
        Files.createDirectories(root);

        workspace = new WorkspaceEntity();
        workspace.setId("ws_test");
        workspace.setRootPath(root.toRealPath().toString());
        workspace.setAccessLevel(WorkspaceAccess.FULL);
    }

    @Test
    void 正常相对路径可以解析() throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello");

        Path resolved = resolver.resolve(workspace, "a.txt", WorkspaceIntent.READ);
        assertEquals(file.toRealPath(), resolved);
    }

    @Test
    void 子目录文件可以解析() throws IOException {
        Files.createDirectories(root.resolve("src/main"));
        Path file = root.resolve("src/main/A.java");
        Files.writeString(file, "class A {}");

        Path resolved = resolver.resolve(workspace, "src/main/A.java", WorkspaceIntent.READ);
        assertEquals(file.toRealPath(), resolved);
    }

    @Test
    void 空路径读取返回根目录() {
        Path resolved = resolver.resolve(workspace, null, WorkspaceIntent.READ);
        assertEquals(root.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void 空路径写操作被拒绝() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "", WorkspaceIntent.WRITE));
        assertTrue(e.getMessage().contains("根目录"));
    }

    @Test
    void 上级目录穿越被拒绝() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "../outside.txt", WorkspaceIntent.READ));
        assertTrue(e.getMessage().contains("越界"));
    }

    @Test
    void 深层穿越被拒绝() {
        assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "a/b/../../../etc/passwd", WorkspaceIntent.READ));
    }

    @Test
    void 绝对路径被拒绝() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "/etc/passwd", WorkspaceIntent.READ));
        assertTrue(e.getMessage().contains("相对路径"));
    }

    @Test
    void 盘符路径被拒绝() {
        assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "C:/Windows/win.ini", WorkspaceIntent.READ));
    }

    @Test
    void UNC路径被拒绝() {
        assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "\\\\server\\share\\a.txt", WorkspaceIntent.READ));
    }

    @Test
    void Windows保留名被拒绝() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "CON", WorkspaceIntent.READ));
        assertTrue(e.getMessage().contains("保留名"));
    }

    @Test
    void 敏感文件env被拒绝() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "config/.env", WorkspaceIntent.READ));
        assertTrue(e.getMessage().contains("安全策略"));
    }

    @Test
    void 敏感文件ssh目录被拒绝() {
        assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, ".ssh/id_rsa", WorkspaceIntent.READ));
    }

    @Test
    void git内部结构可读但不可写() {
        // READ 档: .git/objects 不在 denyRead 里
        assertNotNull(resolver.resolve(workspace, ".git/objects", WorkspaceIntent.READ));
        // WRITE: 命中 denyWrite
        BusinessException e = assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, ".git/objects/ab/cdef", WorkspaceIntent.WRITE));
        assertTrue(e.getMessage().contains("安全策略"));
    }

    @Test
    void 只读档位不能写() {
        workspace.setAccessLevel(WorkspaceAccess.READ);
        BusinessException e = assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "a.txt", WorkspaceIntent.WRITE));
        assertTrue(e.getMessage().contains("不允许"));
    }

    @Test
    void 可写档位不能删() {
        workspace.setAccessLevel(WorkspaceAccess.WRITE);
        assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "a.txt", WorkspaceIntent.DELETE));
    }

    @Test
    void 完整档位可以删() {
        assertNotNull(resolver.resolve(workspace, "a.txt", WorkspaceIntent.DELETE));
    }

    @Test
    void 新建文件时父目录不存在也能解析() {
        Path resolved = resolver.resolve(workspace, "new/dir/file.txt", WorkspaceIntent.WRITE);
        assertTrue(resolved.toString().replace('\\', '/').endsWith("new/dir/file.txt"));
    }

    @Test
    void 指向工作区外的符号链接被拒绝() throws IOException {
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "secret");

        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 上创建符号链接需要开发者模式或管理员权限, 无权限时跳过
            return;
        }

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, "link/secret.txt", WorkspaceIntent.READ));
        assertTrue(ex.getMessage().contains("符号链接") || ex.getMessage().contains("越界"));
    }

    @Test
    void 未绑定工作区抛出可读提示() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> resolver.resolve(null, "a.txt", WorkspaceIntent.READ));
        assertTrue(e.getMessage().contains("未绑定工作区"));
    }

    @Test
    void 删除时作用于符号链接本身而不是它指向的目标() throws IOException {
        Path real = root.resolve("real.txt");
        Files.writeString(real, "真内容");
        Path link = root.resolve("ln.txt");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 无权限建链接时跳过
        }

        // 读: 透过链接读目标 —— 这是正确语义
        assertEquals(real.toRealPath(), resolver.resolve(workspace, "ln.txt", WorkspaceIntent.READ));

        // 删: 必须返回链接本身。返回目标路径的话, 删链接会把真实文件删掉
        Path deleted = resolver.resolveForDelete(workspace, "ln.txt");
        assertTrue(Files.isSymbolicLink(deleted));
        assertEquals("ln.txt", deleted.getFileName().toString());
    }

    @Test
    void 断链的符号链接也能被删除() throws IOException {
        Path link = root.resolve("broken");
        try {
            Files.createSymbolicLink(link, root.resolve("not-there.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        // 断链时 toRealPath 会抛 NoSuchFileException, 删不了; 链接本身仍然应该可删
        Path deleted = resolver.resolveForDelete(workspace, "broken");
        assertTrue(Files.isSymbolicLink(deleted));
    }

    @Test
    void 回收区目录对模型不可见也不可删() throws IOException {
        // trashLocation=workspace 时回收区就在工作区里: 读它等于把"已删除"的内容再暴露一遍,
        // 删它则直接破坏可撤回性
        Path trash = root.resolve(".workspace-trash/blobs/ab");
        Files.createDirectories(trash);
        Files.writeString(trash.resolve("x"), "old content");

        assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, ".workspace-trash/blobs/ab/x", WorkspaceIntent.READ));
        assertThrows(BusinessException.class,
                () -> resolver.resolve(workspace, ".workspace-trash", WorkspaceIntent.DELETE));
    }
}
