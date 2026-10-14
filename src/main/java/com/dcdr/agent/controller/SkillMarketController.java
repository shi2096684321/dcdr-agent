package com.dcdr.agent.controller;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.SkillMarketDtos;
import com.dcdr.agent.skill.market.SkillMarketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 技能市场接口 (V3.1)。
 * <p>
 * 端点:
 * <ul>
 *   <li>GET  /api/skill-market/status                  市场是否启用(前端据此决定显示内容还是提示)</li>
 *   <li>GET  /api/skill-market/categories              一级分类</li>
 *   <li>GET  /api/skill-market/skills                  列表(分页 + 关键词 + 分类)</li>
 *   <li>GET  /api/skill-market/search                  关键词搜索</li>
 *   <li>GET  /api/skill-market/skills/{slug}           详情(含版本历史与安全扫描结论)</li>
 *   <li>POST /api/skill-market/install                 引入到我的技能(元数据骨架, 自动停用)</li>
 * </ul>
 * <p>
 * <b>为什么全链路都在后端中转, 不让前端直连 SkillHub</b>: 三个理由, 缺一不可 ——
 * ①分类/详情的缓存只在服务端有意义(直连等于每次点击都打上游);
 * ②"已安装"标记需要本地技能库, 前端拿不到;
 * ③引入必须落到本项目自己的校验与入库路径上, 前端直连等于绕开 {@code SkillPackageService}
 * 的全部约束(大小上限、资源白名单、配额), 那是这条链路上唯一的安全闸门。
 * <p>
 * 与 {@link SkillController} 同口径: 阻塞调用(上游 HTTP + JDBC)统一切到 boundedElastic,
 * 避免占住 reactor 事件循环线程。
 * <p>
 * <b>启用开关</b>: 市场默认关闭。关闭时 {@code /status} 仍可用(返回 enabled=false),
 * 其余端点抛 503 并给出"需开启 agent.skill.hub.enabled"的明确提示。
 */
@Slf4j
@RestController
@RequestMapping("/api/skill-market")
@RequiredArgsConstructor
@Tag(name = "技能市场", description = "V3.1: 从 SkillHub 发现技能并引入 (可选外部依赖, 默认关闭)")
public class SkillMarketController {

    private final SkillMarketService marketService;
    private final OwnershipGuard ownershipGuard;

    /**
     * 市场可用状态。
     * <p>
     * 刻意不受 {@code enabled} 限制: 前端需要先知道"能不能用"才能决定是渲染市场还是渲染一段
     * 启用说明。若这里也抛 503, 前端只能把"未启用"和"上游挂了"当成同一个错误显示。
     */
    @Operation(summary = "市场状态", description = "返回市场是否启用; 未启用时前端应显示配置提示而不是报错")
    @GetMapping("/status")
    public Mono<Result<Map<String, Object>>> status() {
        return Mono.just(Result.success(Map.of("enabled", marketService.isEnabled())));
    }

    @Operation(summary = "市场分类", description = "SkillHub 的一级分类; 可直接用作本地技能的 categoryId")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillMarketDtos.MarketCategory.class)))
    @GetMapping("/categories")
    public Mono<Result<List<SkillMarketDtos.MarketCategory>>> categories() {
        return blocking(() -> marketService.categories());
    }

    @Operation(summary = "排序方式", description = "可选排序方式(静态列表, 不依赖上游); 前端据此渲染排序筛选器")
    @GetMapping("/sorts")
    public Mono<Result<List<SkillMarketDtos.SortOption>>> sorts() {
        return Mono.just(Result.success(marketService.sorts()));
    }

    @Operation(summary = "市场技能列表",
            description = "分页 + 关键词 + 分类筛选 + 排序; page 从 0 开始(与 /api/market/templates 同口径)。"
                    + "排序值非法时静默回落默认, 实际生效值见返回体的 sort 字段")
    @GetMapping("/skills")
    public Mono<Result<SkillMarketDtos.MarketPage>> list(
            @Parameter(description = "页码, 从 0 开始") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "12") int pageSize,
            @Parameter(description = "关键词(可选)") @RequestParam(required = false) String keyword,
            @Parameter(description = "分类 key(可选)") @RequestParam(required = false) String category,
            @Parameter(description = "排序 key(可选, 见 /sorts): default|downloads|installs|stars|recent")
            @RequestParam(required = false) String sort) {
        return ownershipGuard.currentContext().flatMap(ctx ->
                blocking(() -> marketService.list(page, pageSize, keyword, category, sort, ctx.userId())));
    }

    @Operation(summary = "搜索市场技能", description = "上游搜索接口不返回总数; 结果字段与列表接口略有差异(已由映射层容错)")
    @GetMapping("/search")
    public Mono<Result<List<SkillMarketDtos.MarketSkillView>>> search(
            @Parameter(description = "搜索关键词", required = true) @RequestParam String q,
            @Parameter(description = "返回条数") @RequestParam(defaultValue = "20") int limit) {
        return ownershipGuard.currentContext().flatMap(ctx ->
                blocking(() -> marketService.search(q, limit, ctx.userId())));
    }

    @Operation(summary = "市场技能详情", description = "含版本历史与第三方安全扫描结论(该结论仅供参考, 不替代本地判断)")
    @ApiResponse(responseCode = "404", description = "上游不存在该技能")
    @GetMapping("/skills/{slug}")
    public Mono<Result<SkillMarketDtos.MarketSkillDetail>> detail(
            @Parameter(description = "技能标识(slug)", required = true) @PathVariable String slug,
            @Parameter(description = "命名空间 handle(可选)") @RequestParam(required = false) String namespace) {
        return ownershipGuard.currentContext().flatMap(ctx ->
                blocking(() -> marketService.detail(slug, namespace, ctx.userId())));
    }

    @Operation(summary = "引入市场技能",
            description = "把市场技能的元数据引入为本地技能。注意: 市场公开接口不提供技能正文, "
                    + "故引入的是'元数据骨架 + 待补全正文', 且会以停用状态入库 —— "
                    + "需在技能管理中补全正文后手工启用。同名技能默认拒绝, 传 overwrite=true 才会覆盖 "
                    + "(会丢弃你已补全的正文, 慎用)")
    @PostMapping("/install")
    public Mono<Result<SkillMarketDtos.InstallResult>> install(
            @RequestBody SkillMarketDtos.InstallRequest req) {
        return ownershipGuard.currentContext().flatMap(ctx ->
                blocking(() -> marketService.install(req, ctx.userId())));
    }

    /** 阻塞调用统一调度: 上游 HTTP 与 JDBC 都是阻塞的, 不能跑在 reactor 线程上 */
    private <T> Mono<Result<T>> blocking(java.util.function.Supplier<T> action) {
        return Mono.fromCallable(() -> Result.success(action.get()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
