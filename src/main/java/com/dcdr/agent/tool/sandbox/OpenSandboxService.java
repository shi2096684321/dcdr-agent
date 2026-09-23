package com.dcdr.agent.tool.sandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;
import com.dcdr.agent.config.ToolsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * OpenSandbox 执行服务(V3.1 F-43) —— 与沙箱中间件交互的<b>唯一出口</b>。
 * <p>
 * <b>架构</b>: 本类只连 opensandbox-server 的 HTTP, <b>不持有 docker.sock</b>;
 * 沙箱的隔离(独立容器 / 禁网 / 资源配额 / 非 root / 单命令超时)由服务端实施 ——
 * 这正是选择"沙箱即服务"而非"自研 docker CLI"的核心收益。
 * <p>
 * <b>生命周期</b>: 每次执行 = 创建沙箱 → 写入代码 → 执行 → 取输出 → <b>显式销毁</b>。
 * 无状态、无挂载、无复用, 与前文场景收敛结论(纯计算)一致。
 * <p>
 * <b>并发</b>: 全局信号量按 {@code maxConcurrent} 限流, 争不到直接拒绝而非排队 ——
 * 避免一次对话把宿主资源打满, 也避免请求在队列里堆积导致雪崩。
 *
 * @see CodeInterpreterTool
 */
@Slf4j
@Service
public class OpenSandboxService {

    /** 沙箱内代码文件路径(容器内可写临时区) */
    private static final String CODE_PATH = "/tmp/dcdr_code.py";

    /** 执行命令(直接参数形式, 不经 shell, 避免命令注入) */
    private static final String RUN_COMMAND = "python -I -B " + CODE_PATH;

    /** 沙箱内以非 root 身份执行(65534 = nobody) */
    private static final int SANDBOX_UID = 65534;

    /** 沙箱内以非 root 组执行 */
    private static final int SANDBOX_GID = 65534;

    /** 代码文件权限 rw-r--r-- */
    private static final int CODE_FILE_MODE = 644;

    /** 连接超时冗余(秒): 覆盖沙箱创建 + 就绪等待 + 执行 */
    private static final int CONNECTION_TIMEOUT_MARGIN_SECONDS = 30;

    private final ToolsProperties.SandboxConfig config;

    /** 全局并发闸 */
    private final Semaphore concurrency;

    public OpenSandboxService(ToolsProperties toolsProperties) {
        this.config = toolsProperties.getSandbox();
        this.concurrency = new Semaphore(Math.max(1, config.getMaxConcurrent()));
    }

    /**
     * 单次沙箱执行结果。
     *
     * @param output      stdout(+ stderr) 文本, 失败/拒绝时为 null
     * @param exitCode    进程退出码, 未知为 null
     * @param durationMs  端到端耗时(含沙箱创建与销毁)
     * @param ok          是否正常结束(退出码 0)
     * @param rejected    是否因并发闸被拒(未真正执行)
     * @param message     失败原因(供审计与排查), 正常为 null
     */
    public record SandboxExecResult(String output, Integer exitCode, long durationMs,
                                    boolean ok, boolean rejected, String message) {

        static SandboxExecResult rejected(String message) {
            return new SandboxExecResult(null, null, 0, false, true, message);
        }

        static SandboxExecResult failure(String message, long durationMs) {
            return new SandboxExecResult(null, null, durationMs, false, false, message);
        }
    }

    /**
     * 在沙箱中执行一段 Python 代码。
     * <p>
     * 本方法不抛异常: 所有失败都收敛为 {@link SandboxExecResult#message()}, 由调用方决定如何回灌模型。
     */
    public SandboxExecResult execute(String code) {
        if (!concurrency.tryAcquire()) {
            log.warn("沙箱并发已达上限({}), 本次执行被拒绝", config.getMaxConcurrent());
            return SandboxExecResult.rejected(
                    "沙箱繁忙(并发上限 " + config.getMaxConcurrent() + "), 请稍后重试");
        }

        long startNanos = System.nanoTime();
        Sandbox sandbox = null;
        try {
            sandbox = createSandbox();
            writeCode(sandbox, code);
            Execution execution = runCode(sandbox);
            long durationMs = elapsedMs(startNanos);
            SandboxExecResult result = toResult(execution, durationMs);
            log.info("沙箱执行完成: exitCode={}, 耗时 {}ms, 输出 {} 字符",
                    result.exitCode(), durationMs,
                    result.output() == null ? 0 : result.output().length());
            return result;
        } catch (Exception e) {
            long durationMs = elapsedMs(startNanos);
            log.error("沙箱执行失败(耗时 {}ms): {}", durationMs, e.getMessage(), e);
            return SandboxExecResult.failure("沙箱执行失败: " + e.getMessage(), durationMs);
        } finally {
            // 必须显式 kill —— close() 只释放本地句柄, 不会终止远端沙箱实例
            killQuietly(sandbox);
            concurrency.release();
        }
    }

    // ===== 内部步骤 =====

    /** 创建沙箱: 禁网 + 资源配额 + 存活超时 */
    private Sandbox createSandbox() {
        Sandbox.Builder builder = Sandbox.builder()
                .connectionConfig(connectionConfig())
                .image(config.getPythonImage())
                .resource(Map.of("cpu", config.getCpus(), "memory", config.getMemory()))
                // 存活超时: 服务端到期自动终止, 作为异常场景下容器泄漏的兜底
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .readyTimeout(Duration.ofSeconds(config.getReadyTimeoutSeconds()));
        if (config.isNetworkDisabled()) {
            builder.networkPolicy(NetworkPolicy.builder()
                    .defaultAction(NetworkPolicy.DefaultAction.DENY)
                    .build());
        }
        return builder.build();
    }

    /** 以文件方式投递代码: 避免 python -c 的引号/换行转义问题 */
    private void writeCode(Sandbox sandbox, String code) {
        sandbox.files().write(List.of(WriteEntry.builder()
                .path(CODE_PATH)
                .data(code)
                .mode(CODE_FILE_MODE)
                .build()));
    }

    /** 执行代码: 单命令超时 + 非 root 身份 */
    private Execution runCode(Sandbox sandbox) {
        RunCommandRequest request = RunCommandRequest.builder()
                .command(RUN_COMMAND)
                .timeout(Duration.ofSeconds(config.getExecutionTimeoutSeconds()))
                .uid(SANDBOX_UID)
                .gid(SANDBOX_GID)
                .build();
        return sandbox.commands().run(request);
    }

    /** 把执行结果转换为统一结构(stdout 优先, stderr 追加在尾部) */
    private SandboxExecResult toResult(Execution execution, long durationMs) {
        StringBuilder output = new StringBuilder();
        if (execution.getLogs() != null) {
            appendMessages(output, execution.getLogs().getStdout());
            List<OutputMessage> stderr = execution.getLogs().getStderr();
            if (stderr != null && !stderr.isEmpty()) {
                output.append("[stderr]\n");
                appendMessages(output, stderr);
            }
        }
        Integer exitCode = execution.getExitCode();
        boolean ok = exitCode == null || exitCode == 0;
        if (!ok && output.isEmpty()) {
            output.append("(退出码 ").append(exitCode).append(", 无输出)");
        }
        return new SandboxExecResult(output.toString(), exitCode, durationMs, ok, false, null);
    }

    private static void appendMessages(StringBuilder target, List<OutputMessage> messages) {
        if (messages == null) {
            return;
        }
        for (OutputMessage message : messages) {
            if (message != null && message.getText() != null) {
                target.append(message.getText()).append('\n');
            }
        }
    }

    /** 销毁沙箱: 失败仅告警(沙箱可能已被服务端超时自动回收) */
    private void killQuietly(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        try {
            sandbox.kill();
        } catch (Exception e) {
            log.warn("沙箱销毁失败(可能已被服务端超时回收): {}", e.getMessage());
        }
    }

    private ConnectionConfig connectionConfig() {
        long timeoutSeconds = (long) config.getTimeoutSeconds()
                + config.getReadyTimeoutSeconds()
                + CONNECTION_TIMEOUT_MARGIN_SECONDS;
        return ConnectionConfig.builder()
                .apiKey(config.getApiKey())
                .domain(config.getDomain())
                .protocol(config.getProtocol())
                .requestTimeout(Duration.ofSeconds(timeoutSeconds))
                // 客户端与沙箱不在同一 docker bridge 网络, 无法直连沙箱内网 IP(实测健康检查必超时),
                // 必须由 Server 代理转发(useServerProxy)。切勿去掉, 除非客户端与沙箱网络直通。
                .useServerProxy(config.isUseServerProxy())
                .build();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
