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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Map;

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
                .map(this::toView)
                .toList();
        LlmCallLogDtos.LlmCallLogPageView v = new LlmCallLogDtos.LlmCallLogPageView();
        v.setContent(views);
        v.setPage(p.getNumber());
        v.setSize(p.getSize());
        v.setTotalElements(p.getTotalElements());
        v.setTotalPages(p.getTotalPages());
        return Result.success(v);
    }

    private LlmCallLogDtos.LlmCallLogView toView(LlmCallLogEntity e) {
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
        v.setSteps(parseSteps(e.getStepsJson()));
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }

    private static List<Map<String, Object>> parseSteps(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}