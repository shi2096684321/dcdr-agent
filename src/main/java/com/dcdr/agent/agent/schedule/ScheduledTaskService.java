package com.dcdr.agent.agent.schedule;

import com.dcdr.agent.agent.AgentOrchestrator;
import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.ScheduledTaskDtos;
import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.entity.ScheduledTaskEntity;
import com.dcdr.agent.entity.ScheduledTaskRunEntity;
import com.dcdr.agent.repository.ScheduledTaskRepository;
import com.dcdr.agent.repository.ScheduledTaskRunRepository;
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
import java.util.concurrent.ScheduledFuture;

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

    /** 租约 TTL(秒): 需 >= 单次任务最长执行时间(Agent 调用可能较久) */
    @Value("${agent.v3.cluster.lock-ttl-seconds:300}")
    private long lockTtlSeconds;

    /** 模板预览用的示例正文: 刻意写成多行 + 列表 + 加粗, 覆盖常见排版 */
    private static final String PREVIEW_SAMPLE_CONTENT = """
            今日重点 3 条：

            1. 待评审 PR 2 个：#482 工作区撤回、#486 推送渠道
            2. 昨日流水线失败 1 次（feat/notify），修复后重跑通过
            3. JVM 内存使用率连续 3 天高于 80%

            **以上为示例内容**：真实推送时这里会替换为本次执行的 Agent 输出。
            """;

    private ThreadPoolTaskScheduler scheduler;
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

    @PreDestroy
    public void shutdown() {
        for (ScheduledFuture<?> f : scheduledFutures.values()) {
            f.cancel(false);
        }
        scheduledFutures.clear();
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
        log.info("手动触发定时任务: {} (name={}, input={})", e.getId(), e.getName(), input);
        return runOnce(e, input, true);
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
        if (!clusterLock.tryAcquire(lockName, Duration.ofSeconds(Math.max(1L, lockTtlSeconds)))) {
            // 其他实例已抢到执行权, 本次跳过 —— 属正常调度竞争, 不是错误
            log.debug("定时任务 {} 已由其他实例执行, 本次跳过", taskId);
            return;
        }
        try {
            ScheduledTaskEntity task = repository.findById(taskId).orElse(null);
            if (task == null || !task.isEnabled()) return;
            String input = task.getInputMessage() == null ? "(定时触发)" : task.getInputMessage();
            log.info("定时任务触发: {} (name={}, agent={})", task.getId(), task.getName(), task.getAgentId());
            // 标记为 running
            task.setLastRunAt(LocalDateTime.now());
            task.setLastStatus("running");
            repository.save(task);
            runOnce(task, input, false);
        } catch (Exception e) {
            log.error("定时任务执行异常: {}", taskId, e);
        } finally {
            clusterLock.release(lockName);
        }
    }

    /**
     * 执行一次任务并回写状态。
     *
     * @param manual 手动触发 (用户点「立即触发」)。<b>手动触发不推送通知</b> ——
     *               结果就展示在页面上, 再往微信推一条只会让调试/试用变成刷屏。
     */
    private ScheduledTaskDtos.TriggerResult runOnce(ScheduledTaskEntity task, String input, boolean manual) {
        long startedAtMs = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        ScheduledTaskDtos.TriggerResult result = new ScheduledTaskDtos.TriggerResult();
        try {
            // 创建临时会话执行 (与 WorkflowEngine 一致, 避免污染用户主会话)
            var sess = sessionManager.createSession(
                    "schedule:" + task.getName(), null, null, task.getUserId());
            ChatRequest req = new ChatRequest();
            req.setSessionId(sess.getId());
            req.setMessage(input);
            req.setAgentId(task.getAgentId());
            req.setStream(false);
            ChatResponse resp = orchestrator.chatSync(req, task.getUserId());
            String content = resp.getContent() == null ? "" : resp.getContent();
            result.setOutput(content);
            result.setStatus("success");
            // 回写任务状态
            task.setLastStatus("success");
            task.setLastError(null);
            try { sessionManager.deleteSession(sess.getId()); } catch (Exception ignored) {}
        } catch (Exception e) {
            result.setStatus("failed");
            result.setError(e.getMessage());
            task.setLastStatus("failed");
            task.setLastError(e.getMessage());
            log.error("定时任务执行失败: {}", task.getId(), e);
        }
        task.setLastRunAt(LocalDateTime.now());
        task.setNextRunAt(computeNextRun(task.getCron(), LocalDateTime.now()));
        repository.save(task);
        long elapsed = System.currentTimeMillis() - startedAtMs;
        // 执行记录总是保存（手动触发也要能回看），只是手动触发不推送
        ScheduledTaskRunEntity run = saveRun(task, result, startedAt, elapsed);
        if (!manual) {
            notifyAsync(task, result, elapsed, run);
        }
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
            return runRepository.save(run);
        } catch (Exception e) {
            log.warn("保存定时任务执行记录失败 (task={}): {}", task.getId(), e.getMessage());
            return null;
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
    private void notifyAsync(ScheduledTaskEntity task, ScheduledTaskDtos.TriggerResult result,
                             long elapsedMs, ScheduledTaskRunEntity run) {
        if (!task.isNotifyEnabled()) return;
        final String taskId = task.getId();
        NotifyMessage message = NotifyMessage.builder()
                .taskId(taskId)
                .taskName(task.getName())
                .channel(task.getNotifyChannel())
                .status(result.getStatus())
                .content(result.getOutput())
                .error(result.getError())
                .durationMs(elapsedMs)
                .runAt(LocalDateTime.now())
                .runUrl(buildRunUrl(run))
                .build();
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
        if (req.getAgentId() == null || req.getAgentId().isBlank()) {
            throw new BusinessException(400, "必须指定关联 Agent");
        }
        if (req.getCron() == null || req.getCron().isBlank()) {
            throw new BusinessException(400, "cron 表达式不能为空");
        }
        try {
            CronExpression.parse(req.getCron());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "cron 表达式无效: " + e.getMessage());
        }
        // Agent 必须存在
        agentDefinitionService.loadEntity(req.getAgentId());
    }

    private void applyRequest(ScheduledTaskEntity e, ScheduledTaskDtos.SaveScheduledTaskRequest req) {
        e.setName(req.getName().trim());
        e.setAgentId(req.getAgentId());
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
        // 关联 Agent 名称 (前端展示用)
        try {
            AgentDefinitionEntity agent = agentDefinitionService.loadEntity(e.getAgentId());
            v.setAgentName(agent.getName());
        } catch (Exception ignored) {}
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
