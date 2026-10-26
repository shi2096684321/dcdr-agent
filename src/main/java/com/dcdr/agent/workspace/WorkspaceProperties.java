package com.dcdr.agent.workspace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 工作区配置(V3.3 F-49)。
 * <p>
 * <b>为什么用 {@code agent.workspace} 而不是 {@code agent-tools.workspace}</b>:
 * 工作区同时被两类消费者使用 —— 工具层(读写删)与 API 层(CRUD/浏览器/绑定会话)。
 * 拆到两个前缀下会让"同一个功能的开关"散落两处, 改配置时容易只改一半,
 * 因此统一收敛到 {@code agent.workspace}。
 * <p>
 * <b>{@link #enabled} 默认 false</b>: 本功能能读用户整台机器的文件, 属于高危能力,
 * 必须由部署方显式开启, 不能因为"引入了这段代码"就自动生效。
 */
@Data
@ConfigurationProperties(prefix = "agent.workspace")
public class WorkspaceProperties {

    /** 功能总开关; 关闭时三个工具不注册、API 返回 503 */
    private boolean enabled = false;

    /**
     * 是否本机运行形态。
     * <p>
     * true: 用户选的目录就是运行机器的真实目录(一期唯一支持的形态)。
     * false: 容器/服务器部署 —— 用户选的其实是服务器的盘, 语义错位, 功能整体禁用。
     */
    private boolean localMode = true;

    /** 是否开放目录枚举接口(仅 admin); 关闭时前端只能手输路径 */
    private boolean browseEnabled = false;

    // ===== 读写限额 =====

    /** 单文件读取上限(字节), 超过直接拒绝 */
    private long maxFileSize = 2L * 1024 * 1024;

    /** 单次写入内容上限(字节) */
    private long maxWriteBytes = 1L * 1024 * 1024;

    /** 单次回灌给模型的字符上限 */
    private int maxReadChars = 8000;

    /** 单次目录遍历条目上限, 超出早停 */
    private int maxEntries = 5000;

    /** 单次删除文件数上限 */
    private int maxDeleteFiles = 100;

    /** 读取超时(秒), 防网络盘挂死 */
    private int readTimeoutSeconds = 5;

    /** 目录遍历时默认忽略的子目录名 */
    private List<String> defaultIgnore = List.of(
            "node_modules", ".git", "target", "dist", "build", ".idea", "__pycache__", ".venv");

    // ===== 回收区 =====

    /**
     * 回收区位置。
     * <p>
     * {@code app-data}(默认): 放应用数据目录, 不污染用户仓库 —— 用户的 {@code git status} 保持干净。
     * {@code workspace}: 放工作区根下, 同卷 move 是 O(1), 但仓库里会多一个目录, 需要 .gitignore。
     */
    private String trashLocation = "app-data";

    /** {@code trashLocation=workspace} 时使用的目录名 */
    private String trashDir = ".workspace-trash";

    /** 应用数据目录(回收区与变更日志的默认落点) */
    private String dataDir = "./data";

    /** 回收区保留天数, 到期后由清理任务真删 */
    private int trashRetentionDays = 30;

    // ===== 敏感路径 =====

    /**
     * 禁止读取的路径模式。
     * <p>匹配规则见 {@link WorkspacePathResolver}: 含 {@code /} 按路径前缀, {@code *.x} 按后缀,
     * 其余按"任一路径段相等"(大小写不敏感)。
     */
    private List<String> denyRead = List.of(
            ".ssh", ".aws", ".gnupg", ".env", "id_rsa", ".pem", ".p12", ".p8",
            ".keystore", ".npmrc", ".pypirc", ".kdbx", "credentials");

    /** 在 {@link #denyRead} 基础上追加禁止写入的路径(.git 内部结构写坏会导致仓库损坏) */
    private List<String> denyWrite = List.of(".git/objects", ".git/refs", ".git/HEAD", ".git/config");

    /** 在 {@link #denyRead} 基础上追加禁止删除的路径 */
    private List<String> denyDelete = List.of(".git");
}
