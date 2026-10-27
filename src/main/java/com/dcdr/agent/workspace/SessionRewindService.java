package com.dcdr.agent.workspace;

import com.dcdr.agent.agent.memory.longterm.LongTermMemoryService;
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

import java.io.IOException;
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
    private final LongTermMemoryService memoryService;

    /** 撤回范围 */
    public enum Scope {
        /** 只回滚文件, 保留对话 */
        FILES,
        /** 文件 + 消息 */
        ALL
    }

    public record Skipped(String path, String reason) {
    }

    /**
     * 撤回结果。
     *
     * @param reverted          已回滚的路径
     * @param skipped           因冲突或校验失败而跳过的路径(附原因, 必须在前端展示 —— 静默跳过等于骗人)
     * @param messagesDeleted   删除的消息条数
     * @param memoriesArchived  归档的长期记忆条数(撤回对话时同步清理, 否则模型仍"记得"被撤回的内容)
     */
    public record Report(List<String> reverted, List<Skipped> skipped, int messagesDeleted, int memoriesArchived) {
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
        int memoriesArchived = 0;

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
            // 撤回对话 = 宣告"这段对话没发生过", 那么它沉淀出来的记忆也必须一起退掉:
            // 文件回滚了、消息删了, 但模型下一轮还能从记忆池里捞到结论 —— 用户会直接感到"没撤干净"。
            // 放在删消息之后执行: 归档本身不依赖消息是否还在, 但失败时要保证消息这条主链路已经完成。
            memoriesArchived = memoryService.archiveBySession(session.getUserId(), sessionId, anchor.getSeq());
        }

        log.info("会话撤回完成: session={}, anchor={}, files={}, skipped={}, messages={}, memories={}",
                sessionId, messageId, reverted.size(), skipped.size(), messagesDeleted, memoriesArchived);
        return new Report(reverted, skipped, messagesDeleted, memoriesArchived);
    }

    /**
     * L1 单操作撤销: 把某一次工具调用造成的文件改动单独回滚, 不动对话与其它操作。
     * <p>
     * 与整轮撤回的区别只在与粒度和边界: 这里日志条目直接按 {@code opId} 定位, 不按消息时间切分,
     * 因此一轮里改了 5 个文件, 用户也能只退掉其中 1 个。
     * <p>
     * <b>回滚本身仍可回滚</b>: 撤销一次写入, 当前内容会先进回收区; 撤销一次删除, 文件从回收区回来。
     * 唯一不可逆的是"回滚到新建前" —— 那个文件进回收区, 需要时仍能手工捞回。
     *
     * @param opId 日志中的操作 ID(由工具结果携带, 前端从工具调用项上发起)
     */
    @Transactional
    public Report undoOperation(String sessionId, String opId, String userId, boolean isAdmin) {
        if (opId == null || opId.isBlank()) {
            throw new BusinessException(400, "请提供要撤销的 operationId");
        }
        SessionEntity session = requireSession(sessionId, userId, isAdmin);
        if (session.getWorkspaceId() == null || session.getWorkspaceId().isBlank()) {
            throw new BusinessException(400, "本会话未绑定工作区, 没有可撤销的文件操作");
        }
        WorkspaceEntity workspace = workspaceService.requireOwned(session.getWorkspaceId(), session.getUserId(), true);

        List<WorkspaceJournal.Entry> targets = new ArrayList<>();
        for (WorkspaceJournal.Entry e : journal.read(workspace, sessionId)) {
            if (opId.equals(e.opId())) targets.add(e);
        }
        if (targets.isEmpty()) {
            throw new BusinessException(404, "找不到该操作记录: " + opId + "(可能已过期清理)");
        }

        List<String> reverted = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        int applicable = 0;
        for (WorkspaceJournal.Entry e : targets) {
            if ("reverted".equals(e.status())) {
                skipped.add(new Skipped(e.path(), "该操作已撤销过"));
                continue;
            }
            // 失败的操作没改动文件, 标成 reverted 只会让日志与事实不符
            if ("failed".equals(e.status())) {
                skipped.add(new Skipped(e.path(), "该操作当时就失败了, 文件未被改动"));
                continue;
            }
            applicable++;
            applyEntry(workspace, e, reverted, skipped);
        }

        // 只有在"该 op 涉及的全部路径都回滚成功"时才标记 reverted。
        // 递归删除是 N 条记录共用一个 opId: 若只成功 1 条就整片标记, 剩下 N-1 个文件就永久失去撤销入口。
        if (applicable > 0 && reverted.size() == applicable) {
            journal.markReverted(workspace, sessionId, List.of(opId));
        }
        log.info("单操作撤销: session={}, op={}, reverted={}, skipped={}",
                sessionId, opId, reverted.size(), skipped.size());
        return new Report(reverted, skipped, 0, 0);
    }

    // ===== 内部 =====

    private void rewindFiles(WorkspaceEntity workspace, String sessionId, MessageEntity anchor,
                             List<String> reverted, List<Skipped> skipped) {
        List<WorkspaceJournal.Entry> entries = journal.read(workspace, sessionId);
        if (entries.isEmpty()) return;

        Instant boundary = toInstant(anchor.getCreatedAt());

        // 每个 path 只取最早的 before 状态: 一轮里改 5 次, 要回到最初始那份
        Map<String, WorkspaceJournal.Entry> earliest = new LinkedHashMap<>();
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
            earliest.merge(e.path(), e, (a, b) -> a.seq() <= b.seq() ? a : b);
        }
        if (earliest.isEmpty()) return;

        // 按 opId 统计"应该成功几条 / 实际成功几条": 递归删除是一个 opId 对应 N 条记录,
        // 只要有一条被冲突跳过, 整片标记 reverted 就会让剩下那些再也撤不回来
        Map<String, int[]> stat = new LinkedHashMap<>();
        for (Map.Entry<String, WorkspaceJournal.Entry> item : earliest.entrySet()) {
            WorkspaceJournal.Entry e = item.getValue();
            int[] counter = stat.computeIfAbsent(e.opId(), k -> new int[2]);
            counter[0]++;
            int before = reverted.size();
            applyEntry(workspace, e, reverted, skipped);
            if (reverted.size() > before) counter[1]++;
        }

        List<String> doneOps = new ArrayList<>();
        stat.forEach((opId, counter) -> {
            if (counter[1] > 0 && counter[1] == counter[0]) doneOps.add(opId);
        });

        if (!doneOps.isEmpty()) {
            journal.markReverted(workspace, sessionId, doneOps);
        }
    }

    /**
     * 把一条日志记录回滚到它的 before 状态。整轮撤回(L2/L3)与单操作撤销(L1)共用这一段,
     * 因为两者的判定逻辑完全一致 —— 差别只在"选哪些记录", 不在"怎么回滚"。
     * <p>
     * 冲突检测是这里唯一真正危险的地方: 用户在 agent 改完之后、点撤销之前自己在 IDE 里动了同一个文件。
     * 无脑覆盖 = 抹掉他的手改, 比不撤销更糟, 因此所有冲突一律跳过并报告, 不提供"强制覆盖"。
     */
    private void applyEntry(WorkspaceEntity workspace, WorkspaceJournal.Entry e,
                            List<String> reverted, List<Skipped> skipped) {
        String relPath = e.path();
        Path target;
        try {
            target = pathResolver.resolve(workspace, relPath, WorkspaceIntent.WRITE);
        } catch (BusinessException ex) {
            skipped.add(new Skipped(relPath, "路径校验未通过: " + ex.getMessage()));
            return;
        }

        boolean exists = Files.exists(target);

        // 空目录记录(DELETE_DIR): 没有内容可回滚, 恢复就是把它建回来。
        // 必须先于文件分支判断 —— 这类记录 beforeSha 为空, 落到下面的 DELETE 分支会被当成
        // "位置已出现同名文件"而跳过, 目录就永远回不来了。
        if ("DELETE_DIR".equals(e.type())) {
            try {
                Files.createDirectories(target);   // 幂等: 目录还在(并发重建过)也算恢复成功
                reverted.add(relPath);
            } catch (IOException ex) {
                skipped.add(new Skipped(relPath, "重建目录失败: " + ex.getMessage()));
            }
            return;
        }

        if (e.existed()) {
            // 改动前文件是存在的(MODIFY / EDIT / DELETE): 从回收区取回原内容
            if (e.afterSha() != null && exists && !shaMatches(target, e.afterSha())) {
                skipped.add(new Skipped(relPath, "文件在撤回前被外部修改过, 已跳过以免覆盖你的改动"));
                return;
            }
            // DELETE 的 afterSha 为空(记录的是"删完之后没有内容"), 此时目标位置若又出现同名文件,
            // 说明有人在删除之后新建了一个 —— 拿旧快照盖上去就是静默销毁一份新内容
            if (e.afterSha() == null && exists) {
                skipped.add(new Skipped(relPath, "该位置已存在新的同名文件, 已跳过以免覆盖"));
                return;
            }
            try {
                fileService.restore(workspace, relPath, true, e.beforeSha());
                reverted.add(relPath);
            } catch (BusinessException ex) {
                skipped.add(new Skipped(relPath, ex.getMessage()));
            }
            return;
        }

        // 改动前文件不存在(CREATE): 撤回 = 移入回收区, 而不是真删 —— 撤回本身也要留退路
        if (!exists) {
            reverted.add(relPath);
            return;
        }
        if (e.afterSha() != null && !shaMatches(target, e.afterSha())) {
            skipped.add(new Skipped(relPath, "新建的文件之后又被改过, 已跳过"));
            return;
        }
        try {
            fileService.restore(workspace, relPath, false, null);
            reverted.add(relPath);
        } catch (BusinessException ex) {
            skipped.add(new Skipped(relPath, ex.getMessage()));
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
