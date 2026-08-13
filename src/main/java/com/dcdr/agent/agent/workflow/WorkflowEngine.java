package com.dcdr.agent.agent.workflow;

import com.dcdr.agent.agent.AgentOrchestrator;
import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.SessionDTO;
import com.dcdr.agent.dto.WorkflowDtos;
import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.entity.WorkflowDefinitionEntity;
import com.dcdr.agent.repository.WorkflowDefinitionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2.3 工作流引擎 (F-25 多 Agent 协作编排)。
 * <p>
 * 支持三种编排模式 (文档 §5.3):
 * <ul>
 *   <li>sequential: 串行, 按顺序执行, 每步输出可被后续步骤通过 ${step.output} 引用</li>
 *   <li>parallel:   并行, 全部步骤并发执行后汇总, 各步独立互不引用</li>
 *   <li>conditional:条件, 由前一步输出经表达式判断选择后续分支</li>
 * </ul>
 * <p>
 * 变量替换: ${user_message} 替换为用户输入, ${step_name.output} 替换为指定步骤的输出。
 * <p>
 * 每步调用一个自定义 Agent (F-24), 在独立临时会话中执行, 不污染用户主会话历史。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    private final WorkflowDefinitionRepository workflowRepository;
    private final AgentDefinitionService agentDefinitionService;
    private final AgentOrchestrator orchestrator;
    private final SessionManager sessionManager;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 变量占位符正则: ${name} 或 ${name.output} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /** 并行模式线程池 (固定大小, 避免大工作流并发过多 Agent 调用打爆上游) */
    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(4);

    // ===== 工作流 CRUD =====

    public List<WorkflowDtos.WorkflowView> list(String userId) {
        List<WorkflowDefinitionEntity> entities = workflowRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        List<WorkflowDtos.WorkflowView> views = new ArrayList<>(entities.size());
        for (WorkflowDefinitionEntity e : entities) views.add(toView(e));
        return views;
    }

    public WorkflowDtos.WorkflowView get(String id, String userId) {
        WorkflowDefinitionEntity e = workflowRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + id, "WORKFLOW_NOT_FOUND"));
        if (!java.util.Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权访问该工作流", "FORBIDDEN");
        }
        return toView(e);
    }

    @Transactional
    public WorkflowDtos.WorkflowView create(WorkflowDtos.SaveWorkflowRequest req, String userId) {
        validate(req);
        WorkflowDefinitionEntity e = new WorkflowDefinitionEntity();
        e.setId("wf_" + UUID.randomUUID().toString().replace("-", ""));
        e.setUserId(userId);
        applyRequest(e, req);
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        workflowRepository.save(e);
        log.info("创建工作流: {} (name={}, mode={}, steps={})", e.getId(), e.getName(), e.getMode(),
                req.getSteps() != null ? req.getSteps().size() : 0);
        return toView(e);
    }

    @Transactional
    public WorkflowDtos.WorkflowView update(String id, WorkflowDtos.SaveWorkflowRequest req, String userId) {
        WorkflowDefinitionEntity e = workflowRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + id, "WORKFLOW_NOT_FOUND"));
        if (!java.util.Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权修改该工作流", "FORBIDDEN");
        }
        validate(req);
        applyRequest(e, req);
        e.setUpdatedAt(LocalDateTime.now());
        workflowRepository.save(e);
        return toView(e);
    }

    @Transactional
    public void delete(String id, String userId) {
        WorkflowDefinitionEntity e = workflowRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + id, "WORKFLOW_NOT_FOUND"));
        if (!java.util.Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权删除该工作流", "FORBIDDEN");
        }
        workflowRepository.delete(e);
    }

    // ===== 工作流执行 =====

    /**
     * 执行工作流。
     * <p>在工作线程中执行 (调用方应包 boundedElastic / 异步线程, 避免阻塞 reactor-http-nio)。
     */
    public WorkflowDtos.ExecutionResult execute(WorkflowDtos.ExecuteWorkflowRequest req, String userId) {
        WorkflowDefinitionEntity wf = workflowRepository.findById(req.getWorkflowId())
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + req.getWorkflowId(), "WORKFLOW_NOT_FOUND"));
        List<WorkflowDtos.WorkflowStep> steps = parseSteps(wf.getStepsJson());
        if (steps.isEmpty()) {
            throw new BusinessException(400, "工作流未定义任何步骤");
        }
        String userInput = req.getUserInput() == null ? "" : req.getUserInput();
        log.info("执行工作流: id={}, name={}, mode={}, steps={}", wf.getId(), wf.getName(), wf.getMode(), steps.size());

        long totalStart = System.currentTimeMillis();
        List<WorkflowDtos.StepResult> stepResults;
        String finalOutput;
        String status;

        switch (wf.getMode() == null ? "sequential" : wf.getMode().toLowerCase()) {
            case "parallel":
                stepResults = executeParallel(steps, userInput, userId);
                finalOutput = summarizeParallel(stepResults);
                status = stepResults.stream().anyMatch(r -> "failed".equals(r.getStatus())) ? "partial" : "success";
                break;
            case "conditional":
                stepResults = executeConditional(steps, userInput, userId);
                finalOutput = pickFinalOutput(stepResults);
                status = stepResults.stream().anyMatch(r -> "failed".equals(r.getStatus()))
                        ? (finalOutput != null ? "partial" : "failed") : "success";
                break;
            case "sequential":
            default:
                stepResults = executeSequential(steps, userInput, userId);
                finalOutput = pickFinalOutput(stepResults);
                status = stepResults.stream().anyMatch(r -> "failed".equals(r.getStatus()))
                        ? (finalOutput != null ? "partial" : "failed") : "success";
                break;
        }

        WorkflowDtos.ExecutionResult result = new WorkflowDtos.ExecutionResult();
        result.setWorkflowId(wf.getId());
        result.setWorkflowName(wf.getName());
        result.setMode(wf.getMode());
        result.setFinalOutput(finalOutput);
        result.setSteps(stepResults);
        result.setTotalDurationMs(System.currentTimeMillis() - totalStart);
        result.setStatus(status);
        log.info("工作流执行完成: id={}, status={}, 耗时={}ms, 步骤={}", wf.getId(), status, result.getTotalDurationMs(),
                stepResults.size());
        return result;
    }

    // ===== 三种编排模式 =====

    /** 串行: 按顺序执行, 前一步输出可被后续步骤引用 */
    private List<WorkflowDtos.StepResult> executeSequential(List<WorkflowDtos.WorkflowStep> steps,
                                                             String userInput, String userId) {
        Map<String, String> outputs = new HashMap<>();
        outputs.put("user_message", userInput);
        List<WorkflowDtos.StepResult> results = new ArrayList<>();
        for (WorkflowDtos.WorkflowStep step : steps) {
            String resolvedInput = substituteVars(step.getInput(), outputs);
            WorkflowDtos.StepResult r = invokeStep(step, resolvedInput, userId);
            results.add(r);
            // 即使失败也记录输出(空串), 让后续步骤的变量替换不会抛 NPE
            outputs.put(step.getName(), r.getOutput() == null ? "" : r.getOutput());
            if ("failed".equals(r.getStatus())) {
                log.warn("串行工作流在步骤 {} 失败, 终止后续步骤", step.getName());
                break;
            }
        }
        return results;
    }

    /** 并行: 全部步骤并发执行, 各步互不引用 (输入只能用 ${user_message}) */
    private List<WorkflowDtos.StepResult> executeParallel(List<WorkflowDtos.WorkflowStep> steps,
                                                          String userInput, String userId) {
        Map<String, Future<WorkflowDtos.StepResult>> futures = new LinkedHashMap<>();
        for (WorkflowDtos.WorkflowStep step : steps) {
            // 并行模式仅支持 ${user_message}, 无前序步骤引用
            String resolvedInput = substituteVars(step.getInput(), Map.of("user_message", userInput));
            futures.put(step.getName(), parallelExecutor.submit(() -> invokeStep(step, resolvedInput, userId)));
        }
        List<WorkflowDtos.StepResult> results = new ArrayList<>();
        for (WorkflowDtos.WorkflowStep step : steps) {
            try {
                results.add(futures.get(step.getName()).get());
            } catch (Exception e) {
                WorkflowDtos.StepResult r = new WorkflowDtos.StepResult();
                r.setName(step.getName());
                r.setAgentId(step.getAgentId());
                r.setStatus("failed");
                r.setError("并行执行异常: " + e.getMessage());
                results.add(r);
            }
        }
        return results;
    }

    /**
     * 条件: 按顺序执行, 每步执行后判断其 condition 表达式;
     * 若 condition 求值为 false, 跳过该步; dependsOn 用于显式声明分支依赖。
     * <p>条件求值规则 (简化版):
     * <ul>
     *   <li>空 condition: 视为 true (无条件执行)</li>
     *   <li>{@code contains(text, "x")}: 上一步输出 (或 ${prev.output}) 包含 x 时为 true</li>
     *   <li>{@code equals(text, "x")}: 完全相等</li>
     *   <li>{@code not:表达式}: 取反</li>
     *   <li>其它非空字符串: 视为 truthy (true)</li>
     * </ul>
     */
    private List<WorkflowDtos.StepResult> executeConditional(List<WorkflowDtos.WorkflowStep> steps,
                                                             String userInput, String userId) {
        Map<String, String> outputs = new HashMap<>();
        outputs.put("user_message", userInput);
        List<WorkflowDtos.StepResult> results = new ArrayList<>();
        String lastOutput = userInput;
        for (WorkflowDtos.WorkflowStep step : steps) {
            // 条件求值 (基于上一步输出 / 已有变量)
            if (step.getCondition() != null && !step.getCondition().isBlank()) {
                boolean pass = evaluateCondition(step.getCondition(), outputs, lastOutput);
                if (!pass) {
                    WorkflowDtos.StepResult skipped = new WorkflowDtos.StepResult();
                    skipped.setName(step.getName());
                    skipped.setAgentId(step.getAgentId());
                    skipped.setStatus("skipped");
                    skipped.setInput("(条件不满足, 跳过)");
                    results.add(skipped);
                    outputs.put(step.getName(), "");
                    continue;
                }
            }
            String resolvedInput = substituteVars(step.getInput(), outputs);
            WorkflowDtos.StepResult r = invokeStep(step, resolvedInput, userId);
            results.add(r);
            outputs.put(step.getName(), r.getOutput() == null ? "" : r.getOutput());
            lastOutput = r.getOutput() == null ? "" : r.getOutput();
            if ("failed".equals(r.getStatus())) {
                log.warn("条件工作流在步骤 {} 失败, 终止后续步骤", step.getName());
                break;
            }
        }
        return results;
    }

    // ===== 内部辅助 =====

    /** 调用单步 Agent (创建临时会话, 调用 chatSync, 清理会话) */
    private WorkflowDtos.StepResult invokeStep(WorkflowDtos.WorkflowStep step, String input, String userId) {
        WorkflowDtos.StepResult r = new WorkflowDtos.StepResult();
        r.setName(step.getName());
        r.setAgentId(step.getAgentId());
        r.setInput(input);
        long start = System.currentTimeMillis();
        try {
            // 解析 Agent 名称 (供前端展示)
            try {
                AgentDefinitionEntity agent = agentDefinitionService.loadEntity(step.getAgentId());
                r.setAgentName(agent.getName());
            } catch (Exception ignored) {}
            // 创建临时会话 (执行后删除, 不污染用户主会话历史)
            SessionDTO sess = sessionManager.createSession(
                    "workflow:" + step.getName(), null, null, userId);
            ChatRequest req = new ChatRequest();
            req.setSessionId(sess.getId());
            req.setMessage(input);
            req.setAgentId(step.getAgentId());
            req.setStream(false);
            ChatResponse resp = orchestrator.chatSync(req, userId);
            String content = resp.getContent() == null ? "" : resp.getContent();
            r.setOutput(content);
            r.setStatus("success");
            // 删除临时会话 (含其消息), 避免堆积
            try { sessionManager.deleteSession(sess.getId()); } catch (Exception ignored) {}
        } catch (Exception e) {
            r.setStatus("failed");
            r.setError(e.getMessage());
            log.error("工作流步骤 {} 执行失败", step.getName(), e);
        }
        r.setDurationMs(System.currentTimeMillis() - start);
        return r;
    }

    /** 变量替换: ${user_message} / ${step.output} */
    private String substituteVars(String template, Map<String, String> vars) {
        if (template == null) return "";
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            // 支持 name.output 形式
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
        // not: 前缀取反
        if (e.startsWith("not:")) {
            return !evaluateCondition(e.substring(4), vars, lastOutput);
        }
        // contains(text, "x")
        Matcher mc = Pattern.compile("contains\\(\\s*([^,]+)\\s*,\\s*\"([^\"]*)\"\\s*\\)").matcher(e);
        if (mc.matches()) {
            String text = resolveText(mc.group(1).trim(), vars, lastOutput);
            return text != null && text.contains(mc.group(2));
        }
        // equals(text, "x")
        Matcher me = Pattern.compile("equals\\(\\s*([^,]+)\\s*,\\s*\"([^\"]*)\"\\s*\\)").matcher(e);
        if (me.matches()) {
            String text = resolveText(me.group(1).trim(), vars, lastOutput);
            return text != null && text.equals(me.group(2));
        }
        // 其它非空字符串: truthy
        return true;
    }

    private String resolveText(String token, Map<String, String> vars, String lastOutput) {
        if ("prev".equalsIgnoreCase(token) || "prev.output".equalsIgnoreCase(token)) return lastOutput;
        if (vars.containsKey(token)) return vars.get(token);
        // 带 ${} 的占位
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
        // 步骤名唯一性校验
        java.util.Set<String> names = new java.util.HashSet<>();
        for (WorkflowDtos.WorkflowStep s : req.getSteps()) {
            if (s.getName() == null || s.getName().isBlank()) {
                throw new BusinessException(400, "每个步骤必须有名");
            }
            if (!names.add(s.getName())) {
                throw new BusinessException(400, "步骤名重复: " + s.getName());
            }
            if (s.getAgentId() == null || s.getAgentId().isBlank()) {
                throw new BusinessException(400, "步骤 " + s.getName() + " 未指定 Agent");
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

    private WorkflowDtos.WorkflowView toView(WorkflowDefinitionEntity e) {
        WorkflowDtos.WorkflowView v = new WorkflowDtos.WorkflowView();
        v.setId(e.getId());
        v.setUserId(e.getUserId());
        v.setName(e.getName());
        v.setDescription(e.getDescription());
        v.setMode(e.getMode());
        v.setSteps(parseSteps(e.getStepsJson()));
        v.setEnabled(e.isEnabled());
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        return v;
    }
}
