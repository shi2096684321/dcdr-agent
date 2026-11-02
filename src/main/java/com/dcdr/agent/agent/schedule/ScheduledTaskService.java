package com.dcdr.agent.agent.schedule;

import com.dcdr.agent.agent.AgentOrchestrator;
import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.agent.session.SessionSource;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.common.log.LlmCallTrace;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.ScheduledTaskDtos;
import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.entity.ScheduledTaskEntity;
import com.dcdr.agent.entity.ScheduledTaskRunEntity;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.repository.ScheduledTaskRepository;
import com.dcdr.agent.repository.ScheduledTaskRunRepository;
import com.dcdr.agent.service.UserModelService;
import com.dcdr.agent.cluster.ClusterLock;
import com.dcdr.agent.notify.Notifier;
import com.dcdr.agent.notify.NotifierRegistry;
import com.dcdr.agent.notify.NotifyChannel;
import com.dcdr.agent.notify.NotifyMessage;
import com.dcdr.agent.notify.NotifyProperties;
import com.dcdr.agent.notify.NotifyResult;
import com.dcdr.agent.notify.NotifyTemplate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * V2.3 定时任务 Service (F-26 定时触发 Agent 执行任务)。
 * <p>
 * 启动时加载全部 enabled 任务, 用 {@link ThreadPoolTaskScheduler} 按 cron 表达式调度;
 * 到点后调用关联 Agent 异步执行 {@code inputMessage}, 记录运行结果与下次触发时间。
 * <p>
 * Cron 表达式使用 Spring 6 字段格式 (秒 分 时 日 月 周), 由 {@link CronExpression} 解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTaskService {

    private final ScheduledTaskRepository repository;
    private final AgentDefinitionService agentDefinitionService;
    private final AgentOrchestrator orchestrator;
    private final SessionManager sessionManager;
    /** V3.1 F-41: 归属判定统一收敛到 OwnershipGuard (越权返回 404, 替代原先内联 equals 判断的 403) */
    private final OwnershipGuard ownershipGuard;
    /** V3.1 F-46: 多实例下保证同一任务同一时刻只被执行一次 */
    private final ClusterLock clusterLock;
    /** V3.3: 执行结果推送 (渠道分发 + 节流) */
    private final NotifierRegistry notifierRegistry;
    /** V3.3: 消息模板渲染 (预览用) */
    private final NotifyTemplate notifyTemplate;
    private final NotifyProperties notifyProperties;
    /** V3.3: 执行记录（推送里的详情链接指向它） */
    private final ScheduledTaskRunRepository runRepository;
    /** 执行模型校验: 平台模型注册中心 */
    private final ModelRegistry modelRegistry;
    /** 执行模型校验: 用户自定义模型 (custom: 前缀, 需校验归属) */
    private final UserModelService userModelService;
    /** V3.3: 调用链日志落库(与用户对话、工作流共用同一套字段映射) */
    private final com.dcdr.agent.agent.log.LlmCallLogWriter callLogWriter;

    /** 租约 TTL(秒): 需 >= 单次任务最长执行时间(Agent 调用可能较久) */
    @Value("${agent.v3.cluster.lock-ttl-seconds:300}")
    private long lockTtlSeconds;

    /**
     * 启动时是否补跑「错过的那一次」(V3.3)。
     * <p>cron 只在进程活着的时候才有意义: 早上 9 点任务而 8:50 重启、9:20 才起来, 这一天就没有任何推送,
     * 而且没有任何提示 —— 表现与"推送坏了"完全一样。
     */
    @Value("${agent.schedule.misfire-catchup-enabled:true}")
    private boolean misfireCatchupEnabled;

    /**
     * 补跑的时间窗(秒): 只补 {@code next_run_at} 距今不超过该值的任务。
     * <p>为什么要有上限: 隔夜启动时把一整天错过的任务全补一遍, 既烧模型费用又没有时效性
     * (早上 9 点的招聘日报在晚上 11 点补出来毫无意义)。默认 1 小时, 只兜"重启窗口"这一种场景。
     */
    @Value("${agent.schedule.misfire-max-lag-seconds:3600}")
    private long misfireMaxLagSeconds;

    /** 模板预览用的示例正文: 刻意写成多行 + 列表 + 加粗, 覆盖常见排版 */
    private static final String PREVIEW_SAMPLE_CONTENT = """
            今日重点 3 条：

            1. 待评审 PR 2 个：#482 工作区撤回、#486 推送渠道
            2. 昨日流水线失败 1 次（feat/notify），修复后重跑通过
            3. JVM 内存使用率连续 3 天高于 80%

            **以上为示例内容**：真实推送时这里会替换为本次执行的 Agent 输出。
            """;

    private ThreadPoolTaskScheduler scheduler;
    /**
     * 租约续期专用线程 (V3.3)。
     * <p>为什么不复用 {@link #scheduler}: 那个池只有 4 个线程, 续期任务一旦排队等线程,
     * 就会出现"该续的时候没续上" —— 而这正是要修的 bug。
     */
    private ScheduledExecutorService leaseRenewer;
    /** 任务 ID → 调度句柄, 用于启停 */
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sched-task-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.initialize();
        leaseRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sched-lease-renewer");
            t.setDaemon(true);
            return t;
        });
        // 启动时加载全部 enabled 任务
        try {
            List<ScheduledTaskEntity> tasks = repository.findByEnabledTrue();
            for (ScheduledTaskEntity t : tasks) {
                scheduleInternal(t);
            }
            log.info("V2.3 定时任务初始化: 加载 {} 个启用任务", tasks.size());
        } catch (Exception e) {
            log.warn("定时任务初始化加载失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 启动后补跑错过的那一次 (V3.3)。
     * <p>
     * 为什么放在 {@link ApplicationReadyEvent} 而不是 {@link #init()}: 补跑会真的调用
     * {@code AgentOrchestrator}, 需要全部 bean(模型、工具、记忆)就绪; {@code @PostConstruct}
     * 阶段别的 bean 可能还没装配完, 这里跑会在最不该失败的地方失败。
     * <p>
     * 判定条件三条, 缺一不可:
     * <ol>
     *   <li>{@code next_run_at} 已过期 —— 说明到点时进程不在;</li>
     *   <li>过期时长 ≤ {@code misfire-max-lag-seconds} —— 太久的补跑没有时效价值;</li>
     *   <li>{@code last_run_at} 早于该时间点 —— 防"跑完了但还没来得及回写 next_run_at 就崩了"
     *       导致重启后重复执行(多花一次模型费用)。</li>
     * </ol>
     * 无论补不补, 都会按当前时间重算 {@code next_run_at} 并落库, 否则每次重启都会重复到这里。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpMissedRuns() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledTaskEntity> tasks;
        try {
            tasks = repository.findByEnabledTrue();
        } catch (Exception e) {
            log.warn("补跑扫描失败: {}", e.getMessage());
            return;
        }
        for (ScheduledTaskEntity task : tasks) {
            try {
                LocalDateTime due = task.getNextRunAt();
                LocalDateTime next = computeNextRun(task.getCron(), now);
                if (due == null) {
                    // 没算过(存量数据/上次 cron 解析失败): 只补算, 不补跑
                    task.setNextRunAt(next);
                    repository.save(task);
                    continue;
                }
                if (!due.isBefore(now)) continue;   // 还没到点
                long lagSeconds = Duration.between(due, now).getSeconds();
                if (!misfireCatchupEnabled) {
                    // 开关关掉时也要重算, 否则下次启动还是同一个过期值
                } else if (ranAfter(due, task.getLastRunAt())) {
                    log.info("定时任务 {} 在 {} 之后已执行过, 跳过补跑", task.getId(), due);
                } else if (!withinCatchUpWindow(lagSeconds, misfireMaxLagSeconds)) {
                    log.warn("错过定时任务 {} ({}), 已超出 {}s 补跑窗口(实际晚 {}s), 本次不补跑",
                            task.getId(), task.getName(), misfireMaxLagSeconds, lagSeconds);
                } else {
                    log.info("补跑错过的定时任务: {} (name={}), 计划 {} 实际 {}, 晚 {}s",
                            task.getId(), task.getName(), due, now, lagSeconds);
                    // 走 runJob 而不是 runOnce: 补跑同样要抢集群锁, 否则多实例会各补一次
                    scheduler.execute(() -> runJob(task.getId()));
                }
                task.setNextRunAt(next);
                repository.save(task);
            } catch (Exception e) {
                log.warn("补跑处理失败 (task={}): {}", task.getId(), e.getMessage());
            }
        }
    }

    /**
     * 补跑窗口判定 (抽成静态方法纯粹为了能单测: 三条条件里任何一条写反,
     * 表现都是"要么该补没补, 要么重启后重复烧钱", 而这两种都很难在集成里复现)。
     *
     * @param lagSeconds    已过期的秒数
     * @param maxLagSeconds 允许补跑的最大迟到秒数
     */
    static boolean withinCatchUpWindow(long lagSeconds, long maxLagSeconds) {
        return lagSeconds >= 0 && lagSeconds <= maxLagSeconds;
    }

    /**
     * 该时间点之后是否已经执行过。
     * <p>防的是"任务跑完、还没把 next_run_at 推到下一次就崩了" —— 重启后 next_run_at 仍是过去时间,
     * 不加这层判断就会把同一时刻的任务再跑一遍(重复会话 + 重复模型费用 + 重复推送)。
     */
    static boolean ranAfter(LocalDateTime due, LocalDateTime lastRunAt) {
        return lastRunAt != null && !lastRunAt.isBefore(due);
    }

    @PreDestroy
    public void shutdown() {
        for (ScheduledFuture<?> f : scheduledFutures.values()) {
            f.cancel(false);
        }
        scheduledFutures.clear();
        if (leaseRenewer != null) leaseRenewer.shutdownNow();
        if (scheduler != null) scheduler.shutdown();
    }

    // ===== CRUD =====

    /** 列出定时任务: 管理员返回全部(含他人); 普通用户仅自己的 */
    public List<ScheduledTaskDtos.ScheduledTaskView> list(String userId, boolean isAdmin) {
        List<ScheduledTaskEntity> entities = isAdmin
                ? repository.findAllByOrderByUpdatedAtDesc()
                : repository.findByUserIdOrderByUpdatedAtDesc(userId);
        List<ScheduledTaskDtos.ScheduledTaskView> views = new ArrayList<>(entities.size());
        for (ScheduledTaskEntity e : entities) views.add(toView(e));
        return views;
    }

    public ScheduledTaskDtos.ScheduledTaskView get(String id, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.SCHEDULED_TASK, id, userId, isAdmin, false);
        return toView(loadEntity(id));
    }

    /** 内部加载实体 (归属校验通过后调用) */
    private ScheduledTaskEntity loadEntity(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在: " + id, "SCHEDULED_TASK_NOT_FOUND"));
    }

    @Transactional
    public ScheduledTaskDtos.ScheduledTaskView create(ScheduledTaskDtos.SaveScheduledTaskRequest req, String userId) {
        validate(req);
        validateModel(req.getModel(), userId);
        ScheduledTaskEntity e = new ScheduledTaskEntity();
        e.setId("sched_" + UUID.randomUUID().toString().replace("-", ""));
        e.setUserId(userId);
        applyRequest(e, req);
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        // 计算下次运行时间
        e.setNextRunAt(computeNextRun(e.getCron(), now));
        repository.save(e);
        if (e.isEnabled()) scheduleInternal(e);
        log.info("创建定时任务: {} (name={}, cron={}, agent={})", e.getId(), e.getName(), e.getCron(), e.getAgentId());
        return toView(e);
    }

    @Transactional
    public ScheduledTaskDtos.ScheduledTaskView update(String id, ScheduledTaskDtos.SaveScheduledTaskRequest req,
                                                      String userId, boolean isAdmin) {
        // 归属校验(写): 创建者本人或 admin
        ownershipGuard.check(ResourceType.SCHEDULED_TASK, id, userId, isAdmin, true);
        ScheduledTaskEntity e = loadEntity(id);
        validate(req);
        // 用任务归属人校验自定义模型, 而不是当前操作者(admin 可能编辑他人任务)
        validateModel(req.getModel(), e.getUserId());
        // 取消旧调度
        cancelSchedule(e.getId());
        applyRequest(e, req);
        e.setNextRunAt(e.isEnabled() ? computeNextRun(e.getCron(), LocalDateTime.now()) : null);
        e.setUpdatedAt(LocalDateTime.now());
        repository.save(e);
        if (e.isEnabled()) scheduleInternal(e);
        return toView(e);
    }

    @Transactional
    public void delete(String id, String userId, boolean isAdmin) {
        // 归属校验(写): 创建者本人或 admin
        ownershipGuard.check(ResourceType.SCHEDULED_TASK, id, userId, isAdmin, true);
        ScheduledTaskEntity e = loadEntity(id);
        cancelSchedule(e.getId());
        repository.delete(e);
    }

    /** 手动触发一次执行 (不依赖 cron, 用于测试 / 立即执行) */
    public ScheduledTaskDtos.TriggerResult trigger(String id, ScheduledTaskDtos.TriggerRequest req,
                                                   String userId, boolean isAdmin) {
        // 归属校验(写): 创建者本人或 admin
        ownershipGuard.check(ResourceType.SCHEDULED_TASK, id, userId, isAdmin, true);
        ScheduledTaskEntity e = loadEntity(id);
        String input = (req != null && req.getInputMessage() != null && !req.getInputMessage().isBlank())
                ? req.getInputMessage() : e.getInputMessage();
        if (input == null || input.isBlank()) input = "(定时触发)";
        // 手动触发默认不推送; 需要推送时由前端显式传 notify=true (按钮「执行并推送」)
        boolean notify = req != null && Boolean.TRUE.equals(req.getNotify());
        log.info("手动触发定时任务: {} (name={}, notify={}, input={})", e.getId(), e.getName(), notify, input);
        return runOnce(e, input, true, notify);
    }

    /**
     * 立即推送一条测试消息, 用于验证 Webhook 配置是否打通 (V3.3)。
     * <p>
     * 为什么需要它: 手动触发刻意不推送, 若没有这个入口, 用户配好 Webhook 后只能干等
     * 下一次 cron 到点才知道通不通 —— 排查成本极高。
     * <p>
     * 刻意<b>不</b>写 notify_last_* 状态: 那是"真实执行结果推送"的记录,
     * 测试消息混进去会让用户以为任务已经跑过一轮。也不占用节流窗口。
     */
    public NotifyResult testNotify(String id, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.SCHEDULED_TASK, id, userId, isAdmin, true);
        ScheduledTaskEntity e = loadEntity(id);
        NotifyMessage message = NotifyMessage.builder()
                .taskId(e.getId() + "#test")
                .taskName(e.getName() + " (测试)")
                .channel(e.getNotifyChannel())
                // V3.3: 用任务归属人的推送配置, 而不是操作者 —— 任务跑完结果本就该进他配的群
                .userId(e.getUserId())
                .status("success")
                .content("收到这条消息说明 Webhook 地址与网络都正常。\n"
                        + "真正的推送会在任务执行完成后发送, 内容是该次执行的 Agent 输出。")
                .durationMs(0)
                .runAt(LocalDateTime.now())
                .build();
        return notifierRegistry.dispatchNow(message);
    }

    /**
     * 预览消息模板的渲染结果 (V3.3 F-50)。
     * <p>
     * 用示例正文分别渲染成功 / 失败两份，让用户在改模板或换渠道时就能看到最终形态 ——
     * 模板写错的代价不该是"等下一次 cron 跑到手机上才发现"。
     * 不发送、不触发执行、不消耗配额。
     */
    public ScheduledTaskDtos.NotifyPreview previewNotify(String id, String channelCode,
                                                         String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.SCHEDULED_TASK, id, userId, isAdmin, false);
        ScheduledTaskEntity e = loadEntity(id);
        NotifyChannel channel = resolveChannel(channelCode);
        Notifier notifier = notifierRegistry.notifierOf(channel);
        if (notifier == null) {
            throw new BusinessException(400, "渠道暂无实现: " + channel.code());
        }
        ScheduledTaskDtos.NotifyPreview preview = new ScheduledTaskDtos.NotifyPreview();
        preview.setChannel(channel.code());
        preview.setChannelLabel(channel.label());
        preview.setMaxSize(notifier.maxSize());
        preview.setUnit(notifier.unit().name());
        preview.setSuccessContent(renderPreview(channel, notifier, e, false));
        preview.setFailureContent(renderPreview(channel, notifier, e, true));
        return preview;
    }

    /** 用示例数据渲染一次模板 */
    private String renderPreview(NotifyChannel channel, Notifier notifier, ScheduledTaskEntity task, boolean failed) {
        NotifyMessage message = NotifyMessage.builder()
                .taskId(task.getId() + "#preview")
                .taskName(task.getName())
                .status(failed ? "failed" : "success")
                .content(failed ? null : PREVIEW_SAMPLE_CONTENT)
                .error(failed ? "LLM 调用超时: connect timed out" : null)
                .durationMs(8400)
                .runAt(LocalDateTime.now())
                .build();
        return notifyTemplate.render(channel, message, notifier.maxSize(),
                notifier.unit(), notifier.truncateSuffix());
    }

    /** 任务级渠道 code → 枚举; 留空走全局默认 */
    private NotifyChannel resolveChannel(String code) {
        if (code == null || code.isBlank()) return notifyProperties.getChannel();
        NotifyChannel channel = NotifyChannel.fromCode(code);
        if (channel == null) {
            throw new BusinessException(400, "无法识别的通知渠道: " + code);
        }
        return channel;
    }

    // ===== 执行历史 =====

    /**
     * 任务的执行历史（倒序，不含完整正文）。
     * <p>列表只给摘要：抓取类任务一次输出可能上万字，全量返回会让接口慢得没必要，
     * 用户点开某条再单独取详情。
     */
    public List<ScheduledTaskDtos.RunSummary> listRuns(String taskId, Integer limit,
                                                       String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.SCHEDULED_TASK, taskId, userId, isAdmin, false);
        int size = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        return runRepository.findByTaskIdOrderByCreatedAtDesc(taskId, PageRequest.of(0, size))
                .stream()
                .map(this::toRunSummary)
                .toList();
    }

    /** 执行历史详情（含完整正文） */
    public ScheduledTaskDtos.RunDetail getRun(String taskId, String runId, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.SCHEDULED_TASK, taskId, userId, isAdmin, false);
        ScheduledTaskRunEntity run = runRepository.findById(runId)
                // 记录必须属于该任务: 否则换个 taskId 就能读到别人任务的执行结果
                .filter(r -> taskId.equals(r.getTaskId()))
                .orElseThrow(() -> new BusinessException(404, "执行记录不存在", "RUN_NOT_FOUND"));

        ScheduledTaskDtos.RunDetail d = new ScheduledTaskDtos.RunDetail();
        d.setId(run.getId());
        d.setTaskId(run.getTaskId());
        d.setStatus(run.getStatus());
        d.setDurationMs(run.getDurationMs());
        d.setStartedAt(run.getStartedAt());
        d.setFinishedAt(run.getFinishedAt());
        d.setOutput(run.getOutput());
        d.setError(run.getError());
        d.setShareUrl(buildRunUrl(run));
        return d;
    }

    private ScheduledTaskDtos.RunSummary toRunSummary(ScheduledTaskRunEntity run) {
        ScheduledTaskDtos.RunSummary s = new ScheduledTaskDtos.RunSummary();
        s.setId(run.getId());
        s.setStatus(run.getStatus());
        s.setDurationMs(run.getDurationMs());
        s.setStartedAt(run.getStartedAt());
        s.setFinishedAt(run.getFinishedAt());
        s.setOutputLength(run.getOutput() == null ? 0 : run.getOutput().length());
        s.setError(run.getError());
        s.setPreview(previewOf(run));
        s.setShareUrl(buildRunUrl(run));
        return s;
    }

    /** 摘要：压掉换行后截到 120 字；失败时退化成错误信息，否则列表里会是一行空白 */
    private static String previewOf(ScheduledTaskRunEntity run) {
        String text = run.getOutput();
        if (text == null || text.isBlank()) {
            text = run.getError() == null ? "" : run.getError();
        }
        return abbrev(text.replaceAll("\\s+", " ").strip(), 120);
    }

    /** 截断时不要留下孤立的高位代理，否则 emoji 会变成方块 */
    private static String abbrev(String text, int max) {
        if (text.length() <= max) return text;
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }

    // ===== 调度内部 =====
    private void scheduleInternal(ScheduledTaskEntity task) {
        try {
            // 校验 cron 表达式
            CronExpression.parse(task.getCron());
            Runnable job = () -> runJob(task.getId());
            // Spring 6 Trigger.nextExecution 返回 Instant (非 Date)
            ScheduledFuture<?> future = scheduler.schedule(job, trigger -> {
                CronExpression cron = CronExpression.parse(task.getCron());
                LocalDateTime next = cron.next(LocalDateTime.now());
                return next == null ? null : next.atZone(ZoneId.systemDefault()).toInstant();
            });
            scheduledFutures.put(task.getId(), future);
            log.debug("调度定时任务: {} (cron={}, nextRun={})", task.getId(), task.getCron(), task.getNextRunAt());
        } catch (Exception e) {
            log.error("调度定时任务失败: {} (cron={})", task.getId(), task.getCron(), e);
        }
    }

    private void cancelSchedule(String taskId) {
        ScheduledFuture<?> f = scheduledFutures.remove(taskId);
        if (f != null) f.cancel(false);
    }

    /**
     * cron 到点的实际执行逻辑(<b>仅</b>由调度器调用)。
     * <p>
     * V3.1 F-46: 每个实例启动时都会把全部 enabled 任务注册进自己的
     * {@link ThreadPoolTaskScheduler}, 故多实例部署下同一时刻 N 个实例会同时触发同一任务 ——
     * 后果是重复触发 Agent(重复会话 + 重复模型费用)。这里用租约锁把执行权收归单一实例。
     * <p>
     * 手动 {@link #trigger} 不经过本方法, <b>刻意不加锁</b>: 那是用户主动发起的动作,
     * 重复执行的语义是"用户点了两次", 不应被集群锁静默吞掉。
     */
    private void runJob(String taskId) {
        String lockName = "scheduled:task:" + taskId;
        Duration ttl = Duration.ofSeconds(Math.max(1L, lockTtlSeconds));
        if (!clusterLock.tryAcquire(lockName, ttl)) {
            // 其他实例已抢到执行权, 本次跳过 —— 属正常调度竞争, 不是错误
            log.debug("定时任务 {} 已由其他实例执行, 本次跳过", taskId);
            return;
        }
        // V3.3: 持有期间持续续租。原来只在开始时拿一次 300s 租约, 而实测单次执行要 405s ——
        // 锁中途过期后其他实例会再跑一遍同一任务(重复会话 + 重复模型费用 + 重复推送)。
        ScheduledFuture<?> lease = startLeaseRenewal(lockName, ttl);
        try {
            ScheduledTaskEntity task = repository.findById(taskId).orElse(null);
            if (task == null || !task.isEnabled()) return;
            String input = task.getInputMessage() == null ? "(定时触发)" : task.getInputMessage();
            log.info("定时任务触发: {} (name={}, agent={})", task.getId(), task.getName(), task.getAgentId());
            // 标记为 running
            task.setLastRunAt(LocalDateTime.now());
            task.setLastStatus("running");
            repository.save(task);
            runOnce(task, input, false, false);
        } catch (Exception e) {
            log.error("定时任务执行异常: {}", taskId, e);
        } finally {
            if (lease != null) lease.cancel(false);
            clusterLock.release(lockName);
        }
    }

    /**
     * 起一个续租心跳 (V3.3)。
     * <p>周期取 TTL 的一半: 留一半容错, 单次续期失败(网络抖动)不至于立刻丢锁。
     *
     * @return 心跳句柄; 无需续租时返回 {@code null}
     */
    private ScheduledFuture<?> startLeaseRenewal(String lockName, Duration ttl) {
        if (leaseRenewer == null) return null;
        long periodMs = Math.max(1000L, ttl.toMillis() / 2);
        return leaseRenewer.scheduleAtFixedRate(() -> {
            try {
                if (!clusterLock.renew(lockName, ttl)) {
                    // 续不上说明租约已被接管(或锁后端故障)。已经跑出去的任务没法中断,
                    // 但必须留下痕迹 —— 否则"同一任务跑了两遍"永远查不出原因
                    log.warn("租约续期失败, 该任务可能已被其他实例接管: lock={}", lockName);
                }
            } catch (Exception e) {
                log.warn("租约续期异常: lock={}, {}", lockName, e.getMessage());
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 执行一次任务并回写状态。
     *
     * @param manual          手动触发 (用户点「立即触发」)
     * @param notifyRequested 手动触发时是否要求推送 (cron 触发恒为 false, 由任务开关决定)
     */
    private ScheduledTaskDtos.TriggerResult runOnce(ScheduledTaskEntity task, String input,
                                                    boolean manual, boolean notifyRequested) {
        long startedAtMs = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        ScheduledTaskDtos.TriggerResult result = new ScheduledTaskDtos.TriggerResult();
        // V3.3: 定时任务也要留调用链日志。此前这里不传 trace, 编排层会自建一个但<b>没人注册监听</b>,
        // 快照只进了日志文件、从不落库 —— 表现是「调用链日志」页面里搜不到任何定时任务,
        // 而失败时最需要它: 只看到一句 "定时任务执行失败", 分不清是第几轮生成超时还是工具挂了。
        LlmCallTrace trace = LlmCallTrace.begin();
        String endSignal = "ON_COMPLETE";
        // 最近运行时间统一记"开始时间": 之前只在末尾写完成时间, 17:58 触发、18:05 才回写,
        // 列表里显示的时间与执行记录的 startedAt 差出 7 分钟, 长任务完全看不出跑了多久
        task.setLastRunAt(startedAt);
        task.setLastStatus("running");
        try {
            // V3.3: 执行会话标记来源=schedule + sourceRef=任务 ID。
            // 会话不再用完即删: 它归档到「自动化 → 任务对话」, 用户可以回看"这次到底问了/答了什么"。
            // 保留数量按任务收敛(见 finally), 不会因为分钟级 cron 无限膨胀。
            var sess = sessionManager.createSession(
                    "定时任务 · " + task.getName(), null, null, task.getUserId(),
                    SessionSource.SCHEDULE.code(), task.getId());
            // 绑在会话创建之后: sessionId 是日志跳回「任务对话」的唯一线索, 早于它就拿不到
            callLogWriter.bind(trace, task.getUserId(), sess.getId(), input, SessionSource.SCHEDULE);
            ChatRequest req = new ChatRequest();
            req.setSessionId(sess.getId());
            req.setMessage(input);
            req.setAgentId(task.getAgentId());
            // 任务上显式选了模型时优先级最高: 连 Agent 配置的模型也不覆盖 (见 ChatRequest#modelLocked)
            if (task.getModel() != null && !task.getModel().isBlank()) {
                req.setModel(task.getModel());
                req.setModelLocked(true);
            }
            req.setStream(false);
            // chatSync 内部复用流式管线（含工具循环），抓取/检索类任务才能真正拿到数据。
            // 曾经直接用 chatSync 的旧实现（单轮 invoke）时，模型没有工具可用，产出全是编的。
            ChatResponse resp = orchestrator.chatSync(req, task.getUserId(), trace);
            String content = resp.getContent() == null ? "" : resp.getContent();
            result.setOutput(content);
            result.setStatus("success");
            // 回写任务状态
            task.setLastStatus("success");
            task.setLastError(null);
        } catch (Exception e) {
            result.setStatus("failed");
            result.setError(e.getMessage());
            task.setLastStatus("failed");
            task.setLastError(e.getMessage());
            endSignal = "ON_ERROR";
            log.error("定时任务执行失败: {}", task.getId(), e);
        } finally {
            // 收尾必须在 trim 之前: summary 是同步落库, 会话被清理掉不影响已写入的日志行,
            // 但顺序反过来会让人误以为"会话没了日志也没了"
            trace.summary(endSignal);
            // 成功与失败都要收敛: 失败留下的会话同样进「任务对话」, 否则只有成功那批被清理
            sessionManager.trimAutoSessions(SessionSource.SCHEDULE.code(), task.getId(),
                    AUTO_SESSION_KEEP);
        }
        task.setNextRunAt(computeNextRun(task.getCron(), LocalDateTime.now()));
        repository.save(task);
        long elapsed = System.currentTimeMillis() - startedAtMs;
        result.setDurationMs(elapsed);
        // 执行记录总是保存（手动触发也要能回看），只是手动触发默认不推送
        ScheduledTaskRunEntity run = saveRun(task, result, startedAt, elapsed);
        applyNotify(task, result, elapsed, run, manual, notifyRequested);
        return result;
    }

    /**
     * 保存单次执行记录，并生成免登录分享 token。
     * <p>
     * 失败不抛出：记录只是"能让详情链接有地方落"的辅助能力，
     * 落库失败不该把一次成功的任务执行变成失败。
     */
    private ScheduledTaskRunEntity saveRun(ScheduledTaskEntity task, ScheduledTaskDtos.TriggerResult result,
                                           LocalDateTime startedAt, long elapsedMs) {
        try {
            LocalDateTime now = LocalDateTime.now();
            ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
            run.setId("run_" + UUID.randomUUID().toString().replace("-", ""));
            run.setTaskId(task.getId());
            run.setUserId(task.getUserId());
            run.setStatus(result.getStatus());
            run.setOutput(result.getOutput());
            run.setError(result.getError());
            run.setDurationMs(elapsedMs);
            run.setStartedAt(startedAt);
            run.setFinishedAt(now);
            run.setCreatedAt(now);
            int ttlDays = notifyProperties.getShareTtlDays();
            if (ttlDays > 0) {
                run.setShareToken(UUID.randomUUID().toString().replace("-", ""));
                run.setShareExpiresAt(now.plusDays(ttlDays));
            }
            ScheduledTaskRunEntity saved = runRepository.save(run);
            trimRuns(task.getId());
            return saved;
        } catch (Exception e) {
            log.warn("保存定时任务执行记录失败 (task={}): {}", task.getId(), e.getMessage());
            return null;
        }
    }

    /** 每个任务保留的执行记录条数上限 */
    private static final int RUN_HISTORY_KEEP = 50;

    /**
     * 每个任务保留的执行会话条数上限 (V3.3)。
     * <p>
     * 执行记录已存 50 条正文, 会话再留 50 条就是两倍存储 —— 会话的用处只是"回看那次对话的
     * 完整往返(含工具调用)", 取执行记录的一半足够覆盖"昨天/上一次"这类查看需求。
     */
    private static final int AUTO_SESSION_KEEP = 20;

    /**
     * 只保留最近 N 条执行记录。
     * <p>
     * 单次输出是 TEXT（抓取类任务可能上万字），定时任务按分钟级跑，不清理的话这张表
     * 会以「每天几十条 × 每条几十 KB」的速度膨胀。清理失败只记日志 ——
     * 它是维护性动作，不该影响任务本身的成败。
     */
    private void trimRuns(String taskId) {
        try {
            List<ScheduledTaskRunEntity> recent = runRepository.findByTaskIdOrderByCreatedAtDesc(
                    taskId, PageRequest.of(0, RUN_HISTORY_KEEP + 1));
            if (recent.size() > RUN_HISTORY_KEEP) {
                runRepository.deleteAll(recent.subList(RUN_HISTORY_KEEP, recent.size()));
            }
        } catch (Exception e) {
            log.warn("清理历史执行记录失败 (task={}): {}", taskId, e.getMessage());
        }
    }

    /**
     * 拼接详情页地址。
     * <p>没配 {@code share-base-url} 时返回 {@code null} —— 相对路径在 IM 里点不开，
     * 与其给个坏链接，不如只推概览。
     */
    private String buildRunUrl(ScheduledTaskRunEntity run) {
        if (run == null || run.getShareToken() == null) return null;
        String base = notifyProperties.getShareBaseUrl();
        if (base == null || base.isBlank()) return null;
        return base.replaceAll("/+$", "") + "/share/runs/" + run.getShareToken();
    }

    /**
     * 异步推送执行结果 (V3.3)。
     * <p>
     * <b>为什么必须异步</b>: 调度线程池只有 4 个线程, 而推送是阻塞 HTTP 调用。
     * 一个渠道卡住 5 秒, 就会挤占其它定时任务的执行窗口。这里丢到
     * {@code boundedElastic} (Reactor 的阻塞任务池), 既不占调度线程也不占 reactor-http-nio。
     * <p>
     * <b>为什么推送失败不能改 lastStatus</b>: 推送是旁路能力。任务跑成功了,
     * 只是消息没发出去, 任务结果依然有效; 若把 lastStatus 改成 failed,
     * 用户会误以为 Agent 执行失败而重跑, 白白多花一次模型费用。
     */
    /**
     * 决定本次执行要不要推送, 并把结果写回 {@link ScheduledTaskDtos.TriggerResult} (V3.3)。
     * <p>
     * <b>三件事必须同时成立才推送</b>: 任务开了推送开关 + (cron 触发 或 手动触发明确要求推送)。
     * 其余情况一律把原因回传前端 —— 早先这里是静默 {@code return}, 用户点十次「立即触发」
     * 也收不到消息, 而页面上还留着上一次 cron 的"推送成功", 完全无法判断是没推还是推失败。
     * <p>
     * <b>手动触发为什么同步</b>: 用户点这一下就是为了当场验证"能不能收到"。异步只能回一句
     * "已提交", 失败了也看不见原因; 而手动触发本来就跑在 controller 的 boundedElastic 线程上,
     * 推送自带 5s 超时, 阻塞几秒不影响任何人。
     * <p>
     * 节流对手动触发同样生效({{@code dispatch}} 而非 {@code dispatchNow}): 连点两次时第二条会
     * 明确回"节流中", 而不是重复往群里灌 —— 只是这次用户看得见原因。
     */
    private void applyNotify(ScheduledTaskEntity task, ScheduledTaskDtos.TriggerResult result,
                             long elapsedMs, ScheduledTaskRunEntity run,
                             boolean manual, boolean notifyRequested) {
        if (!task.isNotifyEnabled()) {
            result.setNotifyStatus("skipped");
            result.setNotifyReason("该任务未开启「结果推送」");
            return;
        }
        if (manual && !notifyRequested) {
            result.setNotifyStatus("skipped");
            result.setNotifyReason("手动触发默认不推送, 需要推送请用「执行并推送」");
            return;
        }
        NotifyMessage message = buildNotifyMessage(task, result, elapsedMs, run);
        if (!manual) {
            notifyAsync(task.getId(), message);
            return;
        }
        NotifyResult r;
        try {
            r = notifierRegistry.dispatch(message);
        } catch (Exception e) {
            // Notifier 实现按约定不抛异常, 这里仍是最后一道兜底
            r = NotifyResult.failed(null, e.getMessage());
        }
        recordNotifyState(task.getId(), r);
        result.setNotifyStatus(r.statusText());
        result.setNotifyReason(r.isSuccess() ? null : r.detail());
    }

    private NotifyMessage buildNotifyMessage(ScheduledTaskEntity task, ScheduledTaskDtos.TriggerResult result,
                                             long elapsedMs, ScheduledTaskRunEntity run) {
        return NotifyMessage.builder()
                .taskId(task.getId())
                .taskName(task.getName())
                .channel(task.getNotifyChannel())
                // V3.3: 用户级推送配置按任务归属人解析
                .userId(task.getUserId())
                .status(result.getStatus())
                .content(result.getOutput())
                .error(result.getError())
                .durationMs(elapsedMs)
                .runAt(LocalDateTime.now())
                .runUrl(buildRunUrl(run))
                .build();
    }

    /**
     * 异步推送执行结果 (cron 路径)。
     * <p>
     * <b>为什么必须异步</b>: 调度线程池只有 4 个线程, 而推送是阻塞 HTTP 调用。
     * 一个渠道卡住 5 秒, 就会挤占其它定时任务的执行窗口。这里丢到
     * {@code boundedElastic} (Reactor 的阻塞任务池), 既不占调度线程也不占 reactor-http-nio。
     * <p>
     * <b>为什么推送失败不能改 lastStatus</b>: 推送是旁路能力。任务跑成功了,
     * 只是消息没发出去, 任务结果依然有效; 若把 lastStatus 改成 failed,
     * 用户会误以为 Agent 执行失败而重跑, 白白多花一次模型费用。
     */
    private void notifyAsync(String taskId, NotifyMessage message) {
        try {
            Schedulers.boundedElastic().schedule(() -> {
                NotifyResult r;
                try {
                    r = notifierRegistry.dispatch(message);
                } catch (Exception e) {
                    // Notifier 实现按约定不抛异常, 这里仍是最后一道兜底
                    r = NotifyResult.failed(null, e.getMessage());
                }
                recordNotifyState(taskId, r);
            });
        } catch (Exception e) {
            log.warn("提交推送任务失败 (task={}): {}", taskId, e.getMessage());
        }
    }

    /**
     * 回写推送状态。
     * <p>
     * 重新 {@code findById} 而不是复用传进来的实体: 异步执行期间任务可能已被编辑,
     * 直接 save 整个 detached 对象会把用户刚改的 cron 覆盖回旧值。
     */
    private void recordNotifyState(String taskId, NotifyResult r) {
        try {
            repository.findById(taskId).ifPresent(t -> {
                t.setNotifyLastStatus(r.statusText());
                t.setNotifyLastError(r.isSuccess() ? null : r.detail());
                t.setNotifyLastAt(LocalDateTime.now());
                repository.save(t);
            });
            if (!r.isSuccess() && !r.isSkipped()) {
                log.warn("定时任务推送失败 (task={}): {}", taskId, r.getError());
            } else if (r.isSkipped()) {
                log.debug("定时任务推送跳过 (task={}): {}", taskId, r.getReason());
            }
        } catch (Exception e) {
            log.warn("回写推送状态失败 (task={}): {}", taskId, e.getMessage());
        }
    }

    private LocalDateTime computeNextRun(String cron, LocalDateTime from) {
        try {
            CronExpression c = CronExpression.parse(cron);
            return c.next(from);
        } catch (Exception e) {
            log.warn("解析 cron 失败: {}", cron, e);
            return null;
        }
    }

    private void validate(ScheduledTaskDtos.SaveScheduledTaskRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException(400, "任务名称不能为空");
        }
        if (req.getCron() == null || req.getCron().isBlank()) {
            throw new BusinessException(400, "cron 表达式不能为空");
        }
        try {
            CronExpression.parse(req.getCron());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "cron 表达式无效: " + e.getMessage());
        }
        // 关联 Agent 可选: 不关联时按默认助手执行(平台默认模型 + 无 Agent 配置/工具白名单)。
        // 指定了才校验存在性, 避免用户填了个已删除的 ID 后到点执行才失败。
        if (req.getAgentId() != null && !req.getAgentId().isBlank()) {
            agentDefinitionService.loadEntity(req.getAgentId());
        }
    }

    /**
     * 校验执行模型是否可用。留空直接放行(= 跟随 Agent 配置/平台默认)。
     * <p>
     * 为什么在<b>保存时</b>校验: 模型名是用户手选的, 配错只会在下一次 cron 到点才暴露,
     * 那时只剩一条失败记录, 排查成本远高于保存当下就被拒。
     * <p>
     * 用户自定义模型 ({@code custom:<id>}) 必须按<b>任务归属人</b>校验 —— 否则 admin 建任务时
     * 可能填上别人私有的模型 ID, 到点执行才 404。
     */
    private void validateModel(String model, String userId) {
        if (model == null || model.isBlank()) return;
        String m = model.trim();
        try {
            if (userModelService.isCustomModelName(m)) {
                // 只校验存在性/启用状态, 不构建 provider: 保存动作不该解密 apiKey 或建客户端
                userModelService.assertUsable(userId, m);
            } else {
                modelRegistry.getChatProvider(m);
            }
        } catch (BusinessException e) {
            throw new BusinessException(400, "执行模型不可用: " + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException(400, "执行模型不可用: " + m);
        }
    }

    private void applyRequest(ScheduledTaskEntity e, ScheduledTaskDtos.SaveScheduledTaskRequest req) {
        e.setName(req.getName().trim());
        // 关联 Agent 可选: 空串归一化成 null, 避免 "" 与 null 两种空值
        e.setAgentId(req.getAgentId() == null || req.getAgentId().isBlank()
                ? null : req.getAgentId().trim());
        // 执行模型可选: 空串归一化成 null(= 跟随 Agent 配置/平台默认)
        e.setModel(req.getModel() == null || req.getModel().isBlank()
                ? null : req.getModel().trim());
        e.setCron(req.getCron().trim());
        e.setInputMessage(req.getInputMessage());
        if (req.getEnabled() != null) e.setEnabled(req.getEnabled());
        // V3.3 推送配置: 空串表示"用全局默认渠道", 统一归一化成 null, 避免 "" 与 null 两种空值
        if (req.getNotifyEnabled() != null) e.setNotifyEnabled(req.getNotifyEnabled());
        if (req.getNotifyChannel() != null) {
            e.setNotifyChannel(req.getNotifyChannel().isBlank() ? null : req.getNotifyChannel().trim());
        }
    }

    private ScheduledTaskDtos.ScheduledTaskView toView(ScheduledTaskEntity e) {
        ScheduledTaskDtos.ScheduledTaskView v = new ScheduledTaskDtos.ScheduledTaskView();
        v.setId(e.getId());
        v.setUserId(e.getUserId());
        v.setName(e.getName());
        v.setAgentId(e.getAgentId());
        // 关联 Agent 名称 (前端展示用); 未关联 Agent 时留空, 由前端展示"默认助手"
        if (e.getAgentId() != null && !e.getAgentId().isBlank()) {
            try {
                AgentDefinitionEntity agent = agentDefinitionService.loadEntity(e.getAgentId());
                v.setAgentName(agent.getName());
            } catch (Exception ignored) {}
        }
        v.setModel(e.getModel());
        v.setCron(e.getCron());
        v.setInputMessage(e.getInputMessage());
        v.setEnabled(e.isEnabled());
        v.setLastRunAt(e.getLastRunAt());
        v.setLastStatus(e.getLastStatus());
        v.setLastError(e.getLastError());
        v.setNextRunAt(e.getNextRunAt());
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        v.setNotifyEnabled(e.isNotifyEnabled());
        v.setNotifyChannel(e.getNotifyChannel());
        v.setNotifyLastStatus(e.getNotifyLastStatus());
        v.setNotifyLastError(e.getNotifyLastError());
        v.setNotifyLastAt(e.getNotifyLastAt());
        return v;
    }
}
