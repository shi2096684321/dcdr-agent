package com.dcdr.agent.controller;

import com.dcdr.agent.agent.feedback.FeedbackService;
import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.agent.memory.strategy.SessionSummaryStore;
import com.dcdr.agent.agent.memory.strategy.SummaryCompactionStrategy;
import com.dcdr.agent.agent.prompt.ContextLimitPreference;
import com.dcdr.agent.agent.prompt.ContextUsageService;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.agent.session.SessionSource;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.ContextUsage;
import com.dcdr.agent.dto.MessageDTO;
import com.dcdr.agent.dto.SessionDTO;
import com.dcdr.agent.dto.SessionSummaryView;
import com.dcdr.agent.service.ConversationExportService;
import com.dcdr.agent.service.ConversationExportService.Format;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 会话接口 (对话模式)。
 * <p>
 * V2.2: 接入用户认证后, 所有查询/操作按 userId 隔离,
 * 未登录用户(auth.enabled=false)退化为全局可见(V2.1 兼容)。
 * <p>
 * V2.2 权限: 读操作(list/get/messages/export)需 {@code session:read},
 * 写操作(create/delete/rename/update/deleteMessages)需 {@code session:write}。
 * <p>
 * <b>V3.1 F-41 归属校验</b>: 涉及具体会话的端点用 {@code @PreAuthorize} +
 * {@code OwnershipGuard.requireRead/requireWrite} 声明式校验归属 —— 当前用户由 Spring Security
 * 以 SpEL 变量 {@code principal} 提供, 因此方法<b>无需</b>声明 {@code ServerWebExchange} 参数,
 * 也无需任何手工判断。越权与不存在统一返回 404(越权即不存在, 防资源枚举)。
 * <p>
 * 注: {@code @EnableReactiveMethodSecurity} 要求被保护方法返回 {@code Mono}, 故本类端点统一
 * 返回 {@code Mono<Result<T>>}(对外 JSON 契约不变)。
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Tag(name = "会话管理", description = "对话模式的会话管理：创建、查询、删除及消息历史")
public class SessionController {

    private final SessionManager sessionManager;
    private final ChatMemory memory;
    private final ConversationExportService exportService;
    /** V3.1 F-48: 截断历史时同步清理被删消息上的反馈 */
    private final FeedbackService feedbackService;
    /** V3.1 F-41: 响应式读取当前登录用户(替代原先的 ServerWebExchange 参数) */
    private final OwnershipGuard ownershipGuard;
    /** V3.4: 上下文占用快照的读取与"按当前上限重算百分比" */
    private final ContextUsageService contextUsageService;
    /** V3.4: 用户设置的"总上下文大小"(百分比的分母) */
    private final ContextLimitPreference contextLimitPreference;
    /** V3.4: 历史摘要的读写(对应上下文策略中的摘要压缩) */
    private final SessionSummaryStore summaryStore;
    /** V3.4: 读当前上下文策略, 用于标记"这份摘要是否仍参与组上下文" */
    private final com.dcdr.agent.config.AgentProperties agentProperties;

    @Operation(summary = "获取会话列表",
            description = "返回当前用户的可访问会话(认证启用时按 userId 隔离; 未登录返回全部)。"
                    + "V3.3: 默认只返回用户自己开的对话(user); 定时任务 / 工作流自动执行产生的会话"
                    + "需显式传 source=automation 才会返回")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @GetMapping
    public Mono<Result<List<SessionDTO>>> list(
            @Parameter(description = "会话来源: user(默认) | schedule | workflow | automation(两者合并) | all")
            @RequestParam(required = false) String source) {
        List<String> sources = SessionSource.parseFilter(source);
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(sessionManager.listSessions(ctx.userId(), ctx.admin(), sources)));
    }

    @Operation(summary = "创建会话", description = "创建新的对话会话; V2.2 关联当前登录用户")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @PostMapping
    public Mono<Result<SessionDTO>> create(@RequestBody Map<String, String> body) {
        String title = body.get("title");
        String model = body.get("model");
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(sessionManager.createSession(title, model, ctx.userId())));
    }

    @Operation(summary = "获取会话详情")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SESSION', principal)")
    @GetMapping("/{id}")
    public Mono<Result<SessionDTO>> get(@Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        SessionDTO session = sessionManager.getSession(id);
        if (session == null) return Mono.just(Result.error(404, "会话不存在"));
        return Mono.just(Result.success(session));
    }

    @Operation(summary = "删除会话")
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SESSION', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(@Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        sessionManager.deleteSession(id);
        return Mono.just(Result.success());
    }

    @Operation(summary = "重命名会话", description = "修改会话标题，覆盖自动生成的标题")
    @ApiResponse(responseCode = "200", description = "重命名成功")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SESSION', principal)")
    @PutMapping("/{id}/title")
    public Mono<Result<Void>> rename(@Parameter(description = "会话 ID", required = true) @PathVariable String id,
                                     @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) return Mono.just(Result.error(400, "标题不能为空"));
        sessionManager.updateTitle(id, title);
        return Mono.just(Result.success());
    }

    @Operation(summary = "设置会话挂载的知识库",
            description = "知识库挂载属于会话属性(与 model 同级): 刷新、换设备、切会话都不丢; "
                    + "挂上后该会话的每一轮都会基于这些库做强制检索。传空数组表示清空挂载。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = String.class)))
    @PutMapping("/{id}/knowledge-bases")
    public Mono<Result<List<String>>> setKnowledgeBases(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("knowledgeBaseIds");
        // 缺省即清空: PUT 的语义是"设置成这个值", 不传等于没挂库; 想"不表态"请用接口调用时省略整个 body
        List<String> ids = raw instanceof List<?> list
                ? list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList()
                : List.of();
        return ownershipGuard.currentContext().map(ctx -> {
            if (sessionManager.getSession(id, ctx.userId()) == null) {
                return Result.<List<String>>error(404, "会话不存在或无权访问");
            }
            return Result.success(sessionManager.resolveKnowledgeBases(id, ids));
        });
    }

    @Operation(summary = "获取会话消息历史",
            description = "返回指定会话的全部消息记录; V3.1 F-48 起每条助手回答附带当前用户的反馈状态 (feedback: up/down/null)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MessageDTO.class)))
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SESSION', principal)")
    @GetMapping("/{id}/messages")
    public Mono<Result<List<MessageDTO>>> messages(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        // userId 只用于回填"我是否评价过这条回答", 由响应式上下文提供
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(sessionManager.getMessages(id, ctx.userId())));
    }

    @Operation(summary = "获取上下文占用明细 (V3.4)",
            description = "返回该会话最近一轮的上下文占用快照(五段分词 + 本轮生效上限 + 百分比)。"
                    + "快照的分子是先前的实际占用, 分母按<b>当前</b>设置的上限重算, "
                    + "因此用户改过总上下文后这里立刻反映新比例。"
                    + "从未对话过的会话返回 available=false 的空结构(界面显示空态, 而不是「已用 0」)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ContextUsage.class)))
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SESSION', principal)")
    @GetMapping("/{id}/context-usage")
    public Mono<Result<ContextUsage>> contextUsage(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        // 读用户设置 + 会话快照都是阻塞 IO(两次查库), 必须离开 reactor 线程(项目硬约束)
        return ownershipGuard.currentContext()
                .flatMap(ctx -> Mono.fromCallable(() -> {
                    int limit = contextLimitPreference.effectiveLimit(ctx.userId());
                    return Result.success(
                            contextUsageService.fromJson(sessionManager.findContextUsage(id), limit));
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "获取历史摘要 (V3.4)",
            description = "返回该会话当前的上下文压缩摘要(可见/可改/可删)。从未压缩过时 available=false。"
                    + "摘要直接影响模型记得什么, 因此必须对用户可见 —— 否则会话突然「忘事」时无从排查。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionSummaryView.class)))
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SESSION', principal)")
    @GetMapping("/{id}/summary")
    public Mono<Result<SessionSummaryView>> summary(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        // 读会话 + 统计条数是阻塞 IO, 离开 reactor 线程(项目硬约束)
        return Mono.fromCallable(() -> Result.success(summaryView(id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "改写历史摘要 (V3.4)",
            description = "只改摘要正文, 不动「已压缩到第几条」的边界 —— 那是压缩策略的职责。"
                    + "用于模型漏记关键约束时由用户直接补上。会话从未压缩过时返回 400")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionSummaryView.class)))
    @ApiResponse(responseCode = "400", description = "会话尚未压缩过, 或摘要过长")
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SESSION', principal)")
    @PutMapping("/{id}/summary")
    public Mono<Result<SessionSummaryView>> updateSummary(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id,
            @RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                if (!summaryStore.updateText(id, body.get("text"))) {
                    return Result.<SessionSummaryView>error(400, "该会话尚未压缩过, 没有可改写的摘要");
                }
            } catch (IllegalArgumentException e) {
                return Result.<SessionSummaryView>error(400, e.getMessage());
            }
            // 回传完整视图: 界面要同步字数, 省一次 GET
            return Result.success(summaryView(id));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "清除历史摘要 (V3.4)",
            description = "作废当前摘要; 下一轮对话若仍超预算, 会从完整原文重新压缩。"
                    + "这是摘要失准(漏记约束、混进错误结论)时的唯一出口。")
    @ApiResponse(responseCode = "200", description = "已清除")
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SESSION', principal)")
    @DeleteMapping("/{id}/summary")
    public Mono<Result<Void>> clearSummary(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        return Mono.fromCallable(() -> {
            // 走 clearByUser 而不是直接置空: 事件表要能区分"用户清的"与"边界失效自愈的"
            summaryStore.clearByUser(id);
            return Result.<Void>success();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 摘要视图 + "当前是否生效"标记。
     * <p>生效判断放在这里而不是 store 里: 那是"部署选择了哪种策略"的问题, 与摘要自身的存取无关。
     */
    private SessionSummaryView summaryView(String sessionId) {
        SessionSummaryView view = summaryStore.view(sessionId);
        view.setApplied(SummaryCompactionStrategy.NAME.equals(agentProperties.getMemory().getStrategy()));
        return view;
    }

    @Operation(summary = "删除会话中的部分消息", description = "删除 seq >= fromSeq 的消息，用于编辑历史或重试失败时截断上下文")
    @ApiResponse(responseCode = "200", description = "删除成功")
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SESSION', principal)")
    @DeleteMapping("/{id}/messages")
    public Mono<Result<Void>> deleteMessages(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id,
            @Parameter(description = "从此序号(含)开始删除", required = true) @RequestParam int fromSeq) {
        memory.deleteFrom(id, fromSeq);
        // V3.1 F-48: 被截断的消息上的反馈一并清理 —— 否则重新生成后新消息复用同一 seq 会错误继承旧评价
        feedbackService.deleteFromSeq(id, fromSeq);
        return Mono.just(Result.success());
    }

    @Operation(summary = "导出对话", description = "导出会话为 Markdown 或 JSON 文件下载; V2.1 F-27")
    @ApiResponse(responseCode = "200", description = "返回文件流, Content-Disposition 触发浏览器下载")
    @ApiResponse(responseCode = "404", description = "会话不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SESSION', principal)")
    @GetMapping("/{id}/export")
    public Mono<ResponseEntity<byte[]>> export(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id,
            @Parameter(description = "导出格式: markdown | json (默认 markdown)") @RequestParam(defaultValue = "markdown") String format) {
        Format fmt = Format.from(format);
        ConversationExportService.ExportResult result = exportService.export(id, fmt);
        String disposition = "attachment; filename=\"" + result.filename()
                + "\"; filename*=UTF-8''" + java.net.URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);
        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, result.contentType() + "; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(result.content()));
    }
}
