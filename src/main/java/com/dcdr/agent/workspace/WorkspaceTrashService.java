package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.WorkspaceEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 工作区回收区(V3.3 F-49 §6.3 / §6.4.1) —— <b>内容寻址</b>的文件快照存取。
 * <p>
 * 目录结构:
 * <pre>
 * &lt;base&gt;/blobs/3f/9a/3f9a1c...e7      // sha256 分片, 同名即同内容, 天然去重
 * &lt;base&gt;/journal/sess_xxx.jsonl      // 变更日志(由 WorkspaceJournal 写, 本类不涉及)
 * </pre>
 * <p>
 * <b>为什么内容寻址而不是"每次操作一个目录"</b>: 一轮对话里同一个文件被改 5 次,
 * 只需要保留最初始的那一份内容; 存 5 份副本会让回收区膨胀 5 倍, 撤回时还要逆序回放。
 * <p>
 * <b>为什么 {@link #stash(Path)} 是"移走"而不是"复制"</b>: 同卷 {@code move} 只是 rename,
 * 不搬数据 —— 删一个 10G 的文件也只有算哈希那一次读。跨卷才退化为复制, 且会先复制成功再删原文件。
 * <p>
 * <b>安全</b>: 恢复前重算 blob 的 sha 与文件名比对, 不一致直接拒绝 —— 防的是"日志或备份被篡改后
 * 指向任意文件"。内容寻址天然具备这个校验能力, 顺手用掉。
 */
@Slf4j
@Component
public class WorkspaceTrashService {

    /** store 与 restore 的单文件上限, 与写入限额同源(超过这个量级说明场景不对) */
    private static final long MAX_BLOB_BYTES = 64L * 1024 * 1024;

    private final WorkspaceProperties properties;

    public WorkspaceTrashService(WorkspaceProperties properties) {
        this.properties = properties;
    }

    /**
     * 把文件内容存入回收区, 并从原位置移走。
     * <p>
     * 调用时机固定为"即将覆盖或删除之前": 先落快照, 再动原文件。顺序反了, 中间那一刻原内容就没了。
     *
     * @param file 已解析、已通过校验的真实文件路径
     * @return 内容 sha256(64 位小写十六进制)
     */
    public String stash(WorkspaceEntity workspace, Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(400, "只能快照普通文件: " + file.getFileName());
        }
        long size = sizeOf(file);
        if (size > MAX_BLOB_BYTES) {
            throw new BusinessException(400, "文件过大, 无法建立可撤回快照(" + size + " 字节)");
        }

        String sha = sha256(file);
        Path blob = blobPath(workspace, sha);
        try {
            if (Files.exists(blob, LinkOption.NOFOLLOW_LINKS)) {
                // 同内容已存在: 回收区里已有这份, 原文件直接删掉即可
                Files.delete(file);
                return sha;
            }
            Files.createDirectories(blob.getParent());
            moveOrCopy(file, blob);
            return sha;
        } catch (IOException e) {
            throw new BusinessException(500, "建立快照失败: " + e.getMessage());
        }
    }

    /**
     * 按 sha 把内容恢复到目标路径(覆盖写, 原子替换)。
     *
     * @param sha    快照内容哈希
     * @param target 目标路径(必须已通过 {@link WorkspacePathResolver} 校验)
     */
    public void restore(WorkspaceEntity workspace, String sha, Path target) {
        Path blob = blobPath(workspace, normalizeSha(sha));
        if (!Files.exists(blob, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(404, "快照不存在, 可能已被清理: " + sha);
        }
        // 防篡改: blob 的实际内容必须与文件名哈希一致
        if (!sha256(blob).equalsIgnoreCase(sha)) {
            throw new BusinessException(409, "快照内容校验失败(哈希不匹配), 已拒绝恢复: " + sha);
        }
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = target.resolveSibling(target.getFileName() + "." + System.nanoTime() + ".tmp");
            try {
                Files.copy(blob, tmp, StandardCopyOption.REPLACE_EXISTING);
                atomicReplace(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new BusinessException(500, "恢复快照失败: " + e.getMessage());
        }
    }

    /**
     * 快照是否还在(清理任务可能已删除过期 blob)。
     * <p>
     * 非法 sha 直接抛错而不是返回 false: 传进非法值说明调用方有 bug, 静默吞掉只会让问题更晚暴露。
     */
    public boolean has(WorkspaceEntity workspace, String sha) {
        return Files.exists(blobPath(workspace, normalizeSha(sha)), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 回收区根目录。
     * <p>
     * 默认放应用数据目录而不是工作区: 放工作区等于往用户的仓库里塞垃圾 —— {@code git status} 出噪音、
     * 要维护 .gitignore、IDE 会去索引它。同卷时 {@code move} 仍然是 rename, 代价可接受。
     */
    public Path baseDir(WorkspaceEntity workspace) {
        if ("workspace".equalsIgnoreCase(properties.getTrashLocation())) {
            return Paths.get(workspace.getRootPath()).resolve(properties.getTrashDir());
        }
        return Paths.get(properties.getDataDir(), "workspace-trash", workspace.getId());
    }

    /** blob 实际落点: {@code blobs/ab/cd/abcdef...} */
    public Path blobPath(WorkspaceEntity workspace, String sha) {
        String s = normalizeSha(sha);
        return baseDir(workspace).resolve("blobs").resolve(s.substring(0, 2)).resolve(s.substring(2, 4)).resolve(s);
    }

    // ===== 内部 =====

    private static String normalizeSha(String sha) {
        if (sha == null || !sha.toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) {
            throw new BusinessException(400, "非法的快照 ID: " + sha);
        }
        return sha.toLowerCase(Locale.ROOT);
    }

    /** 计算字节内容的 sha256(写入前算 afterSha 用, 与文件版结果一致) */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(500, "运行环境不支持 SHA-256");
        }
    }

    /** 计算文件 sha256(流式, 不整读进内存) */
    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestIn = new DigestInputStream(in, MessageDigest.getInstance("SHA-256"))) {
            byte[] buf = new byte[8192];
            while (digestIn.read(buf) != -1) {
                // 读空即可, 摘要由 DigestInputStream 累积
            }
            return HexFormat.of().formatHex(digestIn.getMessageDigest().digest());
        } catch (IOException e) {
            throw new BusinessException(500, "读取文件失败: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(500, "运行环境不支持 SHA-256");
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new BusinessException(500, "读取文件大小失败: " + e.getMessage());
        }
    }

    /** 同卷 rename; 跨卷(或目标已存在)退化为"先复制成功再删原文件" */
    private void moveOrCopy(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException e) {
            // AtomicMoveNotSupportedException 是 FileSystemException 的子类, 与跨卷/占用的处理一致:
            // 一律退化为复制, 复制成功后再删原件
            log.debug("移动失败, 退化为复制: {} -> {} ({})", source, target, e.getMessage());
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(source);
    }

    /**
     * 原子替换。Windows 上目标被 IDE / 杀软 / 云盘占用时会抛 {@code EEXIST}/{@code EPERM},
     * 此时退化为"先删后改名" —— 这个分支不写, Windows 用户会遇到间歇性写失败。
     */
    private void atomicReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException e) {
            String reason = e.getReason();
            if (reason == null || !(reason.contains("exists") || reason.contains("permission")
                    || reason.contains("denied"))) {
                throw e;
            }
            Files.deleteIfExists(target);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
