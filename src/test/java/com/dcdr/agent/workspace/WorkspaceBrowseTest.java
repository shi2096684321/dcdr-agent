package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 目录浏览接口测试(V3.3 F-49)。
 * <p>
 * 这个接口是"打开磁盘选工作区"的唯一数据源, 三件事必须稳:
 * 子目录列得对(不混进文件)、上一级算得对(否则下钻进去出不来)、盘符给得全(前端自己拿不到)。
 */
class WorkspaceBrowseTest {

    @TempDir
    Path tempDir;

    private WorkspaceProperties props;
    private WorkspaceService service;

    @BeforeEach
    void setUp() throws Exception {
        props = new WorkspaceProperties();
        props.setEnabled(true);
        props.setLocalMode(true);
        props.setBrowseEnabled(true);
        service = new WorkspaceService(mock(WorkspaceRepository.class), props);

        Files.createDirectories(tempDir.resolve("alpha"));
        Files.createDirectories(tempDir.resolve("beta"));
        Files.createFile(tempDir.resolve("readme.txt"));
    }

    @Test
    @DisplayName("只返回目录, 不返回文件(选的是工作区根, 文件只是噪声)")
    @SuppressWarnings("unchecked")
    void listsDirectoriesOnly() throws Exception {
        Map<String, Object> r = service.browse(tempDir.toString(), true);

        List<Map<String, Object>> children = (List<Map<String, Object>>) r.get("children");
        assertEquals(2, children.size());
        assertEquals(List.of("alpha", "beta"),
                children.stream().map(c -> c.get("name")).toList(),
                "应按名称升序, 且不含 readme.txt");
        assertEquals(tempDir.toRealPath().toString(),
                Path.of(String.valueOf(r.get("path"))).toRealPath().toString());
    }

    @Test
    @DisplayName("返回 parent 与本机盘符列表, 前端才能「上一级」和「换盘」")
    @SuppressWarnings("unchecked")
    void providesParentAndRoots() {
        Map<String, Object> r = service.browse(tempDir.toString(), true);

        assertNotNull(r.get("parent"), "非根目录必须给出上一级, 否则下钻进去就出不来");
        List<Map<String, Object>> roots = (List<Map<String, Object>>) r.get("roots");
        assertFalse(roots.isEmpty(), "盘符列表由后端提供 —— 浏览器不知道这台机器有几个盘");
    }

    @Test
    @DisplayName("已在磁盘根时 parent 为空(前端据此隐藏「上一级」)")
    void rootHasNoParent() {
        Path root = tempDir.getRoot();
        Map<String, Object> r = service.browse(root.toString(), true);
        assertNull(r.get("parent"));
    }

    @Test
    @DisplayName("非管理员请求被拒(这个接口能枚举整台机器的目录结构)")
    void rejectsNonAdmin() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.browse(tempDir.toString(), false));
        assertEquals(403, e.getCode());
    }

    @Test
    @DisplayName("开关关闭时明确告知原因, 而不是静默返回空列表")
    void rejectsWhenDisabled() {
        props.setBrowseEnabled(false);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.browse(tempDir.toString(), true));
        assertEquals(403, e.getCode());
        assertTrue(e.getMessage().contains("browse-enabled"));
    }

    @Test
    @DisplayName("路径不是目录时报错")
    void rejectsNonDirectory() throws Exception {
        Path file = tempDir.resolve("readme.txt");
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.browse(file.toString(), true));
        assertEquals(400, e.getCode());
    }
}
