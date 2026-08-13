package com.dcdr.agent.agent.schedule;

import com.dcdr.agent.agent.AgentOrchestrator;
import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.ScheduledTaskDtos;
import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.entity.ScheduledTaskEntity;
import com.dcdr.agent.repository.ScheduledTaskRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public List<ScheduledTaskDtos.ScheduledTaskView> list(String userId) {
        List<ScheduledTaskEntity> entities = repository.findByUserIdOrderByUpdatedAtDesc(userId);
        List<ScheduledTaskDtos.ScheduledTaskView> views = new ArrayList<>(entities.size());
        for (ScheduledTaskEntity e : entities) views.add(toView(e));
        return views;
    }

    public ScheduledTaskDtos.ScheduledTaskView get(String id, String userId) {
        ScheduledTaskEntity e = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在: " + id, "SCHEDULED_TASK_NOT_FOUND"));
        if (!java.util.Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权访问该定时任务", "FORBIDDEN");
        }
        return toView(e);
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
    public ScheduledTaskDtos.ScheduledTaskView update(String id, ScheduledTaskDtos.SaveScheduledTaskRequest req, String userId) {
        ScheduledTaskEntity e = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在: " + id, "SCHEDULED_TASK_NOT_FOUND"));
        if (!java.util.Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权修改该定时任务", "FORBIDDEN");
        }
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
    public void delete(String id, String userId) {
        ScheduledTaskEntity e = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在: " + id, "SCHEDULED_TASK_NOT_FOUND"));
        if (!java.util.Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权删除该定时任务", "FORBIDDEN");
        }
        cancelSchedule(e.getId());
        repository.delete(e);
    }

    /** 手动触发一次执行 (不依赖 cron, 用于测试 / 立即执行) */
    public ScheduledTaskDtos.TriggerResult trigger(String id, ScheduledTaskDtos.TriggerRequest req, String userId) {
        ScheduledTaskEntity e = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在: " + id, "SCHEDULED_TASK_NOT_FOUND"));
        if (!java.util.Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权触发该定时任务", "FORBIDDEN");
        }
        String input = (req != null && req.getInputMessage() != null && !req.getInputMessage().isBlank())
                ? req.getInputMessage() : e.getInputMessage();
        if (input == null || input.isBlank()) input = "(定时触发)";
        log.info("手动触发定时任务: {} (name={}, input={})", e.getId(), e.getName(), input);
        return runOnce(e, input);
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

    /** cron 到点 / 手动触发都会调用的实际执行逻辑 */
    private void runJob(String taskId) {
        try {
            ScheduledTaskEntity task = repository.findById(taskId).orElse(null);
            if (task == null || !task.isEnabled()) return;
            String input = task.getInputMessage() == null ? "(定时触发)" : task.getInputMessage();
            log.info("定时任务触发: {} (name={}, agent={})", task.getId(), task.getName(), task.getAgentId());
            // 标记为 running
            task.setLastRunAt(LocalDateTime.now());
            task.setLastStatus("running");
            repository.save(task);
            runOnce(task, input);
        } catch (Exception e) {
            log.error("定时任务执行异常: {}", taskId, e);
        }
    }

    private ScheduledTaskDtos.TriggerResult runOnce(ScheduledTaskEntity task, String input) {
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
        return result;
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
        return v;
    }
}
