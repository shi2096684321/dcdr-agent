package com.dcdr.agent.workspace;

import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.repository.WorkspaceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 回收区清理(V3.3 F-49 §6.4.5)。
 * <p>
 * 只删"过期 + 仍无人引用"的 blob。引用计数靠扫日志实现(而不是维护计数器):
 * 计数器一旦漏减就会误删用户的救援副本, 而扫日志最坏只是慢一点。
 * <p>
 * <b>被后续轮次引用的 blob 绝不能删</b> —— 否则 L2 撤回会拿到残缺快照, 表现为"撤回成功但文件内容没了"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.workspace.enabled", havingValue = "true")
public class WorkspaceCleanupTask {

    private static final Pattern SHA = Pattern.compile("\"([0-9a-f]{64})\"");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkspaceProperties properties;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceJournal journal;

    /** 每天凌晨 3:30 清理; cron 可由 agent.workspace.cleanup-cron 覆盖 */
    @Scheduled(cron = "${agent.workspace.cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        int retentionDays = properties.getTrashRetentionDays();
        if (retentionDays <= 0) return;
        Instant deadline = Instant.now().minusSeconds(retentionDays * 86400L);

        List<WorkspaceEntity> workspaces;
        try {
            workspaces = workspaceRepository.findAll();
        } catch (Exception e) {
            log.warn("回收区清理跳过: 读取工作区失败 {}", e.getMessage());
            return;
        }

        for (WorkspaceEntity ws : workspaces) {
            try {
                cleanWorkspace(ws, deadline);
            } catch (Exception e) {
                log.warn("回收区清理失败: workspace={}, err={}", ws.getId(), e.getMessage());
            }
        }

        purgeOrphanWorkspaces(workspaces, deadline);
    }

    /**
     * 清理"工作区记录已被删除、但磁盘数据还在"的残留目录。
     * <p>
     * 不这么做的话, 每删一个工作区就会在 {@code data/workspace-trash/} 下留一个永不回收的目录 ——
     * 用户以为删干净了, 实际上回收区和日志一直躺在硬盘上。删除工作区本身只删数据库记录
     * (设计如此: 磁盘上的东西不归它管), 由这里按保留期兜底。
     * <p>
     * 仅 {@code trashLocation=app-data} 时有统一的根目录; {@code workspace} 模式下回收区散落在
     * 各自的仓库里, 记录一删就再也找不到它们了 —— 这也是默认值选 app-data 的另一个理由。
     */
    private void purgeOrphanWorkspaces(List<WorkspaceEntity> workspaces, Instant deadline) {
        if ("workspace".equalsIgnoreCase(properties.getTrashLocation())) return;

        Path root = Path.of(properties.getDataDir(), "workspace-trash");
        if (!Files.isDirectory(root)) return;

        Set<String> alive = new HashSet<>();
        for (WorkspaceEntity ws : workspaces) alive.add(ws.getId());

        try (Stream<Path> entries = Files.list(root)) {
            for (Path dir : entries.filter(Files::isDirectory).toList()) {
                if (alive.contains(dir.getFileName().toString())) continue;
                if (Files.getLastModifiedTime(dir).toInstant().isAfter(deadline)) continue;
                int removed = deleteRecursively(dir);
                log.info("清理已删除工作区的残留数据: dir={}, 文件 {} 个", dir.getFileName(), removed);
            }
        } catch (IOException e) {
            log.warn("清理残留工作区数据失败: {}", e.getMessage());
        }
    }

    private int deleteRecursively(Path dir) throws IOException {
        int[] count = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                count[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private void cleanWorkspace(WorkspaceEntity workspace, Instant deadline) throws IOException {
        Path blobRoot = baseDir(workspace).resolve("blobs");
        if (!Files.isDirectory(blobRoot)) return;

        Set<String> referenced = collectReferencedShas(workspace);
        int deleted = 0;
        try (Stream<Path> files = Files.walk(blobRoot)) {
            List<Path> blobs = files.filter(Files::isRegularFile).toList();
            for (Path blob : blobs) {
                String sha = blob.getFileName().toString();
                if (referenced.contains(sha)) continue;
                Instant modified = Files.getLastModifiedTime(blob).toInstant();
                if (modified.isAfter(deadline)) continue;
                Files.deleteIfExists(blob);
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("回收区清理完成: workspace={}, 删除 {} 个过期且无引用的快照", workspace.getId(), deleted);
        }
    }

    /**
     * 扫该工作区所有会话日志, 收集"仍可能被撤回用到"的 sha。
     * <p>
     * <b>为什么不能简单地"日志里出现过就算引用"</b>: 每条改动记录都带 beforeSha,
     * 于是所有 blob 恒被引用 → 保留天数形同虚设, 回收区只增不减。
     * 真正还需要保留的只有"这个操作还可能被撤回"的那些:
     * <ul>
     *   <li>{@code reverted} —— 已回滚过, 再撤也会被跳过, 这份 before 不再需要;</li>
     *   <li>{@code failed} —— 当时就没改动文件, 快照是回滚用的, 同样不再需要。</li>
     * </ul>
     * 二者可以通过 {@code agent.workspace.release-reverted-snapshots=true} 释放(默认关)。
     * 关着时行为与"永不删"完全一致 —— 这是保守默认, 确认无副作用后再打开。
     * <p>
     * 解析失败的行无法判断归属, 一律把里面的 sha 当引用: 宁可多留, 也不能删掉还在用的快照。
     */
    private Set<String> collectReferencedShas(WorkspaceEntity workspace) throws IOException {
        Set<String> refs = new HashSet<>();
        Path journalDir = journal.journalDir(workspace);
        if (!Files.isDirectory(journalDir)) return refs;

        boolean releaseReverted = properties.isReleaseRevertedSnapshots();
        try (Stream<Path> files = Files.list(journalDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                collectFromJournal(f, releaseReverted, refs);
            }
        }
        return refs;
    }

    /** 单份日志: 先按 op 归并状态, 再决定哪些 sha 还要留着 */
    private void collectFromJournal(Path file, boolean releaseReverted, Set<String> refs) throws IOException {
        Map<String, String> statusByOp = new HashMap<>();
        Map<String, Set<String>> shasByOp = new LinkedHashMap<>();
        List<String> broken = new ArrayList<>();

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n;
            try {
                n = MAPPER.readTree(line);
            } catch (Exception e) {
                broken.add(line);
                continue;
            }
            String op = n.path("op").asText(null);
            if (op == null) continue;
            if (!n.hasNonNull("path")) {
                // commit / markReverted 短记录: 只贡献状态
                if (n.hasNonNull("status")) statusByOp.put(op, n.get("status").asText());
                continue;
            }
            Set<String> shas = shasByOp.computeIfAbsent(op, k -> new HashSet<>());
            if (n.hasNonNull("beforeSha")) shas.add(n.get("beforeSha").asText());
            if (n.hasNonNull("afterSha")) shas.add(n.get("afterSha").asText());
        }

        for (String line : broken) {
            Matcher m = SHA.matcher(line);
            while (m.find()) refs.add(m.group(1));
        }

        shasByOp.forEach((op, shas) -> {
            String status = statusByOp.getOrDefault(op, "begin");
            if (releaseReverted && ("reverted".equals(status) || "failed".equals(status))) return;
            refs.addAll(shas);
        });
    }

    private Path baseDir(WorkspaceEntity workspace) {
        if ("workspace".equalsIgnoreCase(properties.getTrashLocation())) {
            return Path.of(workspace.getRootPath()).resolve(properties.getTrashDir());
        }
        return Path.of(properties.getDataDir(), "workspace-trash", workspace.getId());
    }
}
