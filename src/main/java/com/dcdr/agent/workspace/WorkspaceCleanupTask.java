package com.dcdr.agent.workspace;

import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.repository.WorkspaceRepository;
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
import java.util.HashSet;
import java.util.List;
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

    /** 扫该工作区所有会话日志, 收集仍被引用的 sha */
    private Set<String> collectReferencedShas(WorkspaceEntity workspace) throws IOException {
        Set<String> refs = new HashSet<>();
        Path journalDir = journal.journalDir(workspace);
        if (!Files.isDirectory(journalDir)) return refs;
        try (Stream<Path> files = Files.list(journalDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = SHA.matcher(text);
                while (m.find()) {
                    refs.add(m.group(1));
                }
            }
        }
        return refs;
    }

    private Path baseDir(WorkspaceEntity workspace) {
        if ("workspace".equalsIgnoreCase(properties.getTrashLocation())) {
            return Path.of(workspace.getRootPath()).resolve(properties.getTrashDir());
        }
        return Path.of(properties.getDataDir(), "workspace-trash", workspace.getId());
    }
}
