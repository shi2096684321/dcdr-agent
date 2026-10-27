package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.WorkspaceEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工作区路径解析器(V3.3 F-49 §6.1) —— <b>本设计的"逻辑沙箱"</b>。
 * <p>
 * 所有文件访问<b>必须</b>经过本类, 任何工具都不得直接调 {@code Files.*}。
 * 破了这条约束, 下面所有校验都白写。
 * <p>
 * 六道校验(顺序固定):
 * <ol>
 *   <li><b>入参清洗</b>: 空值 / {@code \0} / 绝对路径 / 盘符 / UNC / Windows 保留名</li>
 *   <li><b>拼接归一化</b>: {@code normalize()} 后必须仍在 root 之下(拦 {@code ../})</li>
 *   <li><b>真实路径</b>: {@code toRealPath()} + 逐级软链接校验(上限 32 级, 带环检测)</li>
 *   <li><b>敏感路径 deny</b>: 按 READ / WRITE / DELETE 三套规则, 大小写不敏感</li>
 *   <li><b>授权档位</b>: 调用方意图必须被工作区档位覆盖</li>
 *   <li><b>文件级校验</b>(大小/二进制)由工具层做 —— 与本类解耦, 因为只有 READ 需要</li>
 * </ol>
 * <p>
 * <b>为什么只做 normalize 不够</b>: {@code normalize()} 是纯字符串运算, 不解析链接。
 * Windows 上 {@code mklink /J} 指向 {@code C:\Users} 的目录联接能完全通过第 2 道,
 * 只有真实路径能拦下。这也是为什么第 3 道排在第 4 道之前 —— deny 匹配必须基于真实路径,
 * 否则一个指向 {@code .ssh} 的符号链接就能绕过敏感路径规则。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspacePathResolver {

    /** 软链接/目录联接的最大层数, 超出视为异常(防环、防病态结构) */
    private static final int MAX_SYMLINK_HOPS = 32;

    /** Windows 保留设备名, 作为文件名时打不开且报错晦涩, 入参阶段就拦掉 */
    private static final Set<String> WINDOWS_RESERVED;

    static {
        Set<String> names = new HashSet<>(List.of("CON", "PRN", "AUX", "NUL"));
        for (int i = 1; i <= 9; i++) {
            names.add("COM" + i);
            names.add("LPT" + i);
        }
        WINDOWS_RESERVED = Set.copyOf(names);
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final WorkspaceProperties properties;

    /**
     * 把工作区内的相对路径解析为可安全访问的绝对路径。
     *
     * @param workspace    目标工作区(含 root 与档位)
     * @param relativePath 相对路径; 空/空白表示工作区根目录(仅 READ 允许)
     * @param intent       本次操作意图, 决定 deny 规则与档位校验
     * @return 解析后的真实绝对路径
     * @throws BusinessException 任一校验不通过, message 直接面向用户, 说明具体原因
     */
    public Path resolve(WorkspaceEntity workspace, String relativePath, WorkspaceIntent intent) {
        if (workspace == null) {
            throw new BusinessException(400, "当前会话未绑定工作区, 请先在输入框上方选择要操作的目录");
        }
        if (workspace.getAccessLevel() == null || !workspace.getAccessLevel().allows(intent)) {
            throw new BusinessException(403,
                    "当前工作区为[" + (workspace.getAccessLevel() == null ? "未知" : workspace.getAccessLevel().label())
                            + "]档, 不允许执行 " + intent + " 操作");
        }

        Path root = Paths.get(workspace.getRootPath()).toAbsolutePath().normalize();
        String rel = relativePath == null ? "" : relativePath.trim();

        // 闸1: 入参清洗
        if (rel.isEmpty() || ".".equals(rel) || "/".equals(rel)) {
            if (intent != WorkspaceIntent.READ) {
                throw new BusinessException(400, "不允许对工作区根目录执行 " + intent + " 操作");
            }
            return root;
        }
        validateRawPath(rel);

        // 闸2: 拼接归一化 + 越界检查
        Path target = root.resolve(rel.replace('\\', '/')).normalize();
        if (!isInside(target, root)) {
            throw new BusinessException(403, "路径越界, 拒绝访问: " + rel);
        }

        // 闸3: 真实路径 + 软链接逐级校验
        assertNoSymlinkEscape(target, root);
        Path real = resolveRealPath(target);
        if (!isInside(real, root)) {
            throw new BusinessException(403, "路径经符号链接后越界, 拒绝访问: " + rel);
        }

        // 闸4: 敏感路径(基于真实相对路径匹配, 防止链接指向被禁目录)
        String realRel = relative(real, root);
        assertNotDenied(realRel, intent);

        return real;
    }

    /**
     * 只做 deny 规则匹配, 不做存在性/软链接校验。
     * <p>
     * 供批量操作预检子项使用: 递归删除只在父目录上判 deny 是漏的 ——
     * {@code delete("project", recursive=true)} 会把 {@code project/.git} 一起搬走,
     * 而 {@code .git} 单独是禁止删除的。这类批量场景要先把每个子项都过一遍。
     *
     * @param relPath 相对工作区根的路径(统一用 {@code /})
     */
    public void assertNotDeniedFor(String relPath, WorkspaceIntent intent) {
        assertNotDenied(relPath == null ? "" : relPath.replace('\\', '/'), intent);
    }

    /**
     * 相对路径归一化(统一用 {@code /}, 去掉前导分隔符), 供 deny 匹配与日志使用。
     */
    public String relative(Path target, Path root) {
        String rel = root.relativize(target).toString().replace('\\', '/');
        return rel.startsWith("/") ? rel.substring(1) : rel;
    }

    // ===== 闸1: 入参清洗 =====

    private void validateRawPath(String rel) {
        if (rel.indexOf('\0') >= 0) {
            throw new BusinessException(400, "路径包含非法字符");
        }
        // 盘符: C:\... / C:/...
        if (rel.length() >= 2 && Character.isLetter(rel.charAt(0)) && rel.charAt(1) == ':') {
            throw new BusinessException(403, "只接受工作区内的相对路径, 不接受绝对路径: " + rel);
        }
        if (rel.startsWith("//") || rel.startsWith("\\\\")) {
            throw new BusinessException(403, "不接受 UNC 网络路径: " + rel);
        }
        // 单个前导分隔符: Windows 上 Paths.get("/etc/passwd") 不是 absolute(root-relative),
        // 若不在这里拦, 它会一路走到 resolve 后变成"越界", 报错含义就不准了
        if (rel.startsWith("/") || rel.startsWith("\\")) {
            throw new BusinessException(403, "只接受工作区内的相对路径, 不接受绝对路径: " + rel);
        }
        // 绝对路径(java.nio 判定, 覆盖 / 开头)
        try {
            if (Paths.get(rel).isAbsolute()) {
                throw new BusinessException(403, "只接受工作区内的相对路径, 不接受绝对路径: " + rel);
            }
        } catch (java.nio.file.InvalidPathException e) {
            throw new BusinessException(400, "路径格式非法: " + rel);
        }

        for (String segment : rel.split("[\\\\/]")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) continue;
            int dot = segment.indexOf('.');
            String base = (dot > 0 ? segment.substring(0, dot) : segment).toUpperCase(Locale.ROOT);
            if (WINDOWS_RESERVED.contains(base)) {
                throw new BusinessException(400, "路径包含系统保留名, 拒绝访问: " + segment);
            }
        }
    }

    // ===== 闸3: 真实路径 + 软链接 =====

    /**
     * 逐级检查从 root 到 target 的每一层: 只要某层是符号链接, 其<b>直接目标</b>就必须仍在 root 内。
     * <p>
     * 与"只对最终路径调一次 {@code toRealPath}"的区别: 链式链接(A→B→C, 中间跳出再跳回)时,
     * 最终落点可能在 root 内而中间已穿越了外部目录。逐级校验把这种情形一并拦下。
     */
    private void assertNoSymlinkEscape(Path target, Path root) {
        Set<Path> visited = new HashSet<>();
        Path cur = target;
        int hops = 0;
        while (cur != null && !cur.equals(root)) {
            if (++hops > MAX_SYMLINK_HOPS) {
                throw new BusinessException(400, "路径层级过深, 可能存在循环符号链接: " + target);
            }
            if (!visited.add(cur.normalize())) {
                throw new BusinessException(400, "检测到符号链接环: " + cur);
            }
            if (Files.isSymbolicLink(cur)) {
                try {
                    Path link = Files.readSymbolicLink(cur);
                    Path resolved = (link.isAbsolute() ? link : cur.getParent().resolve(link))
                            .toAbsolutePath().normalize();
                    if (!isInside(resolved, root)) {
                        throw new BusinessException(403, "符号链接指向工作区外, 拒绝访问: "
                                + root.relativize(target));
                    }
                } catch (IOException e) {
                    throw new BusinessException(400, "无法解析符号链接: " + cur);
                }
            }
            cur = cur.getParent();
        }
    }

    /**
     * 解析真实路径; 目标尚不存在(新建文件)时, 找到最近的已存在祖先解析后再拼回尾部。
     * <p>
     * 新建场景不能直接 {@code toRealPath()} —— 会抛 {@code NoSuchFileException}。
     */
    private Path resolveRealPath(Path target) {
        Path cur = target;
        List<String> tail = new ArrayList<>();
        while (cur != null) {
            if (Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Path real = cur.toRealPath();
                    for (int i = tail.size() - 1; i >= 0; i--) {
                        real = real.resolve(tail.get(i));
                    }
                    return real;
                } catch (IOException e) {
                    throw new BusinessException(400, "无法解析路径: " + e.getMessage());
                }
            }
            Path name = cur.getFileName();
            if (name != null) tail.add(name.toString());
            cur = cur.getParent();
        }
        return target;
    }

    // ===== 闸4: 敏感路径 =====

    private void assertNotDenied(String realRelative, WorkspaceIntent intent) {
        List<String> rules = new ArrayList<>(properties.getDenyRead());
        if (intent == WorkspaceIntent.WRITE) rules.addAll(properties.getDenyWrite());
        if (intent == WorkspaceIntent.DELETE) rules.addAll(properties.getDenyDelete());

        String lower = realRelative.toLowerCase(Locale.ROOT);
        String[] segments = lower.split("/");
        for (String raw : rules) {
            if (raw == null || raw.isBlank()) continue;
            String rule = raw.trim().toLowerCase(Locale.ROOT);
            if (matchesRule(lower, segments, rule)) {
                throw new BusinessException(403, "该路径被安全策略禁止访问(规则: " + raw.trim() + "): " + realRelative);
            }
        }
    }

    /**
     * 规则形态(大小写不敏感):
     * <ul>
     *   <li>含 {@code /}: 按路径匹配 —— 相等、前缀、中间段、结尾段都算命中({@code .git/objects} 任意层级生效)</li>
     *   <li>{@code *.ext}: 后缀匹配</li>
     *   <li>其余: 任一路径段完全相等({@code .env} 命中 {@code config/.env} 但不命中 {@code .env.local})</li>
     * </ul>
     */
    private boolean matchesRule(String lowerPath, String[] segments, String rule) {
        if (rule.contains("/")) {
            return lowerPath.equals(rule)
                    || lowerPath.startsWith(rule + "/")
                    || lowerPath.endsWith("/" + rule)
                    || lowerPath.contains("/" + rule + "/");
        }
        for (String segment : segments) {
            if (rule.startsWith("*.")) {
                if (segment.endsWith(rule.substring(1))) return true;
            } else if (segment.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    // ===== 工具 =====

    /**
     * 是否在 root 之下(含相等)。Windows 文件系统大小写不敏感, 这里做大小写归一后比较,
     * 否则 {@code C:\Work} 与 {@code c:\work\a.txt} 会被判为越界。
     */
    private static boolean isInside(Path p, Path root) {
        if (WINDOWS) {
            String a = p.toString().toLowerCase(Locale.ROOT);
            String b = root.toString().toLowerCase(Locale.ROOT);
            if (a.equals(b)) return true;
            String prefix = b.endsWith("\\") || b.endsWith("/") ? b : b + java.io.File.separator;
            return a.startsWith(prefix);
        }
        return p.equals(root) || p.startsWith(root);
    }
}
