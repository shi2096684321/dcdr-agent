package com.dcdr.agent.controller;

import com.dcdr.agent.agent.workflow.WorkflowEngine;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.config.V3Properties;
import com.dcdr.agent.dto.WebhookDtos;
import com.dcdr.agent.dto.WorkflowDtos;
import com.dcdr.agent.entity.WebhookEndpointEntity;
import com.dcdr.agent.entity.WorkflowDefinitionEntity;
import com.dcdr.agent.model.circuitbreaker.LlmCircuitBreaker;
import com.dcdr.agent.repository.WebhookEndpointRepository;
import com.dcdr.agent.repository.WorkflowDefinitionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * V3.0 Webhook 接口 (F-31 触发层扩展, 对应 V3.0 规划文档 §5.3)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET    /api/webhooks                 当前用户的 Webhook 列表</li>
 *   <li>GET    /api/webhooks/{id}            Webhook 详情</li>
 *   <li>POST   /api/webhooks                 创建 Webhook (绑定工作流)</li>
 *   <li>PUT    /api/webhooks/{id}            更新 Webhook</li>
 *   <li>DELETE /api/webhooks/{id}            删除 Webhook</li>
 *   <li>POST   /api/webhooks/trigger/{token} 触发 Webhook (公开端点, 外部系统调用)</li>
 * </ul>
 * <p>
 * 权限: 管理端点(CRUD) 走 {@code workflow:execute} (与工作流管理同权限);
 * 触发端点 {@code /api/webhooks/trigger/**} 在 SecurityConfig 中放行(无需认证, 靠 token 鉴权)。
 * <p>
 * V3.1 F-41: 管理端点的归属校验用 {@code @PreAuthorize} 声明式完成
 * (当前用户由 SpEL 变量 {@code principal} 提供, <b>无需</b>声明 {@code ServerWebExchange} 参数),
 * 越权与不存在统一返回 404。
 * <p>
 * <b>线程模型</b>: 触发端点会同步执行工作流(LLM 阻塞), 必须切到 boundedElastic 线程池,
 * 避免阻塞 reactor-http-nio (项目硬约束)。
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhook 触发器", description = "V3.0 工作流 Webhook 触发层 (F-31)")
public class WebhookController {

    private final WebhookEndpointRepository webhookRepository;
    /** V3.1 F-41: 响应式读取当前登录用户 + 归属校验 */
    private final OwnershipGuard ownershipGuard;
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowEngine workflowEngine;
    private final LlmCircuitBreaker circuitBreaker;
    private final V3Properties v3Properties;

    // ===== 管理 API (需登录 + workflow:execute 权限) =====

    @Operation(summary = "Webhook 列表", description = "返回当前用户创建的 Webhook 端点")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WebhookDtos.WebhookView.class)))
    @GetMapping
    public Mono<Result<List<WebhookDtos.WebhookView>>> list() {
        return ownershipGuard.currentContext().map(ctx -> {
            String userId = ctx.userId();
            // V3.1 F-41: 管理员返回全部 Webhook; 普通用户仅自己的
            List<WebhookEndpointEntity> entities = ctx.admin()
                    ? webhookRepository.findAllByOrderByCreatedAtDesc()
                    : webhookRepository.findByUserIdOrderByCreatedAtDesc(userId);
            return Result.success(entities.stream().map(e -> toView(e, userId)).toList());
        });
    }

    @Operation(summary = "Webhook 详情")
    @ApiResponse(responseCode = "404", description = "Webhook 不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'WEBHOOK', principal)")
    @GetMapping("/{id}")
    public Mono<Result<WebhookDtos.WebhookView>> get(
            @Parameter(description = "Webhook ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(toView(load(id), ctx.userId())));
    }

    @Operation(summary = "创建 Webhook",
            description = "为指定工作流创建一个 Webhook 触发端点。返回的 URL 可被外部系统 POST 调用以触发该工作流。"
                    + "仅可绑定自己创建的工作流(预设模板不可绑定, 因执行时会以 Webhook 所属用户身份运行)。")
    @PostMapping
    public Mono<Result<WebhookDtos.WebhookView>> create(@RequestBody WebhookDtos.SaveWebhookRequest req) {
        return ownershipGuard.currentContext().map(ctx -> {
            String userId = ctx.userId();
            if (req.getWorkflowId() == null || req.getWorkflowId().isBlank()) {
                throw new BusinessException(400, "workflowId 不能为空");
            }
            // 关联资源校验: 只能绑定自己的工作流(模板不可绑定) —— 越权统一 404, 防资源枚举
            WorkflowDefinitionEntity wf = requireOwnWorkflow(req.getWorkflowId(), userId);

            WebhookEndpointEntity e = new WebhookEndpointEntity();
            e.setId("wh_" + UUID.randomUUID().toString().replace("-", ""));
            e.setUserId(userId);
            e.setWorkflowId(wf.getId());
            e.setName(req.getName());
            e.setToken(UUID.randomUUID().toString().replace("-", ""));
            e.setSecret(req.getSecret());
            e.setEnabled(req.getEnabled() == null ? true : req.getEnabled());
            e.setTriggerCount(0);
            e.setCreatedAt(LocalDateTime.now());
            webhookRepository.save(e);
            log.info("创建 Webhook: id={}, workflow={}, user={}", e.getId(), wf.getId(), userId);
            return Result.success(toView(e, userId));
        });
    }

    @Operation(summary = "更新 Webhook",
            description = "更新 Webhook 名称/secret/启用状态/绑定工作流。token 不可更改。")
    @ApiResponse(responseCode = "404", description = "Webhook 不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'WEBHOOK', principal)")
    @PutMapping("/{id}")
    public Mono<Result<WebhookDtos.WebhookView>> update(
            @Parameter(description = "Webhook ID", required = true) @PathVariable String id,
            @RequestBody WebhookDtos.SaveWebhookRequest req) {
        return ownershipGuard.currentContext().map(ctx -> {
            String userId = ctx.userId();
            WebhookEndpointEntity e = load(id);
            if (req.getWorkflowId() != null && !req.getWorkflowId().isBlank()
                    && !Objects.equals(req.getWorkflowId(), e.getWorkflowId())) {
                e.setWorkflowId(requireOwnWorkflow(req.getWorkflowId(), userId).getId());
            }
            if (req.getName() != null) e.setName(req.getName());
            if (req.getSecret() != null) e.setSecret(req.getSecret());
            if (req.getEnabled() != null) e.setEnabled(req.getEnabled());
            webhookRepository.save(e);
            log.info("更新 Webhook: id={}", e.getId());
            return Result.success(toView(e, userId));
        });
    }

    @Operation(summary = "删除 Webhook")
    @ApiResponse(responseCode = "404", description = "Webhook 不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'WEBHOOK', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(
            @Parameter(description = "Webhook ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            WebhookEndpointEntity e = load(id);
            webhookRepository.delete(e);
            log.info("删除 Webhook: id={}", e.getId());
            return Result.success();
        });
    }

    // ===== 触发 API (公开端点, 靠 token 鉴权; SecurityConfig 中放行 /api/webhooks/trigger/**) =====

    @Operation(summary = "触发 Webhook",
            description = "外部系统通过此端点触发绑定的工作流。请求体可为任意 JSON, 其中 `input` 字段(可选)作为工作流用户输入; "
                    + "若配置了 secret, 需在请求头 `X-Webhook-Secret` 携带相同的值。同步返回工作流执行结果。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkflowDtos.ExecutionResult.class)))
    @PostMapping("/trigger/{token}")
    public Mono<Result<WorkflowDtos.ExecutionResult>> trigger(
            @Parameter(description = "Webhook token (URL 路径携带)", required = true)
            @PathVariable String token,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestHeader(value = "X-Webhook-Id", required = false) String webhookId) {
        // 工作流执行为阻塞 LLM 调用, 必须切到 boundedElastic
        return Mono.fromCallable(() -> doTrigger(token, body, secret, webhookId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    /** 触发逻辑 (同步, 在 boundedElastic 执行) */
    private WorkflowDtos.ExecutionResult doTrigger(String token, Map<String, Object> body, String secret, String webhookId) {
        WebhookEndpointEntity e = webhookRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(404, "Webhook 不存在或 token 无效", "WEBHOOK_NOT_FOUND"));
        if (!e.isEnabled()) {
            throw new BusinessException(403, "Webhook 已禁用", "WEBHOOK_DISABLED");
        }
        if (e.getSecret() != null && !e.getSecret().isBlank()) {
            if (secret == null || !e.getSecret().equals(secret)) {
                throw new BusinessException(401, "Webhook secret 校验失败", "WEBHOOK_SECRET_INVALID");
            }
        }
        // 从请求体提取 input; 兼容 {input: "..."} 或裸字符串或整个 body 序列化
        String input = extractInput(body);
        // 更新触发统计
        e.setTriggerCount(e.getTriggerCount() + 1);
        e.setLastTriggered(LocalDateTime.now());
        webhookRepository.save(e);
        log.info("Webhook 触发: id={}, workflow={}, user={}", e.getId(), e.getWorkflowId(), e.getUserId());
        // F-40.8 幂等: 依赖方携带 X-Webhook-Id 时, 处理过(成功)的同一 id 直接判定重复, 不重复执行。
        // 仅在成功后才保留 id; 执行失败/熔断拒绝会释放, 允许重试(进程内存, 单实例够用)。
        boolean claimed = webhookId != null && !webhookId.isBlank()
                && webhookIds.putIfAbsent(webhookId, System.currentTimeMillis()) == null;
        if (webhookId != null && !webhookId.isBlank() && !claimed) {
            throw new BusinessException(409, "重复的 Webhook 请求 (X-Webhook-Id 已成功处理)", "WEBHOOK_DUPLICATE");
        }
        evictWebhookIds();
        // F-40.4 熔断降级: 以工作流为粒度保护 Webhook 触发的执行链路
        String breakerKey = "webhook:" + e.getWorkflowId();
        circuitBreaker.check(breakerKey);
        try {
            // 以 Webhook 所属用户身份执行工作流
            WorkflowDtos.ExecutionResult result =
                    workflowEngine.executeWithTrigger(e.getWorkflowId(), input, "webhook", e.getUserId());
            circuitBreaker.recordSuccess(breakerKey);
            return result;
        } catch (RuntimeException ex) {
            if (claimed) webhookIds.remove(webhookId); // 失败可重试, 释放幂等占位
            circuitBreaker.recordFailure(breakerKey);
            throw ex;
        }
    }

    /** F-40.8 幂等占位: webhookId -> 最近处理时间戳(成功触发后保留) */
    private final java.util.concurrent.ConcurrentMap<String, Long> webhookIds =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 幂等保留窗口 */
    private static final long IDEMPOTENT_RETENTION_MS = 10 * 60_000L;

    /** 容量过大时清理过期占位 */
    private void evictWebhookIds() {
        if (webhookIds.size() <= 10_000) return;
        long cutoff = System.currentTimeMillis() - IDEMPOTENT_RETENTION_MS;
        webhookIds.entrySet().removeIf(en -> en.getValue() < cutoff);
    }

    /** 从请求体提取工作流输入 */
    @SuppressWarnings("unchecked")
    private static String extractInput(Map<String, Object> body) {
        if (body == null || body.isEmpty()) return "";
        Object input = body.get("input");
        if (input == null) input = body.get("message");
        if (input == null) input = body.get("text");
        if (input != null) return String.valueOf(input);
        // 无显式 input 字段时, 把整个 body 作为输入文本 (外部系统可传裸 JSON)
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (Exception ex) {
            return body.toString();
        }
    }

    // ===== 辅助方法 =====

    /**
     * 加载 Webhook 实体。
     * <p>归属校验已由 {@code @PreAuthorize} 在方法入口完成(越权 404), 此处只负责"取实体"。
     */
    private WebhookEndpointEntity load(String id) {
        return webhookRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Webhook 不存在: " + id, "WEBHOOK_NOT_FOUND"));
    }

    /** 关联资源校验: 目标工作流必须存在且属于当前用户, 且不是预设模板 */
    private WorkflowDefinitionEntity requireOwnWorkflow(String workflowId, String userId) {
        WorkflowDefinitionEntity wf = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + workflowId, "WORKFLOW_NOT_FOUND"));
        if (wf.isTemplate() || !Objects.equals(wf.getUserId(), userId)) {
            // 统一口径: 越权即不存在(404), 避免暴露他人工作流的存在性
            throw new BusinessException(404, "工作流不存在: " + workflowId, "WORKFLOW_NOT_FOUND");
        }
        return wf;
    }

    /** 实体转视图 */
    private WebhookDtos.WebhookView toView(WebhookEndpointEntity e, String userId) {
        WebhookDtos.WebhookView v = new WebhookDtos.WebhookView();
        v.setId(e.getId());
        v.setUserId(e.getUserId());
        v.setWorkflowId(e.getWorkflowId());
        // 回填工作流名
        workflowRepository.findById(e.getWorkflowId()).ifPresent(wf -> v.setWorkflowName(wf.getName()));
        v.setName(e.getName());
        v.setToken(e.getToken());
        v.setUrl(buildUrl(e.getToken()));
        v.setSecret(e.getSecret());
        v.setEnabled(e.isEnabled());
        v.setLastTriggered(e.getLastTriggered());
        v.setTriggerCount(e.getTriggerCount());
        v.setCreatedAt(e.getCreatedAt());
        v.setEditable(Objects.equals(e.getUserId(), userId));
        return v;
    }

    /** 拼装外部可访问的 Webhook URL */
    private String buildUrl(String token) {
        String base = v3Properties.getWebhook().getBaseUrl();
        if (base == null || base.isBlank()) {
            base = "/api/webhooks/trigger";
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + token;
    }
}
