package com.dcdr.agent.workspace;

import com.dcdr.agent.audit.AuditService;
import com.dcdr.agent.entity.WorkspaceEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作区写/删的审计留痕(V3.3 F-49 §6.5)。
 * <p>
 * <b>只记写、改、删, 不记读</b>: 读是高频动作, 每个文件都记一条会把审计表刷成流水账,
 * 反而淹没真正需要追溯的变更; 而且读取本身不改变磁盘状态, 靠 {@code readStamps} 已能定位。
 * <p>
 * <b>不记录文件内容</b>, 只记路径、字节数、内容哈希和操作 ID —— 审计表不该成为第二份代码副本。
 * <p>
 * <b>失败绝不影响主流程</b>: 审计是旁路, 落库失败只打日志。反过来(审计失败就回滚文件操作)
 * 会让一个纯粹的可观测性问题升级成数据一致性问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceAuditor {

    private final AuditService auditService;

    /** 写入(新建或整体覆盖) */
    public void write(WorkspaceEntity workspace, String relPath, String opId, long bytes, String afterSha) {
        Map<String, Object> detail = base(relPath, opId);
        detail.put("bytes", bytes);
        if (afterSha != null) detail.put("sha256", afterSha);
        record("workspace.write", workspace, relPath, detail);
    }

    /** 片段替换 */
    public void edit(WorkspaceEntity workspace, String relPath, String opId, int replaced,
                     long bytes, String afterSha) {
        Map<String, Object> detail = base(relPath, opId);
        detail.put("replaced", replaced);
        detail.put("bytes", bytes);
        if (afterSha != null) detail.put("sha256", afterSha);
        record("workspace.edit", workspace, relPath, detail);
    }

    /** 删除(文件 / 目录 / 链接统一记一条汇总, 不按子项刷屏) */
    public void delete(WorkspaceEntity workspace, String relPath, String opId,
                       int files, int dirs, int links) {
        Map<String, Object> detail = base(relPath, opId);
        detail.put("files", files);
        detail.put("dirs", dirs);
        detail.put("links", links);
        record("workspace.delete", workspace, relPath, detail);
    }

    private Map<String, Object> base(String relPath, String opId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        // resourceId 列只有 64 字符, 长路径会被数据库拒绝, 所以路径放 detail
        detail.put("path", relPath);
        if (opId != null) detail.put("opId", opId);
        String sessionId = WorkspaceContextHolder.sessionId();
        if (sessionId != null) detail.put("sessionId", sessionId);
        return detail;
    }

    private void record(String action, WorkspaceEntity workspace, String relPath, Map<String, Object> detail) {
        try {
            auditService.record(action, "workspace", workspace.getId(), detail,
                    WorkspaceContextHolder.userId());
        } catch (Exception e) {
            log.warn("工作区审计写入失败(不影响文件操作): action={}, path={}, err={}",
                    action, relPath, e.getMessage());
        }
    }
}
