package com.dcdr.agent.controller;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.LlmCallLogDtos;
import com.dcdr.agent.entity.LlmCallLogEntity;
import com.dcdr.agent.repository.LlmCallLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LLM 调用链日志查询接口。
 * <p>
 * 每次对话请求落一条 {@code llm_call_logs}, 记录该次请求触发的整条 LLM 调用链:
 * 路由判断 / 各生成轮 / 工具步骤 / token / 总耗时 / 结束原因。
 * 本接口分页返回, 支撑前端"调用链日志"独立页面(不在对话内查看)。
 * <p>
 * 权限: {@code /api/llm-calls/**}, 归入 {@code /api/** authenticated()} (仅需登录)。
 * 普通用户只查自己; 管理员可传 userId=all 查全部。
 */
@Slf4j
@RestController
@RequestMapping("/api/llm-calls")
@RequiredArgsConstructor
@Tag(name = "调用链日志", description = "LLM 调用链日志查询(replica 独立页面)")
public class LlmCallLogController {

    private final LlmCallLogRepository llmCallLogRepository;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Operation(summary = "调用链日志列表 (分页)",
            description = "分页查询 LLM 调用链日志。管理员可传 userId=all 查全部; 普通用户仅查自己。"
                    + "返回 {content, page, size, totalElements, totalPages}.")
    @GetMapping("/logs")
    public Result<LlmCallLogDtos.LlmCallLogPageView> logs(
            @Parameter(description = "页码 (从 0 开始)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数 (1~100)") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "查询全部用户 (仅管理员)") @RequestParam(required = false) String userId,
            ServerWebExchange exchange) {
        String currentUserId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<LlmCallLogEntity> p;
        if (isAdmin && "all".equalsIgnoreCase(userId)) {
            p = llmCallLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            p = llmCallLogRepository.findByUserIdOrderByCreatedAtDesc(currentUserId, pageable);
        }
        List<LlmCallLogDtos.LlmCallLogView> views = p.getContent().stream()
                .map(e -> toView(e, false))
                .toList();
        LlmCallLogDtos.LlmCallLogPageView v = new LlmCallLogDtos.LlmCallLogPageView();
        v.setContent(views);
        v.setPage(p.getNumber());
        v.setSize(p.getSize());
        v.setTotalElements(p.getTotalElements());
        v.setTotalPages(p.getTotalPages());
        return Result.success(v);
    }

    @Operation(summary = "调用链日志详情",
            description = "按 ID 返回单条日志的**完整**步骤明细(含实际提交的 System Prompt 与完整消息列表, 不做截断)。"
                    + "列表接口只返回步骤元数据, 这两个重字段仅此处返回。")
    @GetMapping("/logs/{id}")
    public Result<LlmCallLogDtos.LlmCallLogView> detail(
            @Parameter(description = "日志 ID", required = true) @PathVariable String id,
            ServerWebExchange exchange) {
        String currentUserId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        LlmCallLogEntity entity = llmCallLogRepository.findById(id).orElse(null);
        // 越权与不存在统一 404(与项目归属校验口径一致, 防止用响应差异枚举他人日志)
        if (entity == null || (!isAdmin && !Objects.equals(entity.getUserId(), currentUserId))) {
            return Result.error(404, "调用链日志不存在");
        }
        return Result.success(toView(entity, true));
    }

    private LlmCallLogDtos.LlmCallLogView toView(LlmCallLogEntity e, boolean full) {
        LlmCallLogDtos.LlmCallLogView v = new LlmCallLogDtos.LlmCallLogView();
        v.setId(e.getId());
        v.setTurnId(e.getTurnId());
        v.setUserId(e.getUserId());
        v.setSessionId(e.getSessionId());
        v.setUserMessage(e.getUserMessage());
        v.setEndSignal(e.getEndSignal());
        v.setLlmCalls(e.getLlmCalls());
        v.setToolSteps(e.getToolSteps());
        v.setTotalInTokens(e.getTotalInTokens());
        v.setTotalOutTokens(e.getTotalOutTokens());
        v.setTotalDurationMs(e.getTotalDurationMs());
        v.setChain(e.getChain());
        v.setSteps(parseSteps(e.getStepsJson(), full));
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }

    /** 步骤中的三个重字段: 完整 System Prompt / 完整消息列表 / 实际请求体 —— 体积大, 仅详情接口返回 */
    private static final String STEP_SYSTEM_PROMPT = "systemPrompt";
    private static final String STEP_MESSAGES = "messages";
    private static final String STEP_WIRE_REQUEST = "wireRequest";

    /**
     * 解析步骤 JSON。
     * <p>
     * 快照落库时<b>不截断</b>(调用链的意义是完整复盘), 因此体积控制在读取侧做:
     * 列表分页只保留步骤元数据(模型/token/耗时/工具名), 剔除 {@code systemPrompt} 与
     * {@code messages} 两个重字段; 需要看全量原文时走详情接口。
     *
     * @param full true = 完整明细(详情接口); false = 剔除重字段(列表接口)
     */
    private static List<Map<String, Object>> parseSteps(String json, boolean full) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Map<String, Object>> steps = MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            if (full) return steps;
            for (Map<String, Object> step : steps) {
                step.remove(STEP_SYSTEM_PROMPT);
                step.remove(STEP_MESSAGES);
                step.remove(STEP_WIRE_REQUEST);
            }
            return steps;
        } catch (Exception e) {
            return null;
        }
    }
}