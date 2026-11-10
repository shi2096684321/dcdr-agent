package com.dcdr.agent.controller;

import com.dcdr.agent.agent.memory.strategy.CompactionEvent;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.ContextCompactionDtos;
import com.dcdr.agent.entity.ContextCompactionEntity;
import com.dcdr.agent.repository.ContextCompactionRepository;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 上下文压缩记录查询接口(V3.5)。
 *
 * <p>对应前端「运维台 → 压缩记录」页与对话右侧上下文面板里的「压缩记录」抽屉。
 * 一次压缩/降级/作废/清除/改写落一条, 用于回答:
 * "这个会话什么时候压过、压完省了多少、为什么这次没压成"。
 *
 * <p>权限: 与调用链日志同一口径 —— 普通用户只能查自己(服务端强制按 userId 过滤),
 * 管理员可传 {@code userId=all} 查全部。
 */
@Slf4j
@RestController
@RequestMapping("/api/context-compactions")
@RequiredArgsConstructor
@Tag(name = "上下文压缩记录", description = "上下文压缩事件查询(压缩/降级/作废/清除/改写)")
public class ContextCompactionController {

    private final ContextCompactionRepository repository;

    @Operation(summary = "压缩记录列表 (分页)",
            description = "分页查询上下文压缩事件。可按会话、事件类型筛选; 普通用户仅查自己, 管理员可传 userId=all 查全部。")
    @GetMapping
    public Mono<Result<ContextCompactionDtos.PageView>> list(
            @Parameter(description = "页码 (从 0 开始)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数 (1~100)") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "会话 ID; 不传=全部会话") @RequestParam(required = false) String sessionId,
            @Parameter(description = "事件类型: compacted / degraded / invalidated / cleared / edited; 不传=全部")
            @RequestParam(required = false) String eventType,
            @Parameter(description = "查询全部用户 (仅管理员)") @RequestParam(required = false) String userId,
            ServerWebExchange exchange) {

        // 身份必须在这里同步取: SecurityContext 是 ThreadLocal, 切到 boundedElastic 之后就没了
        String currentUserId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        // 普通用户不接受 userId 入参(只能查自己), 管理员传 all 才放开 —— 与调用链日志一致
        String userFilter = (isAdmin && "all".equalsIgnoreCase(userId)) ? null : currentUserId;
        String sessionFilter = blankToNull(sessionId);
        String eventFilter = normalizeEvent(eventType);

        // 查库是阻塞 IO, 离开 reactor 线程(项目硬约束)
        return Mono.fromCallable(() -> {
            Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
            Page<ContextCompactionEntity> p = repository.findForList(
                    userFilter, sessionFilter, eventFilter, pageable);

            List<ContextCompactionDtos.View> views = p.getContent().stream().map(this::toView).toList();
            ContextCompactionDtos.PageView v = new ContextCompactionDtos.PageView();
            v.setContent(views);
            v.setPage(p.getNumber());
            v.setSize(p.getSize());
            v.setTotalElements(p.getTotalElements());
            v.setTotalPages(p.getTotalPages());
            return Result.success(v);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 解析事件筛选参数。
     * <p><b>非法值必须报错而不是静默返回全部</b>: 传了 {@code degradd}(拼错) 时若返回全部记录,
     * 界面看起来"筛选生效了", 实际是假象 —— 与调用链日志同一口径。
     */
    private static String normalizeEvent(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) return null;
        String code = CompactionEvent.normalize(raw);
        if (code == null) throw new BusinessException(400, "无法识别的压缩事件: " + raw);
        return code;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ContextCompactionDtos.View toView(ContextCompactionEntity e) {
        ContextCompactionDtos.View v = new ContextCompactionDtos.View();
        v.setId(e.getId());
        v.setSessionId(e.getSessionId());
        v.setUserId(e.getUserId());
        v.setEventType(e.getEventType());
        v.setEventLabel(CompactionEvent.labelOf(e.getEventType()));
        v.setReason(e.getReason());
        v.setTriggerTokens(e.getTriggerTokens());
        v.setBeforeTokens(e.getBeforeTokens());
        v.setAfterTokens(e.getAfterTokens());
        v.setSummarizedCount(e.getSummarizedCount());
        v.setKeptCount(e.getKeptCount());
        v.setSummaryChars(e.getSummaryChars());
        v.setBoundarySeq(e.getBoundarySeq());
        v.setModel(e.getModel());
        v.setDurationMs(e.getDurationMs());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }
}
