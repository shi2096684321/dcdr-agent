package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.WorkspaceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件读写的核心行为测试 —— 覆盖最容易做错的三件事: 编码保持、换行保持、外部改动保护。
 * <p>
 * 这三个一旦做错, 用户看到的不是"功能有点问题", 而是"我的文件被搞坏了"。
 */
class WorkspaceFileServiceTest {

    @TempDir
    Path tempDir;

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
        files = new WorkspaceFileService(props, resolver, trash, journal);

        root = tempDir.resolve("ws");
        Files.createDirectories(root);

        workspace = new WorkspaceEntity();
        workspace.setId("ws_test");
        workspace.setRootPath(root.toRealPath().toString());
        workspace.setAccessLevel(WorkspaceAccess.FULL);
    }

    @Test
    void 新建文件后可读回() {
        files.write(workspace, SESSION, "a.txt", "line1\nline2\n", false);

        String out = files.read(workspace, "a.txt", null, null);
        assertTrue(out.contains("line1"));
        assertTrue(out.contains("line2"));
        assertTrue(out.contains("[workspace_read]"));
    }

    @Test
    void 已存在文件必须显式overwrite() {
        files.write(workspace, SESSION, "a.txt", "v1", false);
        BusinessException e = assertThrows(BusinessException.class,
                () -> files.write(workspace, SESSION, "a.txt", "v2", false));
        assertTrue(e.getMessage().contains("overwrite"));
    }

    @Test
    void edit精确替换单处() {
        files.write(workspace, SESSION, "a.txt", "int a = 1;\nint b = 2;\n", false);

        files.edit(workspace, SESSION, "a.txt", "int b = 2;", "int b = 3;", false);

        assertTrue(files.read(workspace, "a.txt", null, null).contains("int b = 3;"));
    }

    @Test
    void edit多处匹配时拒绝而不是任选一处() throws IOException {
        files.write(workspace, SESSION, "a.txt", "foo\nfoo\n", false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> files.edit(workspace, SESSION, "a.txt", "foo", "bar", false));
        assertTrue(e.getMessage().contains("出现 2 次"));
        // 文件必须原封不动
        assertEquals("foo\nfoo\n", Files.readString(root.resolve("a.txt")));
    }

    @Test
    void edit找不到原文时明确报错() {
        files.write(workspace, SESSION, "a.txt", "hello", false);
        BusinessException e = assertThrows(BusinessException.class,
                () -> files.edit(workspace, SESSION, "a.txt", "not-exist", "x", false));
        assertTrue(e.getMessage().contains("未找到"));
    }

    @Test
    void edit保持CRLF换行() throws IOException {
        Path file = root.resolve("crlf.txt");
        Files.writeString(file, "a\r\nb\r\n");
        files.read(workspace, "crlf.txt", null, null);

        files.edit(workspace, SESSION, "crlf.txt", "b", "c", false);

        String raw = Files.readString(file);
        assertTrue(raw.contains("\r\n"), "必须保留 CRLF");
        assertFalse(raw.matches("(?s).*[^\r]\\n.*"), "不能出现裸 LF");
        assertTrue(raw.contains("c"));
    }

    @Test
    void edit保持GBK编码() throws IOException {
        Charset gbk = Charset.forName("GBK");
        Path file = root.resolve("gbk.txt");
        Files.write(file, "第一行\n第二行\n".getBytes(gbk));
        files.read(workspace, "gbk.txt", null, null);

        files.edit(workspace, SESSION, "gbk.txt", "第二行", "第二行(改)", false);

        byte[] raw = Files.readAllBytes(file);
        String decoded = new String(raw, gbk);
        assertTrue(decoded.contains("第二行(改)"), "GBK 文件写回后必须仍是 GBK, 不能变乱码");
    }

    @Test
    void 外部修改后写入被拒绝() throws IOException {
        files.write(workspace, SESSION, "a.txt", "original", false);
        files.read(workspace, "a.txt", null, null); // 登记读取状态

        // 模拟用户在 IDE 里改了它
        Path file = root.resolve("a.txt");
        Files.writeString(file, "user edited");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000));

        BusinessException e = assertThrows(BusinessException.class,
                () -> files.write(workspace, SESSION, "a.txt", "model version", true));
        assertTrue(e.getMessage().contains("外部修改"));
    }

    @Test
    void 删除后文件消失但内容在回收区() throws IOException {
        files.write(workspace, SESSION, "a.txt", "keep me", false);

        files.delete(workspace, SESSION, "a.txt", false);

        assertFalse(Files.exists(root.resolve("a.txt")));
        // 回收区里能找到这份内容
        Path blobDir = tempDir.resolve("data").resolve("workspace-trash").resolve("ws_test").resolve("blobs");
        assertTrue(Files.exists(blobDir));
        assertTrue(Files.walk(blobDir).anyMatch(p -> p.toFile().isFile()));
    }

    @Test
    void 删除目录必须显式recursive() throws IOException {
        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("dir/a.txt"), "x");

        BusinessException e = assertThrows(BusinessException.class,
                () -> files.delete(workspace, SESSION, "dir", false));
        assertTrue(e.getMessage().contains("recursive"));
    }

    @Test
    void 递归删除目录会逐个进回收区() throws IOException {
        Files.createDirectories(root.resolve("dir/sub"));
        Files.writeString(root.resolve("dir/a.txt"), "a");
        Files.writeString(root.resolve("dir/sub/b.txt"), "b");

        String out = files.delete(workspace, SESSION, "dir", true);

        assertTrue(out.contains("2 个文件"));
        assertFalse(Files.exists(root.resolve("dir")));
    }

    @Test
    void 超限写入被拒绝() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setMaxWriteBytes(10);
        WorkspacePathResolver resolver = new WorkspacePathResolver(props);
        WorkspaceFileService small = new WorkspaceFileService(props, resolver,
                new WorkspaceTrashService(props), new WorkspaceJournal(props));

        assertThrows(BusinessException.class,
                () -> small.write(workspace, SESSION, "big.txt", "x".repeat(100), false));
    }

    @Test
    void 二进制文件不按文本读() throws IOException {
        Files.write(root.resolve("bin.dat"), new byte[]{1, 2, 0, 4, 5});

        BusinessException e = assertThrows(BusinessException.class,
                () -> files.read(workspace, "bin.dat", null, null));
        assertTrue(e.getMessage().contains("二进制"));
    }

    @Test
    void 分页读取提示剩余行数() {
        files.write(workspace, SESSION, "many.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n", false);

        String out = files.read(workspace, "many.txt", 1, 3);
        assertTrue(out.contains("第 1-3 行"));
        assertTrue(out.contains("还有"));
        assertTrue(out.contains("offset=4"));
    }

    @Test
    void offset超出总行数时明确报错而不是返回空() {
        files.write(workspace, SESSION, "three.txt", "1\n2\n3\n", false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> files.read(workspace, "three.txt", 99, 10));
        assertTrue(e.getMessage().contains("超过文件总行数"));
    }

    @Test
    void GBK文件写入无法表示的字符时报错而不是静默写成问号() throws IOException {
        Path gbk = root.resolve("cn.txt");
        Files.write(gbk, "中文".getBytes(Charset.forName("GBK")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> files.write(workspace, SESSION, "cn.txt", "中文😀", true));
        assertTrue(e.getMessage().contains("GBK"));
        // 关键: 报错发生在动文件之前, 原内容必须完好
        assertEquals("中文", new String(Files.readAllBytes(gbk), Charset.forName("GBK")));
    }

    @Test
    void 递归删除会为空目录留下可恢复记录() throws IOException {
        Path tree = root.resolve("tree");
        Files.createDirectories(tree.resolve("logs/empty"));
        Files.writeString(tree.resolve("a.txt"), "x");

        files.delete(workspace, SESSION, "tree", true);

        java.util.List<WorkspaceJournal.Entry> entries = journal.read(workspace, SESSION);
        // 没有文件可依托的目录必须单独留痕, 否则撤回只还原文件, 目录结构永久缺失
        assertTrue(entries.stream().anyMatch(e ->
                "DELETE_DIR".equals(e.type()) && "tree/logs/empty".equals(e.path())));
        // 有文件的目录不重复记录: 文件的父链会自动重建它
        assertFalse(entries.stream().anyMatch(e ->
                "DELETE_DIR".equals(e.type()) && "tree".equals(e.path())));
        assertFalse(Files.exists(tree));
    }

    @Test
    void 递归删除不越过子项的deny规则() throws IOException {
        Path project = root.resolve("project");
        Files.createDirectories(project.resolve(".git/objects"));
        Files.writeString(project.resolve(".git/config"), "[core]");
        Files.writeString(project.resolve("a.txt"), "keep me");

        // 只在父目录上判 deny 是漏的: project 本身合法, 但里面有禁止删除的 .git
        BusinessException e = assertThrows(BusinessException.class,
                () -> files.delete(workspace, SESSION, "project", true));
        assertTrue(e.getMessage().contains(".git"));
        // 整体拒绝, 一个文件都不该被搬走
        assertTrue(Files.exists(project.resolve("a.txt")));
        assertTrue(Files.exists(project.resolve(".git/config")));
    }
}
