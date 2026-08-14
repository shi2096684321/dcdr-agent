package com.dcdr.agent.controller;

import com.dcdr.agent.agent.workflow.WorkflowEngine;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.config.V3Properties;
import com.dcdr.agent.dto.WebhookDtos;
import com.dcdr.agent.dto.WorkflowDtos;
import com.dcdr.agent.entity.WebhookEndpointEntity;
import com.dcdr.agent.entity.WorkflowDefinitionEntity;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
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
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowEngine workflowEngine;
    private final V3Properties v3Properties;

    // ===== 管理 API (需登录 + workflow:execute 权限) =====

    @Operation(summary = "Webhook 列表", description = "返回当前用户创建的 Webhook 端点")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WebhookDtos.WebhookView.class)))
    @GetMapping
    public Result<List<WebhookDtos.WebhookView>> list(ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        List<WebhookEndpointEntity> entities = webhookRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<WebhookDtos.WebhookView> views = entities.stream().map(e -> toView(e, userId)).toList();
        return Result.success(views);
    }

    @Operation(summary = "Webhook 详情")
    @GetMapping("/{id}")
    public Result<WebhookDtos.WebhookView> get(@Parameter(description = "Webhook ID", required = true)
                                                @PathVariable String id,
                                                ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        WebhookEndpointEntity e = loadOwned(id, userId);
        return Result.success(toView(e, userId));
    }

    @Operation(summary = "创建 Webhook",
            description = "为指定工作流创建一个 Webhook 触发端点。返回的 URL 可被外部系统 POST 调用以触发该工作流。"
                    + "仅可绑定自己创建的工作流(预设模板不可绑定, 因执行时会以 Webhook 所属用户身份运行)。")
    @PostMapping
    public Result<WebhookDtos.WebhookView> create(@RequestBody WebhookDtos.SaveWebhookRequest req,
                                                  ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        if (req.getWorkflowId() == null || req.getWorkflowId().isBlank()) {
            throw new BusinessException(400, "workflowId 不能为空");
        }
        // 校验工作流归属: 只能绑定自己的工作流 (模板不可绑定)
        WorkflowDefinitionEntity wf = workflowRepository.findById(req.getWorkflowId())
                .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + req.getWorkflowId(), "WORKFLOW_NOT_FOUND"));
        if (wf.isTemplate() || !Objects.equals(wf.getUserId(), userId)) {
            throw new BusinessException(403, "只能为自己的工作流创建 Webhook", "FORBIDDEN");
        }

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
    }

    @Operation(summary = "更新 Webhook",
            description = "更新 Webhook 名称/secret/启用状态/绑定工作流。token 不可更改。")
    @PutMapping("/{id}")
    public Result<WebhookDtos.WebhookView> update(@Parameter(description = "Webhook ID", required = true)
                                                   @PathVariable String id,
                                                   @RequestBody WebhookDtos.SaveWebhookRequest req,
                                                   ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        WebhookEndpointEntity e = loadOwned(id, userId);
        if (req.getWorkflowId() != null && !req.getWorkflowId().isBlank()
                && !Objects.equals(req.getWorkflowId(), e.getWorkflowId())) {
            WorkflowDefinitionEntity wf = workflowRepository.findById(req.getWorkflowId())
                    .orElseThrow(() -> new BusinessException(404, "工作流不存在: " + req.getWorkflowId(), "WORKFLOW_NOT_FOUND"));
            if (wf.isTemplate() || !Objects.equals(wf.getUserId(), userId)) {
                throw new BusinessException(403, "只能绑定自己的工作流", "FORBIDDEN");
            }
            e.setWorkflowId(wf.getId());
        }
        if (req.getName() != null) e.setName(req.getName());
        if (req.getSecret() != null) e.setSecret(req.getSecret());
        if (req.getEnabled() != null) e.setEnabled(req.getEnabled());
        webhookRepository.save(e);
        log.info("更新 Webhook: id={}", e.getId());
        return Result.success(toView(e, userId));
    }

    @Operation(summary = "删除 Webhook")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "Webhook ID", required = true)
                                @PathVariable String id,
                                ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        WebhookEndpointEntity e = loadOwned(id, userId);
        webhookRepository.delete(e);
        log.info("删除 Webhook: id={}", e.getId());
        return Result.success();
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
            ServerWebExchange exchange) {
        // 工作流执行为阻塞 LLM 调用, 必须切到 boundedElastic
        return Mono.fromCallable(() -> doTrigger(token, body, secret))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    /** 触发逻辑 (同步, 在 boundedElastic 执行) */
    private WorkflowDtos.ExecutionResult doTrigger(String token, Map<String, Object> body, String secret) {
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
        // 以 Webhook 所属用户身份执行工作流
        return workflowEngine.executeWithTrigger(e.getWorkflowId(), input, "webhook", e.getUserId());
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

    /** 加载并校验归属 (仅创建者本人可管理) */
    private WebhookEndpointEntity loadOwned(String id, String userId) {
        WebhookEndpointEntity e = webhookRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Webhook 不存在: " + id, "WEBHOOK_NOT_FOUND"));
        if (!Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(403, "无权访问该 Webhook", "FORBIDDEN");
        }
        return e;
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
