package com.dcdr.agent.controller;

import com.dcdr.agent.agent.feedback.FeedbackService;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.FeedbackDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * V3.1 回答反馈接口 (F-48 点赞/点踩收集)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET    /api/feedback/reason-tags  点踩原因标签清单 (前后端口径一致)</li>
 *   <li>POST   /api/feedback              提交/更新对某条回答的评价 (up / down)</li>
 *   <li>DELETE /api/feedback              撤销评价 (幂等)</li>
 *   <li>GET    /api/feedback/stats        反馈概览 (好评率/原因分布/按模型/按天/最近差评)</li>
 *   <li>GET    /api/feedback/list         反馈明细分页</li>
 * </ul>
 * <p>
 * 权限: 登录即可提交自己的反馈; 概览与明细默认仅本人, 管理员传 {@code scope=global} 可看全局
 * (与 {@link MonitorController} 同口径)。
 * <p>
 * V3.1 F-41: 提交/撤销的归属校验用 {@code @PreAuthorize} 声明式完成 —— 反馈依附于会话,
 * 故直接校验 {@code SESSION} 归属(会话 ID 取自请求参数, SpEL 用 {@code #sessionId}),
 * 越权与不存在统一返回 404; 底层 {@link FeedbackService} 亦会二次校验(纵深防御)。
 * 当前用户由 SpEL 变量 {@code principal} 提供, <b>无需</b>声明 {@code ServerWebExchange} 参数。
 * <p>
 * 本版定位为"收集": 数据消费 (差评转回归用例 / 调提示词 / 调路由权重) 属后续版本。
 */
@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@Tag(name = "回答反馈", description = "V3.1 回答点赞/点踩收集与归因概览 (F-48)")
public class FeedbackController {

    private final FeedbackService feedbackService;
    /** V3.1 F-41: 响应式读取当前登录用户 */
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "点踩原因标签清单",
            description = "返回可选的差评原因 (code + 展示名)。前端应使用本接口渲染标签, 避免前后端硬编码不一致。")
    @GetMapping("/reason-tags")
    public Mono<Result<List<FeedbackDtos.ReasonTag>>> reasonTags() {
        return Mono.just(Result.success(feedbackService.reasonTags()));
    }

    @Operation(summary = "提交/更新回答反馈",
            description = "对某条助手回答点赞(up)或点踩(down)。以 (sessionId, messageSeq) 定位回答, "
                    + "同一用户重复提交会覆盖原评价。点踩可携带原因标签与补充说明; "
                    + "提交时自动快照该回答的模型/工具/来源数, 供后续归因。")
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#req.sessionId, 'SESSION', principal)")
    @PostMapping
    public Mono<Result<FeedbackDtos.FeedbackView>> submit(@RequestBody FeedbackDtos.FeedbackRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(feedbackService.submit(req, ctx.userId())));
    }

    @Operation(summary = "撤销回答反馈", description = "取消自己对某条回答的评价, 幂等 (不存在时同样返回成功)。")
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#sessionId, 'SESSION', principal)")
    @DeleteMapping
    public Mono<Result<Void>> withdraw(
            @Parameter(description = "会话 ID", required = true) @RequestParam String sessionId,
            @Parameter(description = "回答的会话内序号", required = true) @RequestParam Integer messageSeq) {
        return ownershipGuard.currentContext().map(ctx -> {
            feedbackService.withdraw(sessionId, messageSeq, ctx.userId());
            return Result.success();
        });
    }

    @Operation(summary = "反馈概览",
            description = "统计窗口内的好评率、原因分布、按模型聚合、按天趋势与最近差评明细。"
                    + "普通用户仅统计自己的反馈; 管理员传 scope=global 查看全局。")
    @GetMapping("/stats")
    public Mono<Result<FeedbackDtos.StatsView>> stats(
            @Parameter(description = "统计窗口天数 (1~365)") @RequestParam(defaultValue = "30") int days,
            @Parameter(description = "global=全局(仅管理员), 默认仅自己") @RequestParam(required = false) String scope) {
        return ownershipGuard.currentContext().map(ctx -> {
            boolean global = "global".equalsIgnoreCase(scope) && ctx.admin();
            return Result.success(feedbackService.stats(global ? null : ctx.userId(), days));
        });
    }

    @Operation(summary = "反馈明细",
            description = "按时间倒序分页返回反馈明细, 可按 rating 过滤 (up / down)。"
                    + "普通用户仅可见自己的反馈; 管理员传 scope=global 查看全部。")
    @GetMapping("/list")
    public Mono<Result<FeedbackDtos.PageView>> list(
            @Parameter(description = "过滤评价类型: up / down, 留空为全部") @RequestParam(required = false) String rating,
            @Parameter(description = "页码 (从 0 开始)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数 (1~100)") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "global=全局(仅管理员), 默认仅自己") @RequestParam(required = false) String scope) {
        return ownershipGuard.currentContext().map(ctx -> {
            boolean global = "global".equalsIgnoreCase(scope) && ctx.admin();
            return Result.success(feedbackService.list(global ? null : ctx.userId(), rating, page, size));
        });
    }
}
