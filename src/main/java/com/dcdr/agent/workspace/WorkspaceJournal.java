package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.WorkspaceEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作区变更日志(V3.3 F-49 §6.4.1) —— append-only JSONL, 撤回的唯一数据源。
 * <p>
 * 布局: {@code <base>/journal/<sessionId>.jsonl}, 一行一条记录。
 * <p>
 * <b>为什么先写 begin 再动文件</b>: 如果先改文件、后落日志, 进程在两者之间崩溃, 这个文件就永远回不去了。
 * 先落 begin, 最坏情况是存在 begin 而无 end, 恢复时按 blob 的 beforeSha 回滚即可。
 * <p>
 * <b>为什么用文件而不是数据库表</b>: 数据库挂了用户仍能手工把文件捞回来 —— 这个冗余是故意的。
 * 记录量级是"每个文件改动一行", 单会话几千行, 全量扫描足够快。
 */
@Slf4j
@Component
public class WorkspaceJournal {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 同一会话的 append 必须串行, 否则 seq 会乱、行会交错 */
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    /** 会话内自增序号缓存(首次访问时从文件里恢复, 避免重启后重新从 1 开始) */
    private static final ConcurrentHashMap<String, Long> SEQ = new ConcurrentHashMap<>();

    private final WorkspaceProperties properties;

    public WorkspaceJournal(WorkspaceProperties properties) {
        this.properties = properties;
    }

    /** 一条变更记录(只有带 path 的记录才参与回滚) */
    public record Entry(
            long seq,
            String ts,
            String opId,
            String sessionId,
            String type,
            String path,
            boolean existed,
            String beforeSha,
            String afterSha,
            long bytes,
            String status) {
    }

    /**
     * 记录"即将发生"的变更。必须在动文件<b>之前</b>调用。
     *
     * @return opId, 供 {@link #commit} 配对
     */
    public String begin(WorkspaceEntity workspace, String sessionId, String type, String relPath,
                        boolean existed, String beforeSha, String afterSha, long bytes) {
        String opId = "op_" + System.nanoTime();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("seq", nextSeq(workspace, sessionId));
        node.put("ts", Instant.now().toString());
        node.put("op", opId);
        node.put("session", sessionId);
        node.put("type", type);
        node.put("path", relPath);
        node.put("existed", existed);
        node.put("beforeSha", beforeSha);
        node.put("afterSha", afterSha);
        node.put("bytes", bytes);
        node.put("status", "begin");
        append(workspace, sessionId, node);
        return opId;
    }

    /**
     * 标记变更结果。
     *
     * @param status {@code done} 成功 / {@code failed} 失败 / {@code reverted} 已撤回
     */
    public void commit(WorkspaceEntity workspace, String sessionId, String opId, String status) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("seq", nextSeq(workspace, sessionId));
        node.put("ts", Instant.now().toString());
        node.put("op", opId);
        node.put("session", sessionId);
        node.put("status", status);
        append(workspace, sessionId, node);
    }

    /**
     * 撤回完成后的标记: 该轮所有 op 记为 reverted。
     * <p>只追加不修改 —— append-only 的意义在于任何一步崩溃都不会破坏已有历史。
     */
    public void markReverted(WorkspaceEntity workspace, String sessionId, List<String> opIds) {
        for (String opId : opIds) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("seq", nextSeq(workspace, sessionId));
            node.put("ts", Instant.now().toString());
            node.put("op", opId);
            node.put("session", sessionId);
            node.put("status", "reverted");
            append(workspace, sessionId, node);
        }
    }

    /** 读取全部变更记录(按 seq 升序); 文件不存在返回空 */
    public List<Entry> read(WorkspaceEntity workspace, String sessionId) {
        Path file = journalFile(workspace, sessionId);
        if (!Files.exists(file)) return List.of();

        List<Entry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode n = MAPPER.readTree(line);
                    if (!n.hasNonNull("path")) continue; // commit / markReverted 记录, 不参与回滚
                    entries.add(new Entry(
                            n.path("seq").asLong(),
                            n.path("ts").asText(),
                            n.path("op").asText(),
                            n.path("session").asText(),
                            n.path("type").asText(),
                            n.path("path").asText(),
                            n.path("existed").asBoolean(true),
                            n.hasNonNull("beforeSha") ? n.get("beforeSha").asText() : null,
                            n.hasNonNull("afterSha") ? n.get("afterSha").asText() : null,
                            n.path("bytes").asLong(),
                            n.path("status").asText()));
                } catch (Exception e) {
                    // 单行损坏不能静默 —— 读不全的日志会让撤回少回滚一个文件, 比撤回失败更危险
                    log.warn("变更日志存在损坏行, 已跳过: session={}, line={}", sessionId, line);
                }
            }
        } catch (IOException e) {
            throw new BusinessException(500, "读取变更日志失败: " + e.getMessage());
        }
        entries.sort(Comparator.comparingLong(Entry::seq));
        return entries;
    }

    public boolean hasHistory(WorkspaceEntity workspace, String sessionId) {
        return !read(workspace, sessionId).isEmpty();
    }

    public Path journalDir(WorkspaceEntity workspace) {
        return baseDir(workspace).resolve("journal");
    }

    public Path journalFile(WorkspaceEntity workspace, String sessionId) {
        return journalDir(workspace).resolve(safeName(sessionId) + ".jsonl");
    }

    // ===== 内部 =====

    /** 与回收区同根, 便于一起清理; 位置规则与 {@link WorkspaceTrashService#baseDir} 保持一致 */
    private Path baseDir(WorkspaceEntity workspace) {
        if ("workspace".equalsIgnoreCase(properties.getTrashLocation())) {
            return Paths.get(workspace.getRootPath()).resolve(properties.getTrashDir());
        }
        return Paths.get(properties.getDataDir(), "workspace-trash", workspace.getId());
    }

    private void append(WorkspaceEntity workspace, String sessionId, ObjectNode node) {
        Object lock = LOCKS.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            try {
                Path file = journalFile(workspace, sessionId);
                Files.createDirectories(file.getParent());
                Files.writeString(file, MAPPER.writeValueAsString(node) + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                // 日志写不进去就不能继续动文件 —— 否则这次改动永远撤不回来
                throw new BusinessException(500, "写入变更日志失败, 已中止操作: " + e.getMessage());
            }
        }
    }

    /** 会话内自增序号; 首次访问时从既有文件恢复, 避免进程重启后序号回退导致排序错乱 */
    private long nextSeq(WorkspaceEntity workspace, String sessionId) {
        Long cached = SEQ.get(sessionId);
        if (cached != null) {
            return SEQ.compute(sessionId, (k, v) -> v + 1);
        }
        synchronized (LOCKS.computeIfAbsent(sessionId, k -> new Object())) {
            Long again = SEQ.get(sessionId);
            if (again != null) {
                return SEQ.compute(sessionId, (k, v) -> v + 1);
            }
            SEQ.put(sessionId, maxSeqInFile(workspace, sessionId) + 1);
            return SEQ.get(sessionId);
        }
    }

    private long maxSeqInFile(WorkspaceEntity workspace, String sessionId) {
        Path file = journalFile(workspace, sessionId);
        if (!Files.exists(file)) return 0;
        long max = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    max = Math.max(max, MAPPER.readTree(line).path("seq").asLong());
                } catch (Exception ignored) {
                    // 损坏行不参与序号判断
                }
            }
        } catch (IOException e) {
            log.warn("读取变更日志序号失败, 从 1 重新开始: {}", e.getMessage());
        }
        return max;
    }

    /** sessionId 来自数据库, 仍做白名单过滤, 防止异常值拼出越界路径 */
    private static String safeName(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_\\-]{1,64}")) {
            throw new BusinessException(400, "非法的会话 ID: " + sessionId);
        }
        return sessionId;
    }
}
