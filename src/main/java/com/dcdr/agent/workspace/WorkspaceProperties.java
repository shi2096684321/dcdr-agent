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

    /**
     * 是否开放目录枚举接口(仅 admin), 供前端「打开磁盘选目录」的面板使用。
     * <p>
     * 默认开启, 理由是 {@link #localMode} 已经保证了"服务跑在用户自己电脑上" ——
     * 那时枚举目录等于让他自己看自己的盘, 没有新增暴露面; 而关掉它, 用户就只能手打绝对路径,
     * 一个字符错了就得重来。服务器部署形态由 {@code localMode=false} 整体兜住。
     * <p>
     * 需要收紧(例如把本机服务临时暴露到局域网)时置 false, 前端自动回退为手输路径。
     */
    private boolean browseEnabled = true;

    // ===== 读写限额 =====

    /** 单文件读取上限(字节), 超过直接拒绝 */
    private long maxFileSize = 2L * 1024 * 1024;

    /** 单次写入内容上限(字节) */
    private long maxWriteBytes = 1L * 1024 * 1024;

    /** 单次回灌给模型的字符上限 */
    private int maxReadChars = 8000;

    /** 单次目录遍历条目上限, 超出早停 */
    private int maxEntries = 5000;

    /** 搜索时向下遍历的最大层数(再深的仓库属于知识库场景, 不该走实时扫描) */
    private int maxDepth = 12;

    /** 单次删除文件数上限 */
    private int maxDeleteFiles = 100;

    /** 读取超时(秒), 防网络盘挂死 */
    private int readTimeoutSeconds = 5;

    /** 目录遍历时默认忽略的子目录名 */
    private List<String> defaultIgnore = List.of(
            "node_modules", ".git", "target", "dist", "build", ".idea", "__pycache__", ".venv",
            // trashLocation=workspace 时的回收区: 不能让模型看见自己的垃圾桶
            ".workspace-trash");

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

    /**
     * 是否释放"已撤回 / 已失败"操作占用的快照。
     * <p>
     * <b>默认 false</b>, 因为默认行为是"日志里出现过的 sha 一律保留" —— 保守, 但代价是
     * 保留天数形同虚设(每条改动都带 beforeSha, 于是没有任何 blob 会过期)。
     * <p>
     * 打开后: {@code reverted}(已回滚过, 再撤会被跳过) 与 {@code failed}(当时没改文件)
     * 这两类记录不再保护其快照, 回收区才会真正按 {@link #trashRetentionDays} 收缩。
     * <b>风险</b>: 若将来引入"撤回的撤回"或按轮次重算, 需要它时快照可能已被清掉。
     * 建议先在测试库观察一段时间再开启。
     */
    private boolean releaseRevertedSnapshots = false;

    // ===== 敏感路径 =====

    /**
     * 禁止读取的路径模式。
     * <p>匹配规则见 {@link WorkspacePathResolver}: 含 {@code /} 按路径前缀, {@code *.x} 按后缀,
     * 其余按"任一路径段相等"(大小写不敏感)。
     */
    private List<String> denyRead = List.of(
            ".ssh", ".aws", ".gnupg", ".env", "id_rsa", ".pem", ".p12", ".p8",
            ".keystore", ".npmrc", ".pypirc", ".kdbx", "credentials",
            // 回收区本身: trashLocation=workspace 时它就在工作区里, 读它等于把"已删除"的内容再暴露一遍,
            // 删它则直接破坏可撤回性。默认 app-data 位置时这条规则不会命中, 留着无害。
            ".workspace-trash");

    /** 在 {@link #denyRead} 基础上追加禁止写入的路径(.git 内部结构写坏会导致仓库损坏) */
    private List<String> denyWrite = List.of(".git/objects", ".git/refs", ".git/HEAD", ".git/config");

    /** 在 {@link #denyRead} 基础上追加禁止删除的路径 */
    private List<String> denyDelete = List.of(".git");
}
