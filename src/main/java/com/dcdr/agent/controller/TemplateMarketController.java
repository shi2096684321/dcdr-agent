package com.dcdr.agent.controller;

import com.dcdr.agent.agent.market.TemplateMarketService;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.TemplateDtos;
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
 * V3.0-beta 模板市场接口 (F-35 Agent 与工作流模板市场, 文档 §9)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET    /api/market/templates              市场列表 (用户分享 + 预置模板, 筛选/搜索/排序/分页)</li>
 *   <li>GET    /api/market/my-shares              我分享的模板</li>
 *   <li>POST   /api/market/share                  分享自己的 Agent / 工作流到市场</li>
 *   <li>POST   /api/market/templates/{id}/clone   克隆模板为自己名下的副本</li>
 *   <li>POST   /api/market/templates/{id}/rate    评分 (1~5)</li>
 *   <li>DELETE /api/market/templates/{id}         下架分享 (分享者本人或管理员)</li>
 * </ul>
 * <p>
 * 权限: 登录即可浏览; 分享校验目标归属 (仅自己的或管理员), 见 {@link TemplateMarketService}。
 * <p>
 * V3.1 F-41: 「下架分享」用 {@code @PreAuthorize} 校验分享记录归属(越权 404);
 * 「克隆」「评分」是面向全体登录用户的公共操作, 不做归属校验。
 * 当前用户由 SpEL 变量 {@code principal} 提供或经 {@link OwnershipGuard#currentContext()} 读取,
 * <b>无需</b>声明 {@code ServerWebExchange} 参数。
 */
@Slf4j
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
@Tag(name = "模板市场", description = "V3.0-beta Agent/工作流模板分享、搜索、克隆与评分 (F-35)")
public class TemplateMarketController {

    private final TemplateMarketService marketService;
    /** V3.1 F-41: 响应式读取当前登录用户 + 归属校验 */
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "市场模板列表",
            description = "合并展示用户分享模板与系统预置模板。"
                    + "type=agent/workflow, category 精确匹配, keyword 模糊匹配标题/描述, "
                    + "sort=recent/clones/rating (默认 recent)。")
    @GetMapping("/templates")
    public Mono<Result<TemplateDtos.TemplatePageView>> list(
            @Parameter(description = "类型筛选: agent / workflow") @RequestParam(required = false) String type,
            @Parameter(description = "分类筛选") @RequestParam(required = false) String category,
            @Parameter(description = "关键词, 模糊匹配标题/描述") @RequestParam(required = false) String keyword,
            @Parameter(description = "排序: recent / clones / rating") @RequestParam(required = false) String sort,
            @Parameter(description = "页码 (从 0 开始)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数 (1~50)") @RequestParam(defaultValue = "12") int size) {
        return Mono.just(Result.success(marketService.list(type, category, keyword, sort, page, size)));
    }

    @Operation(summary = "我分享的模板", description = "当前用户分享到市场的模板列表 (含已下架前的统计)。")
    @GetMapping("/my-shares")
    public Mono<Result<List<TemplateDtos.TemplateShareView>>> myShares() {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(marketService.myShares(ctx.userId())));
    }

    @Operation(summary = "分享到市场",
            description = "把自己的 Agent / 工作流分享到模板市场。普通用户仅可分享自己的目标; "
                    + "管理员可分享任意目标。同一目标重复分享会更新上架信息。")
    @PostMapping("/share")
    public Mono<Result<TemplateDtos.TemplateShareView>> share(@RequestBody TemplateDtos.ShareRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(marketService.share(req, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "克隆模板",
            description = "克隆市场模板 (用户分享或预置) 为自己名下的副本, 副本可自由编辑。")
    @PostMapping("/templates/{id}/clone")
    public Mono<Result<TemplateDtos.CloneResult>> clone(
            @Parameter(description = "市场模板 ID", required = true) @PathVariable String id) {
        // 克隆面向全体登录用户(目标是市场公开资源), 无需归属校验
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(marketService.clone(id, ctx.userId())));
    }

    @Operation(summary = "评分", description = "对市场模板打分 (1~5), 平均分与评分次数累计更新。")
    @PostMapping("/templates/{id}/rate")
    public Mono<Result<TemplateDtos.TemplateShareView>> rate(
            @Parameter(description = "市场模板 ID", required = true) @PathVariable String id,
            @RequestBody TemplateDtos.RateRequest req) {
        if (req.getRating() == null) {
            throw new BusinessException(400, "评分不能为空");
        }
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(marketService.rate(id, req.getRating(), ctx.userId())));
    }

    @Operation(summary = "下架分享", description = "从市场下架自己的分享; 管理员可下架任意分享。")
    @ApiResponse(responseCode = "404", description = "分享不存在或无权下架")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'TEMPLATE_SHARE', principal)")
    @DeleteMapping("/templates/{id}")
    public Mono<Result<Void>> unshare(
            @Parameter(description = "市场模板 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            marketService.unshare(id, ctx.userId(), ctx.admin());
            return Result.success();
        });
    }
}
