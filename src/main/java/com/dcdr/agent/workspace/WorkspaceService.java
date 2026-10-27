package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.WorkspaceDTO;
import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工作区服务(V3.3 F-49) —— CRUD + 归属校验 + 档位管理。
 * <p>
 * 三条硬约束:
 * <ol>
 *   <li>创建时就把路径 {@code toRealPath()} 落库, 之后只做比对, 不再反复归一化</li>
 *   <li>不接受磁盘根目录(如 {@code C:\}) —— 那等于把整块盘交出去, 与"选一个工作区"的语义不符</li>
 *   <li>删除工作区只删记录, <b>不动磁盘任何文件</b></li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceProperties properties;

    // ===== 查询 =====

    /** 列表: 管理员看全部, 普通用户只看自己的 */
    public List<WorkspaceDTO> list(String userId, boolean isAdmin) {
        ensureEnabled();
        List<WorkspaceEntity> entities = (isAdmin || userId == null)
                ? workspaceRepository.findAllByOrderByUpdatedAtDesc()
                : workspaceRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        return entities.stream().map(WorkspaceService::toDTO).toList();
    }

    public WorkspaceDTO get(String id) {
        WorkspaceEntity e = workspaceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作区不存在: " + id));
        return toDTO(e);
    }

    /**
     * 取实体并校验归属。
     * <p>
     * 工具执行层也会调用本方法<b>再校验一次</b> —— 不是冗余: workflow / 定时任务路径不经过 Controller,
     * 只有 ContextHolder 里的 workspaceId 可信, 上游传进来的任何 id 都必须重新验。
     */
    public WorkspaceEntity requireOwned(String id, String userId, boolean isAdmin) {
        WorkspaceEntity e = workspaceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作区不存在: " + id));
        if (!isAdmin && userId != null && !userId.equals(e.getUserId())) {
            // 越权与不存在返回同样的信息, 不泄露"这个 id 存在但不属于你"
            throw new BusinessException(404, "工作区不存在: " + id);
        }
        return e;
    }

    // ===== 写 =====

    @Transactional
    public WorkspaceDTO create(String userId, String name, String rawRootPath, String accessLevel) {
        ensureEnabled();
        if (rawRootPath == null || rawRootPath.isBlank()) {
            throw new BusinessException(400, "请提供工作区目录路径");
        }

        Path root;
        try {
            root = Paths.get(rawRootPath.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new BusinessException(400, "路径格式非法: " + rawRootPath);
        }
        if (!Files.exists(root)) {
            throw new BusinessException(400, "目录不存在: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new BusinessException(400, "不是目录: " + root);
        }
        if (!Files.isReadable(root)) {
            throw new BusinessException(400, "目录无读取权限: " + root);
        }
        if (root.getParent() == null) {
            throw new BusinessException(400, "不能把磁盘根目录设为工作区, 请选择具体目录");
        }

        Path real;
        try {
            real = root.toRealPath();
        } catch (IOException e) {
            throw new BusinessException(400, "无法解析目录真实路径: " + e.getMessage());
        }

        WorkspaceAccess access = WorkspaceAccess.parse(accessLevel);
        if (access == null) {
            throw new BusinessException(400, "档位非法, 只能是 READ / WRITE / FULL: " + accessLevel);
        }

        workspaceRepository.findFirstByUserIdAndRootPath(userId, real.toString()).ifPresent(w -> {
            throw new BusinessException(409, "该目录已存在工作区: " + w.getName() + " (" + w.getId() + ")");
        });

        LocalDateTime now = LocalDateTime.now();
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setId("ws_" + System.currentTimeMillis());
        entity.setUserId(userId);
        entity.setName(name == null || name.isBlank() ? real.getFileName().toString() : name.trim());
        entity.setRootPath(real.toString());
        entity.setAccessLevel(access);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        workspaceRepository.save(entity);
        log.info("创建工作区: {} -> {} ({})", entity.getId(), real, access);
        return toDTO(entity);
    }

    @Transactional
    public WorkspaceDTO update(String id, String name, String accessLevel) {
        WorkspaceEntity e = workspaceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作区不存在: " + id));
        if (name != null && !name.isBlank()) {
            e.setName(name.trim());
        }
        if (accessLevel != null && !accessLevel.isBlank()) {
            WorkspaceAccess access = WorkspaceAccess.parse(accessLevel);
            if (access == null) {
                throw new BusinessException(400, "档位非法, 只能是 READ / WRITE / FULL: " + accessLevel);
            }
            if (access != e.getAccessLevel()) {
                log.warn("工作区档位变更: {} {} -> {}", id, e.getAccessLevel(), access);
            }
            e.setAccessLevel(access);
        }
        e.setUpdatedAt(LocalDateTime.now());
        workspaceRepository.save(e);
        return toDTO(e);
    }

    /** 删除工作区记录。磁盘文件一个字节都不动 —— 这一点在 API 文档与前端文案都要写死 */
    @Transactional
    public void delete(String id) {
        if (!workspaceRepository.existsById(id)) {
            throw new BusinessException(404, "工作区不存在: " + id);
        }
        workspaceRepository.deleteById(id);
        log.info("删除工作区记录(未触碰磁盘): {}", id);
    }

    // ===== 目录浏览(仅 admin + 显式开启) =====

    /**
     * 列出指定目录下的子目录, 供前端做目录选择器。
     * <p>
     * 这是全功能里<b>最危险</b>的一个接口: 它能把服务器任意目录名枚举出来。
     * 因此三重限制: 功能开关 + 仅管理员 + 不可列文件内容(只返回目录名)。
     */
    /**
     * 列出某个目录下的子目录, 供前端「打开磁盘选择」的面板逐级下钻。
     * <p>
     * 返回体里一次带齐三样导航信息, 而不是让前端自己算:
     * <ul>
     *   <li>{@code path} —— 当前所在目录;</li>
     *   <li>{@code parent} —— 上一级(已在根时为 null);</li>
     *   <li>{@code roots} —— 本机的盘符/根列表, 这是前端自己拿不到的信息 ——
     *       浏览器只知道相对路径, 不知道这台机器有几个盘。</li>
     * </ul>
     * 只返回目录不返回文件: 这个接口的唯一用途是选工作区根, 返回文件只是徒增噪声与暴露面。
     *
     * @param rawPath 要展开的目录绝对路径; 为空时从用户主目录开始
     * @param isAdmin 目录枚举仅对管理员开放(它能枚举整台机器的目录结构)
     */
    public Map<String, Object> browse(String rawPath, boolean isAdmin) {
        ensureEnabled();
        if (!properties.isBrowseEnabled()) {
            throw new BusinessException(403,
                    "目录枚举已关闭(agent.workspace.browse-enabled=false), 请直接输入工作区路径");
        }
        if (!isAdmin) {
            throw new BusinessException(403,
                    "目录枚举仅管理员可用, 请直接输入工作区路径; 或让管理员在本会话代为选择");
        }

        Path dir = (rawPath == null || rawPath.isBlank())
                ? Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize()
                : Paths.get(rawPath.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new BusinessException(400, "不是目录: " + dir);
        }

        List<Map<String, Object>> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                try {
                    if (!Files.isDirectory(child)) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", child.getFileName().toString());
                    item.put("path", child.toAbsolutePath().normalize().toString());
                    item.put("hidden", child.getFileName().toString().startsWith("."));
                    children.add(item);
                } catch (SecurityException ignored) {
                    // 单个目录无权限不影响整体列表
                }
                if (children.size() >= 500) break;
            }
        } catch (IOException e) {
            throw new BusinessException(400, "无法读取目录: " + e.getMessage());
        }
        children.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", dir.toString());
        // 盘符根(D:\)的 getParent() 是 null —— 前端据此隐藏「上一级」
        Path parent = dir.getParent();
        result.put("parent", parent == null ? null : parent.toString());
        result.put("roots", listRoots());
        result.put("children", children);
        return result;
    }

    /**
     * 本机盘符/根列表。Windows 是 C:\ D:\ …, 类 Unix 只有一个 /。
     * <p>
     * {@code File.listRoots()} 是 JDK 里唯一跨平台的做法; {@code FileStore} 拿到的是挂载点粒度,
     * 在 Windows 上会把同一个盘的多个挂载点重复列出。
     */
    private List<Map<String, Object>> listRoots() {
        List<Map<String, Object>> roots = new ArrayList<>();
        for (File root : File.listRoots()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", root.getPath());
            item.put("path", root.getPath());
            roots.add(item);
        }
        return roots;
    }

    // ===== 内部 =====

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(503, "工作区功能未启用(agent.workspace.enabled=false)");
        }
        if (!properties.isLocalMode()) {
            throw new BusinessException(503,
                    "当前为服务器部署形态, 工作区功能已禁用: 用户选中的是服务器的目录, 不是自己电脑上的目录");
        }
    }

    static WorkspaceDTO toDTO(WorkspaceEntity e) {
        WorkspaceDTO dto = new WorkspaceDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setRootPath(e.getRootPath());
        dto.setAccessLevel(e.getAccessLevel());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
