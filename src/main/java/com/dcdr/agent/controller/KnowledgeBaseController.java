package com.dcdr.agent.controller;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.DocumentEntity;
import com.dcdr.agent.entity.KnowledgeBaseEntity;
import com.dcdr.agent.rag.dto.ChunkPageResult;
import com.dcdr.agent.rag.dto.RetrievalResult;
import com.dcdr.agent.rag.dto.SplitPreviewResult;
import com.dcdr.agent.rag.splitter.ChunkStrategy;
import com.dcdr.agent.rag.service.DocumentService;
import com.dcdr.agent.rag.service.SplitPreviewService;
import com.dcdr.agent.rag.service.KnowledgeBaseService;
import com.dcdr.agent.rag.service.RetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口 (V2.1, 对应 F-21-001/002/003; V2.2 接入用户隔离)。
 * <p>
 * 端点清单(与 V2.0 规划文档 §10.1 对齐):
 * <ul>
 *   <li>GET    /api/knowledge-bases                          知识库列表</li>
 *   <li>GET    /api/knowledge-bases/splitter-options         可选的切分策略列表</li>
 *   <li>POST   /api/knowledge-bases/split-preview            切分预览(不入库)</li>
 *   <li>POST   /api/knowledge-bases                          创建知识库</li>
 *   <li>GET    /api/knowledge-bases/{id}                     知识库详情</li>
 *   <li>PUT    /api/knowledge-bases/{id}                     更新知识库</li>
 *   <li>DELETE /api/knowledge-bases/{id}                     删除知识库(级联)</li>
 *   <li>POST   /api/knowledge-bases/{id}/documents           上传文档(异步入库)</li>
 *   <li>GET    /api/knowledge-bases/{id}/documents           文档列表</li>
 *   <li>GET    /api/knowledge-bases/{id}/documents/{docId}/chunks  查看文档分块</li>
 *   <li>DELETE /api/knowledge-bases/{id}/documents/{docId}   删除文档</li>
 *   <li>POST   /api/knowledge-bases/{id}/documents/{docId}/reprocess  重新入库</li>
 *   <li>POST   /api/knowledge-bases/{id}/search              检索测试</li>
 *   <li>POST   /api/knowledge-bases/search                   多库合并检索测试</li>
 * </ul>
 * <p>
 * <b>开放范围与越权校验</b>: 知识库已开放给普通用户(user 角色, 拥有 kb:read/kb:write):
 * 可创建/管理自己的 USER 级知识库; PLATFORM 级知识库仅 admin 可写, 普通用户只读。
 * <p>
 * <b>V3.1 F-41 归属校验</b>: 涉及具体知识库的端点用 {@code @PreAuthorize} 声明式校验归属
 * (当前用户由 SpEL 变量 {@code principal} 提供, <b>无需</b>声明 {@code ServerWebExchange} 参数),
 * 越权与不存在统一返回 404; 需要 userId/isAdmin 的业务调用由
 * {@link OwnershipGuard#currentContext()} 响应式提供。Service 层 {@code getAccessible} 仍会
 * 二次校验(纵深防御), 两者口径一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库 CRUD、文档上传/管理、检索测试（V2.1）")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;
    private final RetrievalService retrievalService;
    private final SplitPreviewService splitPreviewService;
    /** V3.1 F-41: 响应式读取当前登录用户 + 归属校验 */
    private final OwnershipGuard ownershipGuard;

    // ===== 知识库 CRUD (F-21-001) =====

    @Operation(summary = "知识库列表",
            description = "管理员返回全部(含 PLATFORM); 普通用户仅返回自己 USER 级 + 全部 PLATFORM")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KnowledgeBaseEntity.class)))
    @GetMapping
    public Mono<Result<List<KnowledgeBaseEntity>>> list() {
        return ownershipGuard.currentContext().map(ctx -> {
            if (ctx.admin()) {
                return Result.success(knowledgeBaseService.listAll());
            }
            // 普通用户: 自己的 USER 级 + 所有 PLATFORM 级
            List<KnowledgeBaseEntity> userKbs = new ArrayList<>(knowledgeBaseService.list(ctx.userId()));
            List<KnowledgeBaseEntity> platformKbs = knowledgeBaseService.listPlatform();
            if (platformKbs != null && !platformKbs.isEmpty()) {
                userKbs.addAll(platformKbs);
            }
            return Result.success(userKbs);
        });
    }

    /**
     * 可选的切分策略列表: 前端下拉框直接取此接口, 避免两端各维护一份枚举。
     * 纯元数据, 不涉及用户数据, 无需鉴权。
     */
    @Operation(summary = "可选的切分策略", description = "返回 fixed / recursive / sentence / markdown 四种策略的 code、名称与说明")
    @GetMapping("/splitter-options")
    public Mono<Result<List<Map<String, String>>>> splitterOptions() {
        return Mono.just(Result.success(ChunkStrategy.all().stream()
                .map(s -> Map.of("code", s.getCode(), "label", s.getLabel(), "description", s.getDescription()))
                .toList()));
    }

    /**
     * 切分预览: 纯计算, 不入库、不向量化。调参时先看效果, 避免反复上传文档等入库。
     */
    @Operation(summary = "切分预览",
            description = "按给定策略/参数对样本文本试切分, 返回片段与统计; 不入库、不向量化, 无副作用")
    @ApiResponse(responseCode = "400", description = "文本为空/过长, 或策略、参数非法")
    @PostMapping("/split-preview")
    public Mono<Result<SplitPreviewResult>> splitPreview(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> splitPreviewService.preview(
                        str(body.get("text")),
                        str(body.get("chunkStrategy")),
                        intOrNull(body.get("chunkSize")),
                        intOrNull(body.get("chunkOverlap")),
                        intOrNull(body.get("maxChunks"))))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "创建知识库", description = "创建空知识库; visibility: USER/PLATFORM; 可选分块参数 chunkStrategy/chunkSize/chunkOverlap(缺省取全局配置)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KnowledgeBaseEntity.class)))
    @ApiResponse(responseCode = "400", description = "名称为空 / 分块策略或参数非法")
    @ApiResponse(responseCode = "403", description = "非管理员不能创建 PLATFORM 知识库")
    @ApiResponse(responseCode = "503", description = "RAG 未启用")
    @PostMapping
    public Mono<Result<KnowledgeBaseEntity>> create(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        String description = str(body.get("description"));
        String visibility = str(body.get("visibility"));
        String chunkStrategy = str(body.get("chunkStrategy"));
        Integer chunkSize = intOrNull(body.get("chunkSize"));
        Integer chunkOverlap = intOrNull(body.get("chunkOverlap"));
        return ownershipGuard.currentContext().map(ctx -> {
            // PLATFORM 知识库仅管理员可创建
            if ("PLATFORM".equalsIgnoreCase(visibility) && !ctx.admin()) {
                return Result.<KnowledgeBaseEntity>error(403, "仅管理员可创建平台级知识库", "FORBIDDEN");
            }
            return Result.success(knowledgeBaseService.create(name, description, ctx.userId(), visibility,
                    chunkStrategy, chunkSize, chunkOverlap));
        });
    }

    @Operation(summary = "知识库详情", description = "仅可查看自己 USER 级或平台级(只读); admin 可查看任意")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KnowledgeBaseEntity.class)))
    @ApiResponse(responseCode = "404", description = "知识库不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'KNOWLEDGE_BASE', principal)")
    @GetMapping("/{id}")
    public Mono<Result<KnowledgeBaseEntity>> get(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx ->
                Result.success(knowledgeBaseService.getAccessible(id, ctx.userId(), ctx.admin(), false)));
    }

    @Operation(summary = "更新知识库", description = "更新名称/描述/分块配置(仅自己 USER 级; 平台级仅 admin); 分块参数变更后需重新入库文档才生效")
    @ApiResponse(responseCode = "400", description = "分块策略或参数非法")
    @ApiResponse(responseCode = "404", description = "知识库不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'KNOWLEDGE_BASE', principal)")
    @PutMapping("/{id}")
    public Mono<Result<KnowledgeBaseEntity>> update(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return ownershipGuard.currentContext().map(ctx ->
                Result.success(knowledgeBaseService.update(id, str(body.get("name")), str(body.get("description")),
                        ctx.userId(), ctx.admin(), str(body.get("chunkStrategy")),
                        intOrNull(body.get("chunkSize")), intOrNull(body.get("chunkOverlap")))));
    }

    @Operation(summary = "删除知识库", description = "级联删除文档、向量及落盘文件(仅自己 USER 级; 平台级仅 admin)")
    @ApiResponse(responseCode = "404", description = "知识库不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'KNOWLEDGE_BASE', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            knowledgeBaseService.delete(id, ctx.userId(), ctx.admin());
            return Result.success();
        });
    }

    // ===== 文档管理 (F-21-002) =====

    @Operation(summary = "上传文档", description = "上传文档到指定知识库; 同步落盘落库(pending)后异步入库(解析+分块+向量化)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DocumentEntity.class)))
    @ApiResponse(responseCode = "400", description = "格式不支持/超大小/超数量上限")
    @ApiResponse(responseCode = "404", description = "知识库不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'KNOWLEDGE_BASE', principal)")
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Result<DocumentEntity>> upload(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id,
            @Parameter(description = "上传的文件", required = true,
                    schema = @Schema(type = "string", format = "binary"))
            @RequestPart("file") FilePart file) {
        // 同步段从响应式上下文取 userId/isAdmin, 再进入异步落盘链路
        return ownershipGuard.currentContext()
                .flatMap(ctx -> documentService.upload(id, file, ctx.userId(), ctx.admin())
                        .map(Result::success));
    }

    @Operation(summary = "文档列表", description = "返回知识库内全部文档(含状态、分块数、大小; 需有读权限)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DocumentEntity.class)))
    @ApiResponse(responseCode = "404", description = "知识库不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'KNOWLEDGE_BASE', principal)")
    @GetMapping("/{id}/documents")
    public Mono<Result<List<DocumentEntity>>> listDocuments(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx ->
                Result.success(documentService.listDocuments(id, ctx.userId(), ctx.admin())));
    }

    /**
     * 查看某文档已入库的分块(真实内容, 不含向量), 用于核对切分效果与检索排障。
     */
    @Operation(summary = "查看分块", description = "分页查看文档已入库的分块内容与 token 数; 不返回向量")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ChunkPageResult.class)))
    @ApiResponse(responseCode = "404", description = "知识库或文档不存在/无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'KNOWLEDGE_BASE', principal)")
    @GetMapping("/{id}/documents/{docId}/chunks")
    public Mono<Result<ChunkPageResult>> listChunks(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id,
            @Parameter(description = "文档 ID", required = true) @PathVariable String docId,
            @Parameter(description = "页码, 从 0 开始") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数(1~100)") @RequestParam(defaultValue = "20") int size) {
        return ownershipGuard.currentContext().map(ctx ->
                Result.success(documentService.listChunks(id, docId, ctx.userId(), ctx.admin(), page, size)));
    }

    @Operation(summary = "删除文档", description = "删除文档及对应的向量与落盘文件(仅自己 USER 级; 平台级仅 admin)")
    @ApiResponse(responseCode = "404", description = "知识库或文档不存在/无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'KNOWLEDGE_BASE', principal)")
    @DeleteMapping("/{id}/documents/{docId}")
    public Mono<Result<Void>> deleteDocument(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id,
            @Parameter(description = "文档 ID", required = true) @PathVariable String docId) {
        return ownershipGuard.currentContext().map(ctx -> {
            documentService.deleteDocument(docId, ctx.userId(), ctx.admin());
            return Result.success();
        });
    }

    @Operation(summary = "重新入库", description = "清空旧分块后重新解析+向量化(仅自己 USER 级; 平台级仅 admin)")
    @ApiResponse(responseCode = "404", description = "知识库或文档不存在/无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'KNOWLEDGE_BASE', principal)")
    @PostMapping("/{id}/documents/{docId}/reprocess")
    public Mono<Result<Void>> reprocess(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id,
            @Parameter(description = "文档 ID", required = true) @PathVariable String docId) {
        return ownershipGuard.currentContext().map(ctx -> {
            documentService.reprocess(docId, ctx.userId(), ctx.admin());
            return Result.success();
        });
    }

    // ===== 检索测试 (F-21-003) =====

    @Operation(summary = "检索测试", description = "输入问题查看命中的文档块及相似度分数; 可调 Top-K(需有该知识库读权限)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RetrievalResult.class)))
    @ApiResponse(responseCode = "404", description = "知识库不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'KNOWLEDGE_BASE', principal)")
    @PostMapping("/{id}/search")
    public Mono<Result<List<RetrievalResult>>> search(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? null : String.valueOf(body.get("query"));
        Integer topK = body.get("topK") == null ? null : Integer.parseInt(String.valueOf(body.get("topK")));
        return ownershipGuard.currentContext().flatMap(ctx -> {
            // Service 入口再校验一次读权限(纵深防御, 口径同 OwnershipGuard)
            knowledgeBaseService.getAccessible(id, ctx.userId(), ctx.admin(), false);
            // retrieve 内部会阻塞等待 embedding HTTP 响应, 不能在 reactor-http-nio 线程执行
            return Mono.fromCallable(() -> retrievalService.retrieve(id, query, topK))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(Result::success);
        });
    }

    // ===== 多知识库合并检索 (V3.2 索引测试扩展) =====

    @Operation(summary = "多知识库检索测试",
            description = "一次查询跨多个知识库合并检索, 结果标注来源知识库+文件名; 需对每个库有读权限")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RetrievalResult.class)))
    @PostMapping("/search")
    public Mono<Result<List<RetrievalResult>>> searchMulti(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> kbIds = (List<String>) body.get("kbIds");
        String query = body.get("query") == null ? null : String.valueOf(body.get("query"));
        Integer topK = body.get("topK") == null ? null : Integer.parseInt(String.valueOf(body.get("topK")));
        if (kbIds == null || kbIds.isEmpty()) {
            return Mono.just(Result.success(List.of()));
        }
        return ownershipGuard.currentContext().flatMap(ctx -> Mono.fromCallable(() -> {
            // 逐个同步校验读权限(任一越权即 404)
            for (String kbId : kbIds) {
                knowledgeBaseService.getAccessible(kbId, ctx.userId(), ctx.admin(), false);
            }
            return retrievalService.retrieve(kbIds, query, topK);
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::success));
    }

    // ===== 请求参数工具 =====

    /** JSON 字段统一转字符串; null 保留为 null(服务层据此区分"未传"与"清空") */
    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 分块数字参数: 未传返回 null(回退), 非整数返回 400 而不是让框架抛 500 */
    private static Integer intOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "分块参数必须为整数: " + value);
        }
    }
}
