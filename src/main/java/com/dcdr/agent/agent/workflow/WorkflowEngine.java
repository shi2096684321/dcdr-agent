package com.dcdr.agent.agent.workflow;

import com.dcdr.agent.agent.AgentOrchestrator;
import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.config.V3Properties;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.SessionDTO;
import com.dcdr.agent.dto.WorkflowDtos;
import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.entity.WorkflowDefinitionEntity;
import com.dcdr.agent.entity.WorkflowExecutionEntity;
import com.dcdr.agent.repository.WorkflowDefinitionRepository;
import com.dcdr.agent.repository.WorkflowExecutionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2.3 工作流引擎 (F-25 多 Agent 协作编排) + V3.0-alpha 执行容错 (F-33)。
 * <p>
 * 支持三种编排模式 (文档 §5.3):
 * <ul>
 *   <li>sequential: 串行, 按顺序执行, 每步输出可被后续步骤通过 ${step.output} 引用</li>
 *   <li>parallel:   并行, 全部步骤并发执行后汇总, 各步独立互不引用</li>
 *   <li>conditional:条件, 由前一步输出经表达式判断选择后续分支</li>
 * </ul>
 * 变量替换: ${user_message} 替换为用户输入, ${step_name.output} 替换为指定步骤的输出。
 * <p>
 * <b>V3.0-alpha F-33 执行容错</b>:
 * <ul>
 *   <li>执行状态持久化到 workflow_executions, 每步状态实时落库 (支持断点续跑)</li>
 *   <li>每步可配置重试策略 {@link WorkflowDtos.RetryConfig} (maxAttempts/backoff)</li>
 *   <li>人工审批节点 (step.approval=true): 执行到该步暂停, 等待 approve 后继续</li>
 *   <li>失败后 resume 从断点续跑; 审批节点 approve 继续; cancel 取消</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final AgentDefinitionService agentDefinitionService;
    private final AgentOrchestrator orchestrator;
    private final SessionManager sessionManager;
    private final V3Properties v3Properties;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 变量占位符正则: ${name} 或 ${name.output} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /** 并行模式线程池 (固定大小, 避免大工作流并发过多 Agent 调用打爆上游) */
    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(4);

    /** 执行上下文 (一次执行/续跑/审批共享) */
    private static class ExecCtx {
        WorkflowExecutionEntity entity;
        String userInput;
        String userId;
        long totalStart;
        /** stepName → output (供后续步骤变量引用) */
        Map<String, String> outputs = new HashMap<>();
        /** 各步执行结果 (持久化到 steps_state_json) */
        List<WorkflowDtos.StepResult> results = new ArrayList<>();
        /** 已完成 (success/skipped/paused) 的步骤名, 续跑时跳过 */
        Set<String> completed = new HashSet<>();
        /** 是否在审批节点暂停 */
        boolean paused;
        /** 最终输出 */
        String finalOutput;
        /** 结果状态 (success/failed/partial/paused) */
        String resultStatus;
    }

    // ===== 工作流 CRUD =====

    /** 列出当前用户可见的工作流: 自己创建的 + 全部预设模板 */
    public List<WorkflowDtos.WorkflowView> list(String userId) {
        List<WorkflowDefinitionEntity> entities = workflowRepository.findVisible(userId);
        List<WorkflowDtos.WorkflowView> views = new ArrayList<>(entities.size());
        for (WorkflowDefinitionEntity e : entities) views.add(toView(e, userId));
        return views;
    }

    /** 仅预设模板 (模板库) */
    public List<WorkflowDtos.WorkflowView> listTemplates() {
        List<WorkflowDefinitionEntity> entities = workflowRepository.findByIsTemplateTrueOrderByCreatedAtDesc();
        List<WorkflowDtos.WorkflowView> views = new ArrayList<>(entities.size());
        for (WorkflowDefinitionEntity e : entities) views.add(toView(e, null));
        return views;
    }

    public WorkflowDtos.WorkflowView get(String id, String userId) {
        WorkflowDefinitionEntity e = workflowRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + id, "WORKFLOW_NOT_FOUND"));
        if (!isVisible(e, userId)) {
            throw new BusinessException(403, "无权访问该工作流", "FORBIDDEN");
        }
        return toView(e, userId);
    }

    /** 内部加载实体 (供调度器/执行器调用, 不做可见性校验) */
    public WorkflowDefinitionEntity loadEntity(String id) {
        return workflowRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + id, "WORKFLOW_NOT_FOUND"));
    }

    @Transactional
    public WorkflowDtos.WorkflowView create(WorkflowDtos.SaveWorkflowRequest req, String userId, boolean isAdmin) {
        validate(req);
        boolean asTemplate = Boolean.TRUE.equals(req.getIsTemplate());
        if (asTemplate && !isAdmin) {
            throw new BusinessException(403, "仅管理员可创建预设模板", "FORBIDDEN");
        }
        WorkflowDefinitionEntity e = new WorkflowDefinitionEntity();
        e.setId("wf_" + UUID.randomUUID().toString().replace("-", ""));
        e.setUserId(userId);
        applyRequest(e, req);
        e.setTemplate(asTemplate);
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        workflowRepository.save(e);
        log.info("创建工作流: {} (name={}, mode={}, steps={}, template={})", e.getId(), e.getName(), e.getMode(),
                req.getSteps() != null ? req.getSteps().size() : 0, asTemplate);
        return toView(e, userId);
    }

    @Transactional
    public WorkflowDtos.WorkflowView update(String id, WorkflowDtos.SaveWorkflowRequest req, String userId, boolean isAdmin) {
        WorkflowDefinitionEntity e = workflowRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + id, "WORKFLOW_NOT_FOUND"));
        if (e.isTemplate() && !isAdmin) {
            throw new BusinessException(403, "预设模板仅管理员可修改, 普通用户请克隆后再编辑", "FORBIDDEN");
        }
        if (!e.isTemplate() && !java.util.Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权修改该工作流", "FORBIDDEN");
        }
        validate(req);
        applyRequest(e, req);
        if (isAdmin && req.getIsTemplate() != null) {
            e.setTemplate(req.getIsTemplate());
        }
        e.setUpdatedAt(LocalDateTime.now());
        workflowRepository.save(e);
        log.info("更新工作流: {} (name={}, template={})", e.getId(), e.getName(), e.isTemplate());
        return toView(e, userId);
    }

    @Transactional
    public void delete(String id, String userId, boolean isAdmin) {
        WorkflowDefinitionEntity e = workflowRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + id, "WORKFLOW_NOT_FOUND"));
        if (e.isTemplate() && !isAdmin) {
            throw new BusinessException(403, "预设模板仅管理员可删除", "FORBIDDEN");
        }
        if (!e.isTemplate() && !java.util.Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权删除该工作流", "FORBIDDEN");
        }
        workflowRepository.delete(e);
        log.info("删除工作流: {} (name={}, template={})", e.getId(), e.getName(), e.isTemplate());
    }

    /** 克隆模板或他人公开工作流为自己的副本 */
    @Transactional
    public WorkflowDtos.WorkflowView clone(String id, String userId) {
        WorkflowDefinitionEntity src = workflowRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + id, "WORKFLOW_NOT_FOUND"));
        if (!isVisible(src, userId)) {
            throw new BusinessException(403, "无权克隆该工作流", "FORBIDDEN");
        }
        WorkflowDtos.SaveWorkflowRequest req = new WorkflowDtos.SaveWorkflowRequest();
        req.setName(src.getName() + " (副本)");
        req.setDescription(src.getDescription());
        req.setMode(src.getMode());
        req.setSteps(parseSteps(src.getStepsJson()));
        req.setEnabled(src.isEnabled());
        req.setIsTemplate(false);
        return create(req, userId, false);
    }

    // ===== 工作流执行 (F-25 + F-33) =====

    /** 执行工作流 (默认 manual 触发; 调用方应包 boundedElastic) */
    public WorkflowDtos.ExecutionResult execute(WorkflowDtos.ExecuteWorkflowRequest req, String userId) {
        return executeWithTrigger(req.getWorkflowId(), req.getUserInput(),
                req.getTriggerType() == null ? "manual" : req.getTriggerType(), userId);
    }

    /** F-31 触发层: 按指定触发方式执行工作流 (api / webhook / chat / schedule) */
    public WorkflowDtos.ExecutionResult executeWithTrigger(String workflowId, String userInput,
                                                           String triggerType, String userId) {
        WorkflowDefinitionEntity wf = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + workflowId, "WORKFLOW_NOT_FOUND"));
        List<WorkflowDtos.WorkflowStep> steps = parseSteps(wf.getStepsJson());
        if (steps.isEmpty()) {
            throw new BusinessException(400, "工作流未定义任何步骤");
        }
        String input = userInput == null ? "" : userInput;
        String trig = triggerType == null ? "manual" : triggerType;

        // 创建执行记录 (F-33)
        WorkflowExecutionEntity ent = new WorkflowExecutionEntity();
        ent.setId("wfe_" + UUID.randomUUID().toString().replace("-", ""));
        ent.setWorkflowId(wf.getId());
        ent.setUserId(userId);
        ent.setTriggerType(trig);
        ent.setInput(input);
        ent.setStatus("running");
        ent.setStartedAt(LocalDateTime.now());
        ent.setStepsStateJson("[]");
        executionRepository.save(ent);

        ExecCtx ctx = new ExecCtx();
        ctx.entity = ent;
        ctx.userInput = input;
        ctx.userId = userId;
        ctx.totalStart = System.currentTimeMillis();
        ctx.outputs.put("user_message", input);

        log.info("执行工作流: id={}, name={}, mode={}, steps={}, trigger={}, exec={}",
                wf.getId(), wf.getName(), wf.getMode(), steps.size(), trig, ent.getId());
        return runAndFinalize(ctx, wf, steps);
    }

    /** F-33 断点续跑: 从 failed 步骤继续执行 */
    public WorkflowDtos.ExecutionResult resume(String executionId, String userId) {
        WorkflowExecutionEntity ent = loadExecutionForUser(executionId, userId);
        if (!"failed".equals(ent.getStatus())) {
            throw new BusinessException(400, "仅 failed 状态的执行可续跑, 当前状态: " + ent.getStatus());
        }
        WorkflowDefinitionEntity wf = workflowRepository.findById(ent.getWorkflowId())
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + ent.getWorkflowId(), "WORKFLOW_NOT_FOUND"));
        List<WorkflowDtos.WorkflowStep> steps = parseSteps(wf.getStepsJson());
        ExecCtx ctx = buildCtxFromState(ent, userId);
        // 丢弃未完成的旧结果 (failed 步骤), 重新执行
        ctx.results.removeIf(r -> !ctx.completed.contains(r.getName()));
        ent.setStatus("running");
        ent.setError(null);
        ent.setCompletedAt(null);
        executionRepository.save(ent);
        log.info("断点续跑工作流: exec={}, workflow={}", ent.getId(), wf.getId());
        return runAndFinalize(ctx, wf, steps);
    }

    /** F-33 人工审批通过: 从审批节点的下一步继续执行 */
    public WorkflowDtos.ExecutionResult approve(String executionId, String userId, String comment) {
        WorkflowExecutionEntity ent = loadExecutionForUser(executionId, userId);
        if (!"paused".equals(ent.getStatus())) {
            throw new BusinessException(400, "仅 paused 状态的执行可审批, 当前状态: " + ent.getStatus());
        }
        WorkflowDefinitionEntity wf = workflowRepository.findById(ent.getWorkflowId())
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + ent.getWorkflowId(), "WORKFLOW_NOT_FOUND"));
        List<WorkflowDtos.WorkflowStep> steps = parseSteps(wf.getStepsJson());
        ExecCtx ctx = buildCtxFromState(ent, userId);
        // 把审批节点标记为通过
        String pausedStep = ent.getCurrentStep();
        if (pausedStep != null) {
            for (WorkflowDtos.StepResult r : ctx.results) {
                if (pausedStep.equals(r.getName()) && "paused".equals(r.getStatus())) {
                    r.setStatus("success");
                    String note = "[审批通过" + (comment != null && !comment.isBlank() ? ": " + comment : "") + "]";
                    r.setOutput(r.getOutput() == null || r.getOutput().isBlank() ? note : r.getOutput() + "\n" + note);
                    ctx.outputs.put(pausedStep, r.getOutput());
                    ctx.completed.add(pausedStep);
                    break;
                }
            }
        }
        ent.setStatus("running");
        ent.setCompletedAt(null);
        executionRepository.save(ent);
        log.info("审批通过, 继续执行: exec={}, step={}", ent.getId(), pausedStep);
        return runAndFinalize(ctx, wf, steps);
    }

    /** F-33 取消执行 */
    @Transactional
    public void cancel(String executionId, String userId) {
        WorkflowExecutionEntity ent = loadExecutionForUser(executionId, userId);
        if ("success".equals(ent.getStatus()) || "cancelled".equals(ent.getStatus())) {
            throw new BusinessException(400, "已完成/已取消的执行不可取消");
        }
        ent.setStatus("cancelled");
        ent.setCompletedAt(LocalDateTime.now());
        executionRepository.save(ent);
        log.info("取消工作流执行: exec={}", ent.getId());
    }

    // ===== 执行记录查询 =====

    /** 列出当前用户的执行记录 */
    public List<WorkflowDtos.ExecutionView> listExecutions(String userId) {
        List<WorkflowExecutionEntity> entities = executionRepository.findByUserIdOrderByStartedAtDesc(userId);
        List<WorkflowDtos.ExecutionView> views = new ArrayList<>(entities.size());
        for (WorkflowExecutionEntity e : entities) views.add(toExecutionView(e, userId));
        return views;
    }

    /**
     * V3.0 分页查询执行记录 (执行历史搜索/筛选/分页)。
     *
     * @param userId      用户 ID (必填, 数据隔离)
     * @param status      状态筛选 (running/paused/success/failed/cancelled, 可空)
     * @param workflowId  工作流筛选 (可空)
     * @param triggerType 触发方式筛选 (manual/api/webhook/event/schedule/chat, 可空)
     * @param keyword     关键词, 模糊匹配输入内容/错误信息 (可空)
     * @param pageable    分页参数
     */
    public WorkflowDtos.ExecutionPageView listExecutionsPaged(String userId, String status, String workflowId,
                                                              String triggerType, String keyword, Pageable pageable) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<WorkflowExecutionEntity> page = executionRepository.queryByConditions(
                userId, normalizeOrNull(status), normalizeOrNull(workflowId),
                normalizeOrNull(triggerType), kw, pageable);
        WorkflowDtos.ExecutionPageView v = new WorkflowDtos.ExecutionPageView();
        v.setContent(page.getContent().stream().map(e -> toExecutionView(e, userId)).toList());
        v.setPage(page.getNumber());
        v.setSize(page.getSize());
        v.setTotalElements(page.getTotalElements());
        v.setTotalPages(page.getTotalPages());
        return v;
    }

    /** 空串归一化为 null (查询条件用) */
    private static String normalizeOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 执行详情 */
    public WorkflowDtos.ExecutionView getExecution(String id, String userId) {
        return toExecutionView(loadExecutionForUser(id, userId), userId);
    }

    /** 加载执行记录并校验归属 (创建者本人) */
    private WorkflowExecutionEntity loadExecutionForUser(String id, String userId) {
        WorkflowExecutionEntity e = executionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "执行记录不存在: " + id, "EXECUTION_NOT_FOUND"));
        if (userId != null && !userId.equals(e.getUserId())) {
            throw new BusinessException(403, "无权访问该执行记录", "FORBIDDEN");
        }
        return e;
    }

    // ===== 编排核心 =====

    /** 运行工作流并落库最终状态 */
    private WorkflowDtos.ExecutionResult runAndFinalize(ExecCtx ctx, WorkflowDefinitionEntity wf,
                                                        List<WorkflowDtos.WorkflowStep> steps) {
        String mode = wf.getMode() == null ? "sequential" : wf.getMode().toLowerCase();
        try {
            switch (mode) {
                case "parallel" -> runParallel(ctx, steps);
                case "conditional" -> runConditional(ctx, steps);
                default -> runSequential(ctx, steps);
            }
        } catch (Exception e) {
            log.error("工作流执行异常: exec={}", ctx.entity.getId(), e);
            ctx.entity.setStatus("failed");
            ctx.entity.setError("执行异常: " + e.getMessage());
        }

        // 计算最终输出与状态
        boolean anyFailed = ctx.results.stream().anyMatch(r -> "failed".equals(r.getStatus()));
        if (ctx.paused) {
            ctx.resultStatus = "paused";
        } else if (anyFailed) {
            ctx.finalOutput = "parallel".equals(mode) ? summarizeParallel(ctx.results) : pickFinalOutput(ctx.results);
            ctx.resultStatus = ctx.finalOutput != null ? "partial" : "failed";
        } else {
            ctx.finalOutput = "parallel".equals(mode) ? summarizeParallel(ctx.results) : pickFinalOutput(ctx.results);
            ctx.resultStatus = "success";
        }

        // 落库执行状态 (paused 已在 markPaused 中落库, 此处覆盖终态)
        ctx.entity.setStepsStateJson(serializeState(ctx.results));
        if (!ctx.paused) {
            ctx.entity.setStatus(anyFailed ? "failed" : "success");
            if (anyFailed && ctx.entity.getError() == null) {
                ctx.entity.setError("存在失败步骤");
            }
        }
        ctx.entity.setCompletedAt(LocalDateTime.now());
        executionRepository.save(ctx.entity);

        log.info("工作流执行完成: exec={}, status={}, 耗时={}ms, 步骤={}",
                ctx.entity.getId(), ctx.resultStatus, System.currentTimeMillis() - ctx.totalStart, ctx.results.size());
        return buildResult(ctx, wf, mode);
    }

    /** 串行: 按顺序执行, 跳过已完成步骤 (续跑), 前一步输出可被后续步骤引用 */
    private void runSequential(ExecCtx ctx, List<WorkflowDtos.WorkflowStep> steps) {
        for (WorkflowDtos.WorkflowStep step : steps) {
            if (ctx.completed.contains(step.getName())) continue;
            String resolvedInput = substituteVars(step.getInput(), ctx.outputs);
            WorkflowDtos.StepResult r = invokeStepWithRetry(step, resolvedInput, ctx.userId);
            ctx.results.add(r);
            ctx.outputs.put(step.getName(), r.getOutput() == null ? "" : r.getOutput());
            persistState(ctx);
            // 人工审批门禁: 成功后暂停
            if (Boolean.TRUE.equals(step.getApproval()) && "success".equals(r.getStatus())) {
                markPaused(ctx, step.getName(), r);
                return;
            }
            if ("failed".equals(r.getStatus())) {
                ctx.entity.setError("步骤 " + step.getName() + " 失败: " + r.getError());
                log.warn("串行工作流在步骤 {} 失败", step.getName());
                return;
            }
        }
    }

    /** 并行: 未完成的步骤并发执行, 已完成步骤复用缓存输出 */
    private void runParallel(ExecCtx ctx, List<WorkflowDtos.WorkflowStep> steps) {
        Map<String, Future<WorkflowDtos.StepResult>> futures = new LinkedHashMap<>();
        for (WorkflowDtos.WorkflowStep step : steps) {
            if (ctx.completed.contains(step.getName())) continue;
            String resolvedInput = substituteVars(step.getInput(), Map.of("user_message", ctx.userInput));
            futures.put(step.getName(), parallelExecutor.submit(() -> invokeStepWithRetry(step, resolvedInput, ctx.userId)));
        }
        for (Map.Entry<String, Future<WorkflowDtos.StepResult>> e : futures.entrySet()) {
            try {
                WorkflowDtos.StepResult r = e.getValue().get();
                ctx.results.add(r);
                ctx.outputs.put(e.getKey(), r.getOutput() == null ? "" : r.getOutput());
            } catch (Exception ex) {
                WorkflowDtos.StepResult r = new WorkflowDtos.StepResult();
                r.setName(e.getKey());
                r.setStatus("failed");
                r.setError("并行执行异常: " + ex.getMessage());
                ctx.results.add(r);
            }
        }
        persistState(ctx);
    }

    /**
     * 条件: 按顺序执行, 每步执行后判断其 condition 表达式;
     * 已完成步骤跳过 (续跑), dependsOn 用于显式声明分支依赖。
     */
    private void runConditional(ExecCtx ctx, List<WorkflowDtos.WorkflowStep> steps) {
        String lastOutput = ctx.userInput;
        // 续跑时从已有结果恢复 lastOutput
        for (int i = ctx.results.size() - 1; i >= 0; i--) {
            WorkflowDtos.StepResult r = ctx.results.get(i);
            if (r.getOutput() != null && !"skipped".equals(r.getStatus())) {
                lastOutput = r.getOutput();
                break;
            }
        }
        for (WorkflowDtos.WorkflowStep step : steps) {
            if (ctx.completed.contains(step.getName())) {
                String out = ctx.outputs.get(step.getName());
                if (out != null && !out.isEmpty()) lastOutput = out;
                continue;
            }
            if (step.getCondition() != null && !step.getCondition().isBlank()) {
                boolean pass = evaluateCondition(step.getCondition(), ctx.outputs, lastOutput);
                if (!pass) {
                    WorkflowDtos.StepResult skipped = new WorkflowDtos.StepResult();
                    skipped.setName(step.getName());
                    skipped.setAgentId(step.getAgentId());
                    skipped.setStatus("skipped");
                    skipped.setInput("(条件不满足, 跳过)");
                    ctx.results.add(skipped);
                    ctx.outputs.put(step.getName(), "");
                    ctx.completed.add(step.getName());
                    persistState(ctx);
                    continue;
                }
            }
            String resolvedInput = substituteVars(step.getInput(), ctx.outputs);
            WorkflowDtos.StepResult r = invokeStepWithRetry(step, resolvedInput, ctx.userId);
            ctx.results.add(r);
            ctx.outputs.put(step.getName(), r.getOutput() == null ? "" : r.getOutput());
            lastOutput = r.getOutput() == null ? "" : r.getOutput();
            persistState(ctx);
            if (Boolean.TRUE.equals(step.getApproval()) && "success".equals(r.getStatus())) {
                markPaused(ctx, step.getName(), r);
                return;
            }
            if ("failed".equals(r.getStatus())) {
                ctx.entity.setError("步骤 " + step.getName() + " 失败: " + r.getError());
                log.warn("条件工作流在步骤 {} 失败", step.getName());
                return;
            }
        }
    }

    // ===== 内部辅助 =====

    /** 带重试的步骤调用 (F-33) */
    private WorkflowDtos.StepResult invokeStepWithRetry(WorkflowDtos.WorkflowStep step, String input, String userId) {
        WorkflowDtos.RetryConfig rc = step.getRetry();
        V3Properties.Execution.Retry defaults = v3Properties.getExecution().getRetry();
        int maxAttempts = (rc != null && rc.getMaxAttempts() != null && rc.getMaxAttempts() > 0)
                ? rc.getMaxAttempts() : defaults.getDefaultMaxAttempts();
        if (maxAttempts < 1) maxAttempts = 1;
        String backoff = (rc != null && rc.getBackoff() != null && !rc.getBackoff().isBlank())
                ? rc.getBackoff() : defaults.getBackoff();
        long initialDelay = (rc != null && rc.getInitialDelayMs() != null && rc.getInitialDelayMs() > 0)
                ? rc.getInitialDelayMs() : defaults.getInitialDelayMs();
        double multiplier = (rc != null && rc.getMultiplier() != null && rc.getMultiplier() > 0)
                ? rc.getMultiplier() : defaults.getMultiplier();

        WorkflowDtos.StepResult last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            last = invokeStep(step, input, userId);
            last.setAttempts(attempt);
            if ("success".equals(last.getStatus())) {
                return last;
            }
            if (attempt < maxAttempts) {
                long delay = computeBackoff(backoff, initialDelay, multiplier, attempt);
                log.warn("步骤 {} 第 {} 次失败, {}ms 后重试: {}", step.getName(), attempt, delay, last.getError());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (last != null) {
            last.setError("重试 " + maxAttempts + " 次仍失败: " + last.getError());
        }
        return last;
    }

    private long computeBackoff(String backoff, long initialDelay, double multiplier, int attempt) {
        return switch (backoff == null ? "exponential" : backoff.toLowerCase()) {
            case "fixed" -> initialDelay;
            case "linear" -> (long) (initialDelay * attempt);
            case "exponential" -> (long) (initialDelay * Math.pow(multiplier, attempt - 1));
            default -> initialDelay;
        };
    }

    /** 调用单步 Agent (创建临时会话, 调用 chatSync, 清理会话) */
    private WorkflowDtos.StepResult invokeStep(WorkflowDtos.WorkflowStep step, String input, String userId) {
        WorkflowDtos.StepResult r = new WorkflowDtos.StepResult();
        r.setName(step.getName());
        r.setAgentId(step.getAgentId());
        r.setInput(input);
        long start = System.currentTimeMillis();
        try {
            try {
                AgentDefinitionEntity agent = agentDefinitionService.loadEntity(step.getAgentId());
                r.setAgentName(agent.getName());
            } catch (Exception ignored) {}
            SessionDTO sess = sessionManager.createSession("workflow:" + step.getName(), null, null, userId);
            ChatRequest req = new ChatRequest();
            req.setSessionId(sess.getId());
            req.setMessage(input);
            req.setAgentId(step.getAgentId());
            req.setStream(false);
            ChatResponse resp = orchestrator.chatSync(req, userId);
            String content = resp.getContent() == null ? "" : resp.getContent();
            r.setOutput(content);
            r.setStatus("success");
            try { sessionManager.deleteSession(sess.getId()); } catch (Exception ignored) {}
        } catch (Exception e) {
            r.setStatus("failed");
            r.setError(e.getMessage());
            log.error("工作流步骤 {} 执行失败", step.getName(), e);
        }
        r.setDurationMs(System.currentTimeMillis() - start);
        return r;
    }

    /** 审批门禁暂停: 持久化状态并标记 ctx.paused */
    private void markPaused(ExecCtx ctx, String stepName, WorkflowDtos.StepResult r) {
        r.setStatus("paused");
        ctx.paused = true;
        ctx.entity.setStatus("paused");
        ctx.entity.setCurrentStep(stepName);
        ctx.entity.setStepsStateJson(serializeState(ctx.results));
        ctx.entity.setCompletedAt(LocalDateTime.now());
        executionRepository.save(ctx.entity);
        log.info("工作流在审批节点 {} 暂停: exec={}", stepName, ctx.entity.getId());
    }

    /** 每步执行后持久化状态 (断点续跑基础) */
    private void persistState(ExecCtx ctx) {
        try {
            ctx.entity.setStepsStateJson(serializeState(ctx.results));
            ctx.entity.setCurrentStep(ctx.results.isEmpty() ? null
                    : ctx.results.get(ctx.results.size() - 1).getName());
            executionRepository.save(ctx.entity);
        } catch (Exception e) {
            log.warn("工作流执行状态持久化失败: {}", e.getMessage());
        }
    }

    /** 从已持久化的执行状态重建上下文 (续跑/审批) */
    private ExecCtx buildCtxFromState(WorkflowExecutionEntity ent, String userId) {
        ExecCtx ctx = new ExecCtx();
        ctx.entity = ent;
        ctx.userInput = ent.getInput() == null ? "" : ent.getInput();
        ctx.userId = userId;
        ctx.totalStart = System.currentTimeMillis();
        ctx.results = deserializeState(ent.getStepsStateJson());
        ctx.outputs.put("user_message", ctx.userInput);
        for (WorkflowDtos.StepResult r : ctx.results) {
            String status = r.getStatus();
            if ("success".equals(status) || "skipped".equals(status) || "paused".equals(status)) {
                ctx.completed.add(r.getName());
            }
            ctx.outputs.put(r.getName(), r.getOutput() == null ? "" : r.getOutput());
        }
        return ctx;
    }

    /** 变量替换: ${user_message} / ${step.output} */
    private String substituteVars(String template, Map<String, String> vars) {
        if (template == null) return "";
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            if (key.endsWith(".output")) key = key.substring(0, key.length() - ".output".length());
            String val = vars.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 简化条件求值 */
    private boolean evaluateCondition(String expr, Map<String, String> vars, String lastOutput) {
        String e = expr.trim();
        if (e.isEmpty()) return true;
        if (e.startsWith("not:")) {
            return !evaluateCondition(e.substring(4), vars, lastOutput);
        }
        Matcher mc = Pattern.compile("contains\\(\\s*([^,]+)\\s*,\\s*\"([^\"]*)\"\\s*\\)").matcher(e);
        if (mc.matches()) {
            String text = resolveText(mc.group(1).trim(), vars, lastOutput);
            return text != null && text.contains(mc.group(2));
        }
        Matcher me = Pattern.compile("equals\\(\\s*([^,]+)\\s*,\\s*\"([^\"]*)\"\\s*\\)").matcher(e);
        if (me.matches()) {
            String text = resolveText(me.group(1).trim(), vars, lastOutput);
            return text != null && text.equals(me.group(2));
        }
        return true;
    }

    private String resolveText(String token, Map<String, String> vars, String lastOutput) {
        if ("prev".equalsIgnoreCase(token) || "prev.output".equalsIgnoreCase(token)) return lastOutput;
        if (vars.containsKey(token)) return vars.get(token);
        if (token.startsWith("${") && token.endsWith("}")) {
            String key = token.substring(2, token.length() - 1);
            if (key.endsWith(".output")) key = key.substring(0, key.length() - ".output".length());
            return vars.getOrDefault(key, "");
        }
        return token;
    }

    /** 取最后一条 success 步骤的输出作为最终输出 */
    private String pickFinalOutput(List<WorkflowDtos.StepResult> results) {
        for (int i = results.size() - 1; i >= 0; i--) {
            WorkflowDtos.StepResult r = results.get(i);
            if ("success".equals(r.getStatus()) && r.getOutput() != null) return r.getOutput();
        }
        return null;
    }

    /** 汇总并行步骤输出 (各步输出按步骤名拼接成 JSON) */
    private String summarizeParallel(List<WorkflowDtos.StepResult> results) {
        Map<String, String> map = new LinkedHashMap<>();
        for (WorkflowDtos.StepResult r : results) {
            map.put(r.getName(), r.getOutput() == null ? "" : r.getOutput());
        }
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return map.toString();
        }
    }

    private String serializeState(List<WorkflowDtos.StepResult> results) {
        try {
            return mapper.writeValueAsString(results);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<WorkflowDtos.StepResult> deserializeState(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, new TypeReference<List<WorkflowDtos.StepResult>>() {});
        } catch (Exception e) {
            log.warn("解析 steps_state_json 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private WorkflowDtos.ExecutionResult buildResult(ExecCtx ctx, WorkflowDefinitionEntity wf, String mode) {
        WorkflowDtos.ExecutionResult r = new WorkflowDtos.ExecutionResult();
        r.setExecutionId(ctx.entity.getId());
        r.setWorkflowId(wf.getId());
        r.setWorkflowName(wf.getName());
        r.setMode(mode);
        r.setTriggerType(ctx.entity.getTriggerType());
        r.setFinalOutput(ctx.finalOutput);
        r.setSteps(ctx.results);
        r.setTotalDurationMs(System.currentTimeMillis() - ctx.totalStart);
        r.setStatus(ctx.resultStatus);
        r.setError(ctx.entity.getError());
        return r;
    }

    private void validate(WorkflowDtos.SaveWorkflowRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException(400, "工作流名称不能为空");
        }
        if (req.getSteps() == null || req.getSteps().isEmpty()) {
            throw new BusinessException(400, "工作流至少需要一步");
        }
        String mode = req.getMode() == null ? "sequential" : req.getMode().toLowerCase();
        if (!"sequential".equals(mode) && !"parallel".equals(mode) && !"conditional".equals(mode)) {
            throw new BusinessException(400, "编排模式必须为 sequential / parallel / conditional");
        }
        Set<String> names = new HashSet<>();
        for (WorkflowDtos.WorkflowStep s : req.getSteps()) {
            if (s.getName() == null || s.getName().isBlank()) {
                throw new BusinessException(400, "每个步骤必须有名称");
            }
            if (!names.add(s.getName())) {
                throw new BusinessException(400, "步骤名重复: " + s.getName());
            }
            // 审批门禁步骤可不指定 Agent (纯门禁); 其余必须有 Agent
            boolean isApproval = Boolean.TRUE.equals(s.getApproval());
            if ((s.getAgentId() == null || s.getAgentId().isBlank()) && !isApproval) {
                throw new BusinessException(400, "步骤 " + s.getName() + " 未指定 Agent (审批门禁步骤除外)");
            }
        }
    }

    private void applyRequest(WorkflowDefinitionEntity e, WorkflowDtos.SaveWorkflowRequest req) {
        e.setName(req.getName().trim());
        e.setDescription(req.getDescription());
        String mode = req.getMode() == null ? "sequential" : req.getMode().toLowerCase();
        e.setMode(mode);
        try {
            e.setStepsJson(mapper.writeValueAsString(req.getSteps()));
        } catch (Exception ex) {
            throw new BusinessException(400, "步骤定义序列化失败: " + ex.getMessage());
        }
        if (req.getEnabled() != null) e.setEnabled(req.getEnabled());
    }

    private List<WorkflowDtos.WorkflowStep> parseSteps(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return mapper.readValue(json, new TypeReference<List<WorkflowDtos.WorkflowStep>>() {});
        } catch (Exception e) {
            log.error("解析工作流 steps_json 失败: {}", json, e);
            return Collections.emptyList();
        }
    }

    private WorkflowDtos.WorkflowView toView(WorkflowDefinitionEntity e, String currentUserId) {
        WorkflowDtos.WorkflowView v = new WorkflowDtos.WorkflowView();
        v.setId(e.getId());
        v.setUserId(e.getUserId());
        v.setName(e.getName());
        v.setDescription(e.getDescription());
        v.setMode(e.getMode());
        v.setSteps(parseSteps(e.getStepsJson()));
        v.setEnabled(e.isEnabled());
        v.setTemplate(e.isTemplate());
        v.setEditable(currentUserId != null && currentUserId.equals(e.getUserId()) && !e.isTemplate());
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        return v;
    }

    private WorkflowDtos.ExecutionView toExecutionView(WorkflowExecutionEntity e, String currentUserId) {
        WorkflowDtos.ExecutionView v = new WorkflowDtos.ExecutionView();
        v.setId(e.getId());
        v.setWorkflowId(e.getWorkflowId());
        // 工作流名 (模板/自己的工作流都可见名)
        try {
            v.setWorkflowName(workflowRepository.findById(e.getWorkflowId())
                    .map(WorkflowDefinitionEntity::getName).orElse("(已删除)"));
        } catch (Exception ex) {
            v.setWorkflowName(null);
        }
        v.setUserId(e.getUserId());
        v.setTriggerType(e.getTriggerType());
        v.setInput(e.getInput());
        v.setStatus(e.getStatus());
        v.setCurrentStep(e.getCurrentStep());
        v.setSteps(deserializeState(e.getStepsStateJson()));
        v.setStartedAt(e.getStartedAt());
        v.setCompletedAt(e.getCompletedAt());
        v.setError(e.getError());
        v.setCostTokens(e.getCostTokens());
        v.setCostAmount(e.getCostAmount() == null ? null : e.getCostAmount().toPlainString());
        // running/paused/failed 状态可续跑/审批/取消
        boolean resumable = currentUserId != null && currentUserId.equals(e.getUserId())
                && ("failed".equals(e.getStatus()) || "paused".equals(e.getStatus()) || "running".equals(e.getStatus()));
        v.setResumable(resumable);
        return v;
    }

    /** 可见性: 模板对所有人可见, 或为创建者本人 */
    private boolean isVisible(WorkflowDefinitionEntity e, String userId) {
        return e.isTemplate() || (userId != null && userId.equals(e.getUserId()));
    }
}
