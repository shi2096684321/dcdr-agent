package com.dcdr.agent.tool.result;

import com.dcdr.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * V3.1 F-47.5 工具结果落盘存储。
 * <p>
 * 极端超长的工具结果(默认 &gt; 32KB)写文件, 回灌给模型的只是"路径 + 规模 + 前 N 行",
 * 模型需要全文时用 {@code tool_result_read} 按行分页读取 ——
 * 这样既不会撑爆上下文, 也不至于把信息整体丢弃(硬截断的固有缺陷)。
 * <p>
 * <b>两条安全约束</b>:
 * <ol>
 *   <li>路径必须落在配置的落盘根目录内(防 {@code ../} 路径穿越)</li>
 *   <li>路径必须属于<b>当前会话</b>目录({@link ToolResultContextHolder}), 杜绝跨会话读取他人结果</li>
 * </ol>
 * <b>落点选择</b>: 默认 {@code ./data/tool-results} —— 与既有 {@code ./data} 同为 bind mount,
 * 容器重建不丢, 且宿主机可见(便于排查); 保留天数到期后由落盘时顺带惰性清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResultSpillStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HHmmssSSS", Locale.ROOT);

    /** 文件名序号: 毫秒时间戳在同一毫秒内的两次落盘会互相覆盖, 加序号兜住 */
    private static final AtomicLong SPILL_SEQ = new AtomicLong();

    /** 未取到会话时的兜底目录名(仍可读写, 只是不与其他会话共享) */
    private static final String NO_SESSION = "_nosession";

    private final AgentProperties properties;

    public boolean enabled() {
        return cfg().isEnabled();
    }

    private AgentProperties.SpillConfig cfg() {
        return properties.getTools().getResult().getSpill();
    }

    public Path root() {
        return Paths.get(cfg().getDir()).toAbsolutePath().normalize();
    }

    /**
     * 落盘工具结果全文。
     *
     * @return 相对落盘根目录的路径(如 {@code sess_xxx/web_search-101530123.txt}),
     * 供模型原样传给 {@code tool_result_read}; 失败返回 {@code null}(调用方回退硬截断)
     */
    public String spill(String toolName, String content) {
        try {
            String session = currentSessionDir();
            Path dir = root().resolve(session);
            Files.createDirectories(dir);
            Path file = dir.resolve(safeFileName(toolName) + "-" + LocalDateTime.now().format(TS)
                    + "-" + SPILL_SEQ.incrementAndGet() + ".txt");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            cleanExpired();
            log.info("工具结果已落盘: {} ({} 字符)", session + "/" + file.getFileName(), content.length());
            return session + "/" + file.getFileName();
        } catch (Exception e) {
            log.warn("工具结果落盘失败(回退硬截断): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按行分页读取。
     *
     * @param relativePath  落盘时返回的相对路径
     * @param offset        起始行号(从 1 开始)
     * @param limit         读取行数
     */
    public ReadResult read(String relativePath, int offset, int limit) {
        Path target = resolveSafe(relativePath);
        if (target == null) {
            // 统一文案: 不区分"路径非法"与"属于他人会话", 避免被用来探测其他会话是否存在
            return ReadResult.fail("路径不可读或不属于当前会话: " + relativePath);
        }
        if (!Files.isRegularFile(target)) {
            return ReadResult.fail("文件不存在(可能已过保留期被清理): " + relativePath);
        }
        try {
            List<String> all = Files.readAllLines(target, StandardCharsets.UTF_8);
            int total = all.size();
            int from = Math.max(1, offset);
            int size = Math.max(1, Math.min(limit, cfg().getMaxPageSize()));
            if (from > total) {
                return ReadResult.fail("起始行 " + from + " 超出总行数 " + total);
            }
            int to = Math.min(total, from + size - 1);
            StringBuilder sb = new StringBuilder(1024);
            sb.append("[工具结果分页] ").append(relativePath)
              .append(" 第 ").append(from).append('-').append(to).append(" 行 / 共 ").append(total).append(" 行\n");
            for (int i = from; i <= to; i++) {
                sb.append(all.get(i - 1)).append('\n');
            }
            if (to < total) {
                sb.append("…(仍有 ").append(total - to).append(" 行, 可增大 offset 继续读取)");
            }
            return ReadResult.ok(sb.toString(), total);
        } catch (Exception e) {
            log.warn("工具结果分页读取失败: path={}, {}", relativePath, e.getMessage());
            return ReadResult.fail("读取失败: " + e.getMessage());
        }
    }

    /** 单页读取结果 */
    public record ReadResult(String text, int totalLines, String error) {
        static ReadResult ok(String text, int totalLines) {
            return new ReadResult(text, totalLines, null);
        }

        static ReadResult fail(String error) {
            return new ReadResult(null, 0, error);
        }
    }

    // ===== 内部 =====

    /** 当前会话目录名(非字母数字一律替换, 避免路径注入) */
    private String currentSessionDir() {
        String sessionId = ToolResultContextHolder.sessionId();
        if (sessionId == null || sessionId.isBlank()) return NO_SESSION;
        return sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String safeFileName(String toolName) {
        if (toolName == null || toolName.isBlank()) return "tool";
        return toolName.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** 解析并校验路径: 必须位于落盘根目录内, 且属于当前会话目录 */
    private Path resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        Path root = root();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) return null;
        Path sessionDir = root.resolve(currentSessionDir()).normalize();
        if (!target.startsWith(sessionDir)) return null;
        return target;
    }

    /** 惰性清理超过保留期的落盘文件(只在写入时执行, 不引入定时任务) */
    private void cleanExpired() {
        int days = cfg().getRetainDays();
        if (days <= 0) return;
        try (Stream<Path> paths = Files.walk(root(), 2)) {
            Instant deadline = Instant.now().minus(Duration.ofDays(days));
            paths.filter(Files::isRegularFile)
                 .filter(p -> {
                     try {
                         return Files.getLastModifiedTime(p).toInstant().isBefore(deadline);
                     } catch (Exception e) {
                         return false;
                     }
                 })
                 .forEach(p -> {
                     try {
                         Files.deleteIfExists(p);
                     } catch (Exception ignored) {
                         // 清理失败不影响主链路
                     }
                 });
        } catch (Exception e) {
            log.debug("清理过期工具结果落盘文件失败: {}", e.getMessage());
        }
    }
}
