package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.WorkspaceEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话变更面板的数据来源(V3.3 F-49) —— 把 append-only 的变更日志整理成"人看得懂的文件清单 + diff"。
 * <p>
 * <b>为什么不直接暴露 journal</b>: 日志是"每次操作一行"的事件流, 同一个文件在一轮里改 5 次就有 5 条;
 * 界面上要的是"这个文件现在跟一开始比差在哪"。聚合与对比必须做在服务端 ——
 * 前端拿不到磁盘, 也读不出回收区里的快照。
 * <p>
 * <b>diff 的两端分别取自哪里(这点最容易搞错)</b>:
 * <ul>
 *   <li><b>改前</b>: 日志里该文件<b>最早</b>那条记录的 {@code beforeSha} → 回收区快照。不能用"最后一次",
 *       否则一轮里改 5 次就只对比最后一对。</li>
 *   <li><b>改后</b>: 直接读<b>磁盘上的当前内容</b>。不用日志的 {@code afterSha}, 因为写入后产生的新内容
 *       从来不入回收区(那是给"撤销"用的旧内容)。顺带的好处是: 用户在 IDE 里又改过一遍, 这里能如实反映。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceChangeService {

    /** 一次最多返回多少个文件; 再多前端也看不过来, 而且响应体要有界 */
    private static final int MAX_FILES = 50;

    /** 单个文件的 diff 输出行上限 */
    private static final int MAX_DIFF_LINES = 200;

    /** 参与 diff 的文本大小上限(两侧各自); 超过就只报"改了多少字节", 不做逐行对比 */
    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    private static final int BINARY_PROBE_BYTES = 8192;

    private final WorkspaceJournal journal;
    private final WorkspaceTrashService trash;
    private final WorkspaceFileService fileService;
    private final WorkspacePathResolver resolver;

    /**
     * 一个文件在本会话里的净变化。
     *
     * @param changeType      added / modified / deleted / unchanged
     * @param active          是否还有未撤销的改动; false 表示这一轮的操作都已被撤回, 仅供查看历史
     * @param snapshotMissing 改前内容已不在回收区(按保留期清理了), 无法对比
     * @param binary          判定为二进制文件, 不生成 diff
     * @param note            给用户看的一句话说明(为什么没有 diff), 无特殊情况为 null
     */
    public record FileChange(
            String path,
            String changeType,
            boolean active,
            boolean snapshotMissing,
            boolean binary,
            int added,
            int removed,
            boolean diffTruncated,
            List<String> diff,
            String note,
            long lastSeq) {
    }

    /**
     * @param truncated 是否因为文件数超过 {@link #MAX_FILES} 被截断
     */
    public record Changes(List<FileChange> files, boolean truncated, int activeCount) {
    }

    /** 汇总本会话的全部文件变更, 最近的排在前面 */
    public Changes list(WorkspaceEntity workspace, String sessionId) {
        Map<String, List<WorkspaceJournal.Entry>> byPath = new LinkedHashMap<>();
        for (WorkspaceJournal.Entry e : journal.read(workspace, sessionId)) {
            byPath.computeIfAbsent(e.path(), k -> new ArrayList<>()).add(e);
        }

        List<FileChange> all = new ArrayList<>(byPath.size());
        for (Map.Entry<String, List<WorkspaceJournal.Entry>> item : byPath.entrySet()) {
            try {
                FileChange change = build(workspace, item.getKey(), item.getValue());
                if (change != null) all.add(change);
            } catch (RuntimeException e) {
                // 单个文件解析失败不能拖垮整个面板: 一个文件名带奇怪字符就整页 500 是不可接受的
                log.warn("生成变更条目失败, 已跳过: path={} ({})", item.getKey(), e.getMessage());
            }
        }

        all.sort(Comparator.comparingLong(FileChange::lastSeq).reversed());
        int activeCount = (int) all.stream().filter(FileChange::active).count();
        boolean truncated = all.size() > MAX_FILES;
        return new Changes(truncated ? new ArrayList<>(all.subList(0, MAX_FILES)) : all, truncated, activeCount);
    }

    // ===== 内部 =====

    private FileChange build(WorkspaceEntity workspace, String relPath, List<WorkspaceJournal.Entry> entries) {
        // 全是失败记录: 磁盘从没被改动过, 列出来只会让人以为出过事
        boolean allFailed = entries.stream().allMatch(e -> "failed".equals(e.status()));
        if (allFailed) return null;

        // journal.read 已按 seq 升序, 首条即"改动前"、末条即"最后一次动作"
        WorkspaceJournal.Entry first = entries.get(0);
        WorkspaceJournal.Entry last = entries.get(entries.size() - 1);
        boolean active = entries.stream().anyMatch(e -> isActive(e.status()));

        if ("DELETE_LINK".equals(last.type()) || "DELETE_DIR".equals(last.type())) {
            return new FileChange(relPath, "deleted", active, false, false, 0, 0, false, List.of(),
                    "DELETE_LINK".equals(last.type()) ? "符号链接, 不生成 diff" : "空目录, 不生成 diff",
                    last.seq());
        }

        Path target = null;
        boolean nowExists = false;
        try {
            target = resolver.resolve(workspace, relPath, WorkspaceIntent.READ);
            nowExists = Files.exists(target);
        } catch (BusinessException e) {
            // 路径已不可读(工作区根被改、或后来被 deny 规则命中): 只影响 diff, 不影响清单本身
            log.debug("变更面板: 路径解析失败, 跳过 diff: {} ({})", relPath, e.getMessage());
        }

        String changeType = !first.existed()
                ? (nowExists ? "added" : "unchanged")
                : (nowExists ? "modified" : "deleted");

        // 改前内容: 只认最早那条的 beforeSha
        byte[] beforeBytes = null;
        if (first.existed()) {
            beforeBytes = trash.readBlobOrNull(workspace, first.beforeSha());
        } else {
            beforeBytes = new byte[0];
        }
        boolean snapshotMissing = first.existed() && beforeBytes == null;

        // 改后内容: 只能从磁盘读
        byte[] afterBytes = new byte[0];
        String note = null;
        if (target != null && nowExists && Files.isRegularFile(target)) {
            try {
                long size = Files.size(target);
                if (size > MAX_TEXT_BYTES) {
                    note = "文件过大, 未生成 diff";
                    afterBytes = null;
                } else {
                    afterBytes = Files.readAllBytes(target);
                }
            } catch (IOException e) {
                note = "读取当前内容失败: " + e.getMessage();
                afterBytes = null;
            }
        }

        boolean binary = isBinary(beforeBytes) || isBinary(afterBytes);
        if (binary) {
            note = "二进制文件, 不生成 diff";
        } else if (snapshotMissing) {
            note = "改前内容已被清理, 无法对比";
        }

        if (binary || snapshotMissing || beforeBytes == null || afterBytes == null) {
            return new FileChange(relPath, changeType, active, snapshotMissing, binary, 0, 0, false,
                    List.of(), note, last.seq());
        }

        String beforeText = beforeBytes.length == 0 ? "" : fileService.decode(beforeBytes).text();
        String afterText = afterBytes.length == 0 ? "" : fileService.decode(afterBytes).text();
        TextDiff.Result diff = TextDiff.unified(beforeText, afterText, 3, MAX_DIFF_LINES);
        return new FileChange(relPath, changeType, active, false, false, diff.added(), diff.removed(),
                diff.truncated(), diff.lines(), diff.coarse() ? "改动过大, 已简化为整体替换" : null, last.seq());
    }

    private static boolean isActive(String status) {
        // begin(进程在中间崩过)也按未撤销算: 文件很可能已经被动过, 藏起来比显示更危险
        return !"reverted".equals(status) && !"failed".equals(status);
    }

    private static boolean isBinary(byte[] bytes) {
        if (bytes == null) return false;
        int probe = Math.min(bytes.length, BINARY_PROBE_BYTES);
        return WorkspaceFileService.looksBinary(probe == bytes.length ? bytes : Arrays.copyOf(bytes, probe));
    }
}
