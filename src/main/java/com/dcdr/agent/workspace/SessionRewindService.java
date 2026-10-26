package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.MessageEntity;
import com.dcdr.agent.entity.SessionEntity;
import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.repository.MessageRepository;
import com.dcdr.agent.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 会话撤回(V3.3 F-49 §6.4) —— L2 单轮 / L3 整会话。
 * <p>
 * 一次撤回要同时处理三样东西, 缺一样用户就会觉得"撤了个寂寞":
 * <ol>
 *   <li><b>文件</b>: 按变更日志把每个 path 恢复到该轮之前的状态(每 path 只取最早的 before)</li>
 *   <li><b>消息</b>: 删除该消息及其之后的全部消息(按 seq, 不按时间 —— 同毫秒会把边界消息带走)</li>
 *   <li><b>标记</b>: 日志里这些 op 记为 reverted, 重复撤回直接返回"已撤回"</li>
 * </ol>
 * <p>
 * <b>冲突检测是硬要求</b>: 用户可能在这一轮之后、点撤回之前自己在 IDE 里改了文件。
 * 无脑回滚 = 覆盖他的手改, 比不撤回更糟。这里比对当前文件 sha 与日志记录的 afterSha,
 * 不一致就跳过并单独报告, 且<b>不提供"强制覆盖"选项</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRewindService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final WorkspaceService workspaceService;
    private final WorkspacePathResolver pathResolver;
    private final WorkspaceJournal journal;
    private final WorkspaceFileService fileService;

    /** 撤回范围 */
    public enum Scope {
        /** 只回滚文件, 保留对话 */
        FILES,
        /** 文件 + 消息 */
        ALL
    }

    public record Skipped(String path, String reason) {
    }

    public record Report(List<String> reverted, List<Skipped> skipped, int messagesDeleted) {
    }

    /** 可撤回点: 每条用户消息一个, 供前端在消息旁显示"撤回此轮" */
    public record Checkpoint(String messageId, int seq, String preview, boolean rewindable) {
    }

    /** 列出可撤回点 */
    public List<Checkpoint> checkpoints(String sessionId, String userId, boolean isAdmin) {
        SessionEntity session = requireSession(sessionId, userId, isAdmin);
        List<MessageEntity> messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        boolean hasWorkspace = session.getWorkspaceId() != null && !session.getWorkspaceId().isBlank();

        List<Checkpoint> result = new ArrayList<>();
        for (MessageEntity m : messages) {
            if (!"user".equals(m.getRole())) continue;
            String preview = m.getContent() == null ? "" : m.getContent().replaceAll("\\s+", " ").trim();
            if (preview.length() > 40) preview = preview.substring(0, 40) + "…";
            result.add(new Checkpoint(String.valueOf(m.getId()), m.getSeq(), preview, hasWorkspace));
        }
        return result;
    }

    /**
     * 执行撤回。
     *
     * @param messageId 撤回起点: 该消息及其之后的一切都退回
     * @param scope     FILES 只回滚文件; ALL 文件 + 消息
     */
    @Transactional
    public Report rewind(String sessionId, String messageId, Scope scope) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "会话不存在: " + sessionId));

        MessageEntity anchor;
        try {
            anchor = messageRepository.findById(Long.parseLong(messageId))
                    .orElseThrow(() -> new BusinessException(404, "消息不存在: " + messageId));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "非法的消息 ID: " + messageId);
        }
        if (!sessionId.equals(anchor.getSessionId())) {
            throw new BusinessException(404, "消息不属于该会话: " + messageId);
        }

        List<String> reverted = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        int messagesDeleted = 0;

        if (session.getWorkspaceId() != null && !session.getWorkspaceId().isBlank()) {
            WorkspaceEntity workspace = workspaceService.requireOwned(session.getWorkspaceId(), session.getUserId(), true);
            rewindFiles(workspace, sessionId, anchor, reverted, skipped);
        }

        if (scope == Scope.ALL) {
            messagesDeleted = (int) messageRepository
                    .countBySessionIdAndSeqGreaterThanEqual(sessionId, anchor.getSeq());
            if (messagesDeleted > 0) {
                messageRepository.deleteBySessionIdAndSeqGreaterThanEqual(sessionId, anchor.getSeq());
            }
        }

        log.info("会话撤回完成: session={}, anchor={}, files={}, skipped={}, messages={}",
                sessionId, messageId, reverted.size(), skipped.size(), messagesDeleted);
        return new Report(reverted, skipped, messagesDeleted);
    }

    // ===== 内部 =====

    private void rewindFiles(WorkspaceEntity workspace, String sessionId, MessageEntity anchor,
                             List<String> reverted, List<Skipped> skipped) {
        List<WorkspaceJournal.Entry> entries = journal.read(workspace, sessionId);
        if (entries.isEmpty()) return;

        Instant boundary = toInstant(anchor.getCreatedAt());

        // 每个 path 只取最早的 before 状态: 一轮里改 5 次, 要回到最初始那份
        Map<String, WorkspaceJournal.Entry> earliest = new LinkedHashMap<>();
        List<String> opIds = new ArrayList<>();
        for (WorkspaceJournal.Entry e : entries) {
            if ("reverted".equals(e.status())) continue;
            Instant ts;
            try {
                ts = Instant.parse(e.ts());
            } catch (Exception ex) {
                continue;
            }
            // 允许 1 秒误差: 日志写入与消息落库的先后顺序不保证严格有序
            if (ts.isBefore(boundary.minusSeconds(1))) continue;
            opIds.add(e.opId());
            earliest.merge(e.path(), e, (a, b) -> a.seq() <= b.seq() ? a : b);
        }
        if (earliest.isEmpty()) return;

        for (Map.Entry<String, WorkspaceJournal.Entry> item : earliest.entrySet()) {
            String relPath = item.getKey();
            WorkspaceJournal.Entry e = item.getValue();
            Path target;
            try {
                target = pathResolver.resolve(workspace, relPath, WorkspaceIntent.WRITE);
            } catch (BusinessException ex) {
                skipped.add(new Skipped(relPath, "路径校验未通过: " + ex.getMessage()));
                continue;
            }

            boolean exists = Files.exists(target);
            if (e.existed()) {
                if (exists && e.afterSha() != null && !shaMatches(target, e.afterSha())) {
                    skipped.add(new Skipped(relPath, "文件在撤回前被外部修改过, 已跳过以免覆盖你的改动"));
                    continue;
                }
                try {
                    fileService.restore(workspace, relPath, true, e.beforeSha());
                    reverted.add(relPath);
                } catch (BusinessException ex) {
                    skipped.add(new Skipped(relPath, ex.getMessage()));
                }
            } else {
                // 这一轮新建的文件: 撤回 = 移入回收区(仍可再恢复, 撤回本身也留退路)
                if (!exists) {
                    reverted.add(relPath);
                    continue;
                }
                if (e.afterSha() != null && !shaMatches(target, e.afterSha())) {
                    skipped.add(new Skipped(relPath, "新建的文件之后又被改过, 已跳过"));
                    continue;
                }
                try {
                    fileService.restore(workspace, relPath, false, null);
                    reverted.add(relPath);
                } catch (BusinessException ex) {
                    skipped.add(new Skipped(relPath, ex.getMessage()));
                }
            }
        }

        if (!opIds.isEmpty()) {
            journal.markReverted(workspace, sessionId, opIds);
        }
    }

    private boolean shaMatches(Path file, String expected) {
        try {
            return WorkspaceTrashService.sha256(file).equalsIgnoreCase(expected);
        } catch (Exception e) {
            return false;
        }
    }

    private SessionEntity requireSession(String sessionId, String userId, boolean isAdmin) {
        SessionEntity s = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "会话不存在: " + sessionId));
        if (!isAdmin && userId != null && !userId.equals(s.getUserId())) {
            throw new BusinessException(404, "会话不存在: " + sessionId);
        }
        return s;
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null
                ? Instant.EPOCH
                : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
