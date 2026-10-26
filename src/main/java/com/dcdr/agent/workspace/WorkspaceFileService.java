package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.WorkspaceEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工作区文件读写删的实际执行层(V3.3 F-49 §6.2 / §6.3)。
 * <p>
 * 职责划分:
 * <ul>
 *   <li>{@link WorkspacePathResolver} —— 能不能碰(安全边界)</li>
 *   <li><b>本类</b> —— 怎么碰(编码、换行、原子写、快照、日志)</li>
 *   <li>工具类 —— 参数解析与结果文案</li>
 * </ul>
 * <p>
 * <b>写入铁律(顺序不可换)</b>: 新鲜度校验 → 编码/换行探测 → 内容转字节 → 原文件入 blob →
 * 写日志 begin → 原子写 → 写日志 done。顺序反了, 崩溃时就可能既没快照也没日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceFileService {

    /** 读取时用于识别二进制的探测窗口 */
    private static final int BINARY_PROBE_BYTES = 8192;

    private final WorkspaceProperties properties;
    private final WorkspacePathResolver resolver;
    private final WorkspaceTrashService trash;
    private final WorkspaceJournal journal;

    /** 读取登记: 绝对路径 → (大小, 修改时间)。写入前比对, 发现外部改动即拒绝, 防覆盖用户手改 */
    private final Map<String, FileStamp> readStamps = new ConcurrentHashMap<>();

    private record FileStamp(long size, long modified) {
    }

    // ===== 读 =====

    /** 列目录。只返回结构, 不返回内容 */
    public String list(WorkspaceEntity workspace, String relPath, Integer depth) {
        Path dir = resolver.resolve(workspace, relPath, WorkspaceIntent.READ);
        if (!Files.isDirectory(dir)) {
            throw new BusinessException(400, "不是目录: " + relPath);
        }
        int maxDepth = depth == null ? 1 : Math.max(1, Math.min(depth, 3));
        int maxEntries = properties.getMaxEntries();
        Set<String> ignored = new HashSet<>(properties.getDefaultIgnore());

        List<String> lines = new ArrayList<>();
        int[] counter = {0};
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), maxDepth + 1,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                            if (!d.equals(dir) && ignored.contains(d.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                            if (counter[0] >= maxEntries) {
                                truncated[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            String rel = dir.relativize(f).toString().replace('\\', '/');
                            lines.add("f  " + rel + (Files.isDirectory(f) ? "/" : "")
                                    + "  (" + humanSize(attrs.size()) + ")");
                            counter[0]++;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                            if (!d.equals(dir) && !Objects.equals(d.getFileName().toString(), "..")) {
                                String rel = dir.relativize(d).toString().replace('\\', '/');
                                lines.add("d  " + rel + "/");
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new BusinessException(500, "列目录失败: " + e.getMessage());
        }

        String head = "[workspace_list] " + (relPath == null || relPath.isBlank() ? "." : relPath)
                + "  (" + counter[0] + " 项" + (truncated[0] ? ", 已达上限 " + maxEntries + ", 请指定子目录" : "") + ")";
        return head + "\n" + String.join("\n", lines);
    }

    /** 按行读文本 */
    public String read(WorkspaceEntity workspace, String relPath, Integer offset, Integer limit) {
        Path file = resolver.resolve(workspace, relPath, WorkspaceIntent.READ);
        return readPath(file, relPath, offset, limit);
    }

    /** 读取已解析路径(撤回后的回显也复用它) */
    public String readPath(Path file, String displayName, Integer offset, Integer limit) {
        if (!Files.exists(file)) {
            throw new BusinessException(404, "文件不存在: " + displayName);
        }
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(400, "不是普通文件: " + displayName);
        }
        long size = sizeOf(file);
        if (size > properties.getMaxFileSize()) {
            throw new BusinessException(400, "文件过大(" + humanSize(size) + "), 上限 "
                    + humanSize(properties.getMaxFileSize()));
        }
        if (looksBinary(file)) {
            throw new BusinessException(400, "这是二进制文件, 不提供文本读取: " + displayName);
        }

        Decoded d = decode(readAll(file));
        String[] all = d.text().split("\n", -1);
        int total = all.length;
        int start = offset == null ? 1 : Math.max(1, offset);
        int count = limit == null ? 200 : Math.max(1, Math.min(limit, 1000));
        int end = Math.min(total, start - 1 + count);

        StringBuilder sb = new StringBuilder();
        sb.append("[workspace_read] ").append(displayName)
                .append("  (第 ").append(start).append("-").append(Math.max(start, end))
                .append(" 行 / 共 ").append(total).append(" 行)")
                .append(d.charsetNote()).append('\n');

        int charBudget = properties.getMaxReadChars();
        int used = sb.length();
        int lastLine = start - 1;
        for (int i = start - 1; i < end; i++) {
            String line = String.format("%6d→ %s", i + 1, all[i]);
            if (used + line.length() > charBudget) break;
            sb.append(line).append('\n');
            used += line.length() + 1;
            lastLine = i + 1;
        }
        if (lastLine < total) {
            sb.append("[还有 ").append(total - lastLine).append(" 行未显示, 用 offset=")
                    .append(lastLine + 1).append(" 继续]");
        }
        stamp(file);
        return sb.toString();
    }

    /** 按正则搜索文件内容 */
    public String search(WorkspaceEntity workspace, String pattern, String relPath, String glob) {
        if (pattern == null || pattern.isBlank()) {
            throw new BusinessException(400, "请提供要搜索的 pattern(正则表达式)");
        }
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new BusinessException(400, "正则表达式非法: " + e.getMessage());
        }
        Path base = resolver.resolve(workspace, relPath, WorkspaceIntent.READ);
        if (!Files.isDirectory(base)) {
            throw new BusinessException(400, "搜索起点必须是目录: " + relPath);
        }

        int maxResults = 30;
        int maxEntries = properties.getMaxEntries();
        Set<String> ignored = new HashSet<>(properties.getDefaultIgnore());
        List<String> hits = new ArrayList<>();
        int[] scanned = {0};
        boolean[] truncated = {false};
        long maxFile = properties.getMaxFileSize();

        try {
            Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), properties.getMaxDepth(),
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                            if (!d.equals(base) && ignored.contains(d.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                            if (hits.size() >= maxResults || scanned[0] >= maxEntries) {
                                truncated[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            String name = f.getFileName().toString();
                            if (glob != null && !glob.isBlank() && !matchGlob(name, glob)) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (attrs.size() > maxFile || looksBinary(f)) {
                                return FileVisitResult.CONTINUE;
                            }
                            scanned[0]++;
                            try {
                                Decoded d = decode(readAll(f));
                                String[] lines = d.text().split("\n", -1);
                                String rel = base.relativize(f).toString().replace('\\', '/');
                                for (int i = 0; i < lines.length && hits.size() < maxResults; i++) {
                                    if (regex.matcher(lines[i]).find()) {
                                        String shown = lines[i].length() > 300
                                                ? lines[i].substring(0, 300) + "…" : lines[i];
                                        hits.add(rel + ":" + (i + 1) + ": " + shown.trim());
                                    }
                                }
                            } catch (Exception e) {
                                // 单个文件读失败不影响整体搜索
                                log.debug("搜索跳过文件 {}: {}", f, e.getMessage());
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new BusinessException(500, "搜索失败: " + e.getMessage());
        }

        if (hits.isEmpty()) {
            return "[workspace_search] 未找到匹配: " + pattern + "  (扫描 " + scanned[0] + " 个文件)";
        }
        return "[workspace_search] " + pattern + "  命中 " + hits.size() + " 处"
                + (truncated[0] ? " (已达上限, 请缩小范围)" : "") + "\n" + String.join("\n", hits);
    }

    // ===== 写 =====

    /** 新建或整体覆盖 */
    public String write(WorkspaceEntity workspace, String sessionId, String relPath,
                        String content, boolean overwrite) {
        if (content == null) {
            throw new BusinessException(400, "请提供 content");
        }
        Path target = resolver.resolve(workspace, relPath, WorkspaceIntent.WRITE);
        boolean existed = Files.exists(target);
        if (existed && !overwrite) {
            throw new BusinessException(409,
                    "文件已存在: " + relPath + "。如需整体覆盖请显式传 overwrite=true; 只改几行请用 workspace_edit");
        }
        if (existed && !Files.isRegularFile(target)) {
            throw new BusinessException(400, "目标不是普通文件: " + relPath);
        }
        if (existed) {
            assertNotExternallyModified(target, relPath);
        }

        Decoded old = existed ? decode(readAll(target)) : Decoded.freshUtf8();
        byte[] bytes = encodeWith(content, old);
        if (bytes.length > properties.getMaxWriteBytes()) {
            throw new BusinessException(400, "内容过大(" + humanSize(bytes.length) + "), 上限 "
                    + humanSize(properties.getMaxWriteBytes()));
        }

        commitWrite(workspace, sessionId, target, relPath, existed, bytes, old);
        stamp(target);
        return "[workspace_write] " + relPath + (existed ? " 已覆盖" : " 已创建")
                + "  (" + countLines(content) + " 行, " + humanSize(bytes.length) + ")"
                + "\n提示: 修改前的内容已进回收区, 可在界面上点「撤销」回滚。";
    }

    /**
     * 精确替换片段。
     * <p>
     * {@code oldString} 必须在文件内<b>唯一</b>, 出现多次时拒绝并提示扩大上下文 ——
     * 让模型自己挑一处替换是丢代码的主要来源之一。
     */
    public String edit(WorkspaceEntity workspace, String sessionId, String relPath,
                       String oldString, String newString, boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            throw new BusinessException(400, "old_string 不能为空; 新建文件请用 workspace_write");
        }
        if (newString == null) {
            throw new BusinessException(400, "请提供 new_string(删除片段可传空串)");
        }
        Path target = resolver.resolve(workspace, relPath, WorkspaceIntent.WRITE);
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new BusinessException(404, "文件不存在: " + relPath);
        }
        if (sizeOf(target) > properties.getMaxFileSize()) {
            throw new BusinessException(400, "文件过大, 不支持整文件替换");
        }
        if (looksBinary(target)) {
            throw new BusinessException(400, "这是二进制文件, 不支持文本编辑: " + relPath);
        }
        assertNotExternallyModified(target, relPath);

        Decoded old = decode(readAll(target));
        // 换行统一: 文件是 CRLF 时, 模型给的换行也要按 CRLF 去匹配, 否则"看起来一样却匹配不上"
        String haystack = normalizeLineEndings(old.text(), "\n");
        String needle = normalizeLineEndings(oldString, "\n");
        String replacement = normalizeLineEndings(newString, "\n");

        int occurrences = countOccurrences(haystack, needle);
        if (occurrences == 0) {
            throw new BusinessException(400,
                    "未找到要替换的内容。请先 workspace_read 确认实际文本(注意缩进与空白)。");
        }
        if (occurrences > 1 && !replaceAll) {
            throw new BusinessException(409,
                    "old_string 在文件中出现 " + occurrences + " 次, 无法确定替换哪一处。"
                            + "请提供更多上下文让它唯一, 或显式传 replace_all=true 全部替换。");
        }

        String updated = replaceAll
                ? haystack.replace(needle, replacement)
                : replaceFirst(haystack, needle, replacement);
        byte[] bytes = encodeWith(updated, old);
        if (bytes.length > properties.getMaxWriteBytes()) {
            throw new BusinessException(400, "修改后内容过大, 超过单次写入上限");
        }

        String beforeSha = trash.stash(workspace, target);
        String opId = journal.begin(workspace, sessionId, "EDIT", relPath, true, beforeSha,
                WorkspaceTrashService.sha256(bytes), bytes.length);
        try {
            atomicWrite(target, bytes);
            journal.commit(workspace, sessionId, opId, "done");
        } catch (IOException e) {
            restoreOrWarn(workspace, target, beforeSha, relPath);
            journal.commit(workspace, sessionId, opId, "failed");
            throw new BusinessException(500, "写入失败, 已尝试回滚: " + e.getMessage());
        }
        stamp(target);

        int replaced = replaceAll ? occurrences : 1;
        return "[workspace_edit] " + relPath + " 已修改 (替换 " + replaced + " 处)\n"
                + diffSnippet(needle, replacement)
                + "\n提示: 修改前的内容已进回收区, 可在界面上点「撤销」回滚。";
    }

    // ===== 删 =====

    /**
     * 删除(移入回收区, <b>永不真删</b>)。
     * <p>
     * 五条硬限制: 禁删根目录 / 目录必须 recursive / 单次文件数上限 / deny 路径 / 回收区自身。
     */
    public String delete(WorkspaceEntity workspace, String sessionId, String relPath, boolean recursive) {
        Path target = resolver.resolve(workspace, relPath, WorkspaceIntent.DELETE);
        if (!Files.exists(target)) {
            throw new BusinessException(404, "路径不存在: " + relPath);
        }
        boolean isDir = Files.isDirectory(target);
        int maxFiles = properties.getMaxDeleteFiles();

        if (!isDir) {
            String sha = trash.stash(workspace, target);
            String opId = journal.begin(workspace, sessionId, "DELETE", relPath, true, sha, null, 0);
            journal.commit(workspace, sessionId, opId, "done");
            return "[workspace_delete] " + relPath + " 已移入回收区(未真删)\n"
                    + "提示: 可在界面上点「撤销」恢复。";
        }

        if (!recursive) {
            throw new BusinessException(400,
                    "目标是目录, 需要显式传 recursive=true 才会递归删除: " + relPath);
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    files.add(f);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new BusinessException(500, "统计目录文件失败: " + e.getMessage());
        }
        if (files.size() > maxFiles) {
            throw new BusinessException(400,
                    "该目录包含 " + files.size() + " 个文件, 超过单次删除上限 " + maxFiles
                            + "。请分批删除, 或让用户手动处理。");
        }

        int deleted = 0;
        for (Path f : files) {
            String rel = resolver.relative(f, Path.of(workspace.getRootPath()));
            String sha = trash.stash(workspace, f);
            String opId = journal.begin(workspace, sessionId, "DELETE", rel, true, sha, null, 0);
            journal.commit(workspace, sessionId, opId, "done");
            deleted++;
        }
        // 文件移走后删除空目录(由深到浅)
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                        // 已被 stash 移走
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("清理空目录失败(文件已进回收区): {}", e.getMessage());
        }

        return "[workspace_delete] " + relPath + " 已递归删除 " + deleted + " 个文件(全部进回收区, 未真删)\n"
                + "提示: 可在界面上点「撤销」整体恢复。";
    }

    // ===== 恢复(供撤回使用) =====

    /** 把某个路径恢复成指定快照; existed=false 表示原本不存在, 恢复即"删掉它" */
    public void restore(WorkspaceEntity workspace, String relPath, boolean existed, String sha) {
        Path target = resolver.resolve(workspace, relPath, WorkspaceIntent.WRITE);
        if (!existed) {
            // 这一轮里新建的文件: 撤回等于把它移走(仍进回收区, 保证"撤回本身也可撤回")
            if (Files.exists(target) && Files.isRegularFile(target)) {
                trash.stash(workspace, target);
            }
            return;
        }
        trash.restore(workspace, sha, target);
    }

    // ===== 内部: 写入 =====

    private void commitWrite(WorkspaceEntity workspace, String sessionId, Path target, String relPath,
                             boolean existed, byte[] bytes, Decoded old) {
        String beforeSha = existed ? trash.stash(workspace, target) : null;
        String opId = journal.begin(workspace, sessionId, existed ? "MODIFY" : "CREATE", relPath,
                existed, beforeSha, WorkspaceTrashService.sha256(bytes), bytes.length);
        try {
            atomicWrite(target, bytes);
            journal.commit(workspace, sessionId, opId, "done");
        } catch (IOException e) {
            if (existed) restoreOrWarn(workspace, target, beforeSha, relPath);
            journal.commit(workspace, sessionId, opId, "failed");
            throw new BusinessException(500, "写入失败, 已尝试回滚: " + e.getMessage());
        }
    }

    private void restoreOrWarn(WorkspaceEntity workspace, Path target, String sha, String relPath) {
        if (sha == null) return;
        try {
            trash.restore(workspace, sha, target);
            log.warn("写入失败, 已按快照回滚: {}", relPath);
        } catch (Exception e) {
            log.error("写入失败且回滚失败, 内容仍可从回收区手工恢复: path={}, sha={}", relPath, sha, e);
        }
    }

    /** 原子写: 同目录 tmp → 替换; Windows 上目标被占用时退化。注释见 WorkspaceTrashService#atomicReplace */
    private void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + "." + System.nanoTime() + ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileSystemException e) {
                Files.deleteIfExists(target);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 新鲜度校验: 模型读到的是旧版本、用户在 IDE 里改过之后再写, 会静默覆盖用户手改。
     * 这里比对读取时登记的 (size, mtime), 不一致直接拒绝并让模型重读。
     */
    private void assertNotExternallyModified(Path target, String relPath) {
        FileStamp known = readStamps.get(key(target));
        if (known == null) return; // 没读过就不判定(例如模型直接写新文件)
        FileStamp now = currentStamp(target);
        if (now == null) return;
        if (known.size() != now.size || known.modified != now.modified) {
            throw new BusinessException(409,
                    "文件在读取后被外部修改过(可能你在编辑器里改了它)。请重新 workspace_read "
                            + relPath + " 再决定怎么改。");
        }
    }

    private void stamp(Path target) {
        FileStamp s = currentStamp(target);
        if (s != null) readStamps.put(key(target), s);
    }

    private FileStamp currentStamp(Path target) {
        try {
            return new FileStamp(Files.size(target), Files.getLastModifiedTime(target).toMillis());
        } catch (IOException e) {
            return null;
        }
    }

    private static String key(Path p) {
        return p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    // ===== 内部: 文本编解码 =====

    /** 解码结果: 文本 + 原编码 + 是否带 BOM + 原换行风格 */
    private record Decoded(String text, Charset charset, boolean bom, String lineEnding) {

        static Decoded freshUtf8() {
            return new Decoded("", StandardCharsets.UTF_8, false, "\n");
        }

        String charsetNote() {
            if (StandardCharsets.UTF_8.equals(charset) && !bom) return "";
            return "  [" + charset.name() + (bom ? " +BOM" : "") + "]";
        }
    }

    /**
     * 解码策略: 严格 UTF-8 → 非法字节降级 GBK → 再失败 GB18030。
     * <p>
     * 中文 Windows 上 GBK 文件极常见, 硬用 UTF-8 会得到乱码或抛异常; 而写回时必须沿用原编码,
     * 否则整个文件的中文会变成乱码 —— 这是最容易踩且最难察觉的一处。
     */
    private Decoded decode(byte[] bytes) {
        if (bytes.length == 0) return Decoded.freshUtf8();

        boolean bom = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
        int offset = bom ? 3 : 0;

        Charset charset = StandardCharsets.UTF_8;
        String text;
        try {
            text = strictDecode(bytes, offset, StandardCharsets.UTF_8);
        } catch (CharacterCodingException utf8Failed) {
            try {
                Charset gbk = Charset.forName("GBK");
                text = strictDecode(bytes, 0, gbk);
                charset = gbk;
                bom = false;
            } catch (CharacterCodingException | java.nio.charset.UnsupportedCharsetException gbkFailed) {
                Charset gb18030 = Charset.forName("GB18030");
                text = new String(bytes, gb18030);
                charset = gb18030;
                bom = false;
            }
        }
        return new Decoded(text, charset, bom, detectLineEnding(text));
    }

    private static String strictDecode(byte[] bytes, int offset, Charset charset)
            throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                .toString();
    }

    private static String detectLineEnding(String text) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') crlf++;
                else lf++;
            }
        }
        if (crlf == 0) return "\n";
        if (lf == 0) return "\r\n";
        return crlf >= lf ? "\r\n" : "\n"; // 混合时按多数, 避免整文件被改写
    }

    private static String normalizeLineEndings(String text, String target) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        return "\r\n".equals(target) ? normalized.replace("\n", "\r\n") : normalized;
    }

    private static byte[] encodeWith(String content, Decoded template) {
        String text = normalizeLineEndings(content, template.lineEnding());
        byte[] body = text.getBytes(template.charset());
        if (!template.bom()) return body;
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        return withBom;
    }

    // ===== 内部: 杂项 =====

    private boolean looksBinary(Path file) {
        byte[] probe = new byte[BINARY_PROBE_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(probe);
            for (int i = 0; i < read; i++) {
                if (probe[i] == 0) return true;
            }
            return false;
        } catch (IOException e) {
            return true; // 读不了就当二进制处理, 不冒险
        }
    }

    private byte[] readAll(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BusinessException(500, "读取文件失败: " + e.getMessage());
        }
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new BusinessException(500, "读取文件大小失败: " + e.getMessage());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String replaceFirst(String haystack, String needle, String replacement) {
        int idx = haystack.indexOf(needle);
        return idx < 0 ? haystack : haystack.substring(0, idx) + replacement + haystack.substring(idx + needle.length());
    }

    private static int countLines(String text) {
        return text.isEmpty() ? 0 : text.split("\n", -1).length;
    }

    private static boolean matchGlob(String name, String glob) {
        String regex = glob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return name.matches("(?i)" + regex);
    }

    /** 变更摘要: 只回前若干行, 不回全文(全文在磁盘上, 需要可再 read) */
    private static String diffSnippet(String oldText, String newText) {
        List<String> oldLines = Arrays.asList(oldText.split("\n", -1));
        List<String> newLines = Arrays.asList(newText.split("\n", -1));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String l : oldLines) {
            if (shown >= 10) break;
            sb.append("  - ").append(l).append('\n');
            shown++;
        }
        shown = 0;
        for (String l : newLines) {
            if (shown >= 10) break;
            sb.append("  + ").append(l).append('\n');
            shown++;
        }
        return sb.toString();
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
