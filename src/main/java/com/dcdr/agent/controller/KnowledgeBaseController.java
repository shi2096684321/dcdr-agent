package com.dcdr.agent.controller;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.entity.DocumentEntity;
import com.dcdr.agent.entity.KnowledgeBaseEntity;
import com.dcdr.agent.rag.dto.RetrievalResult;
import com.dcdr.agent.rag.service.DocumentService;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
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
 *   <li>POST   /api/knowledge-bases                          创建知识库</li>
 *   <li>GET    /api/knowledge-bases/{id}                     知识库详情</li>
 *   <li>PUT    /api/knowledge-bases/{id}                     更新知识库</li>
 *   <li>DELETE /api/knowledge-bases/{id}                     删除知识库(级联)</li>
 *   <li>POST   /api/knowledge-bases/{id}/documents           上传文档(异步入库)</li>
 *   <li>GET    /api/knowledge-bases/{id}/documents           文档列表</li>
 *   <li>DELETE /api/knowledge-bases/{id}/documents/{docId}   删除文档</li>
 *   <li>POST   /api/knowledge-bases/{id}/documents/{docId}/reprocess  重新入库</li>
 *   <li>POST   /api/knowledge-bases/{id}/search              检索测试</li>
 * </ul>
 * <p>
 * <b>userId 来源</b>: 从 {@link SecurityContext} 取当前登录用户 ID 与 isAdmin;
 * 未登录(auth.enabled=false 或匿名访问)时 userId 退化为 null, 行为与 V2.1 单用户一致。
 * <p>
 * <b>开放范围与越权校验</b>: 知识库已开放给普通用户(user 角色, 拥有 kb:read/kb:write):
 * 可创建/管理自己的 USER 级知识库; PLATFORM 级知识库仅 admin 可写, 普通用户只读。
 * 因此本类每个涉及具体知识库的接口都必须先取 userId/isAdmin 并交给
 * {@link com.dcdr.agent.rag.service.KnowledgeBaseService#getAccessible} 校验, 禁止直接按 id 操作。
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

    // ===== 知识库 CRUD (F-21-001) =====

    @Operation(summary = "知识库列表",
            description = "管理员返回全部(含 PLATFORM); 普通用户仅返回自己 USER 级 + 全部 PLATFORM; 未登录退化为全部 USER 级")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KnowledgeBaseEntity.class)))
    @GetMapping
    public Result<List<KnowledgeBaseEntity>> list(ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        if (isAdmin) {
            return Result.success(knowledgeBaseService.listAll());
        }
        // 普通用户: 自己的 USER 级 + 所有 PLATFORM 级(平台级对全体可见, 但前端选择器由 RagProperties 控制是否可见)
        List<KnowledgeBaseEntity> userKbs = new ArrayList<>(knowledgeBaseService.list(userId));
        List<KnowledgeBaseEntity> platformKbs = knowledgeBaseService.listPlatform();
        if (userKbs.isEmpty()){
            userKbs = new ArrayList<>();
        }
        if (platformKbs!=null && !platformKbs.isEmpty()) {
            userKbs.addAll(platformKbs);
        }
        return Result.success(userKbs);
    }

    @Operation(summary = "创建知识库", description = "创建一个新的空知识库, 复用全局 embedding/分块配置; visibility: USER(用户级) 或 PLATFORM(平台级, 仅 admin)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KnowledgeBaseEntity.class)))
    @ApiResponse(responseCode = "400", description = "名称为空")
    @ApiResponse(responseCode = "403", description = "非管理员不能创建 PLATFORM 知识库")
    @ApiResponse(responseCode = "503", description = "RAG 未启用")
    @PostMapping
    public Result<KnowledgeBaseEntity> create(@RequestBody Map<String, String> body,
                                              ServerWebExchange exchange) {
        String name = body.get("name");
        String description = body.get("description");
        String visibility = body.get("visibility");
        String userId = SecurityContext.getUserId(exchange);
        // PLATFORM 知识库仅管理员可创建
        if ("PLATFORM".equalsIgnoreCase(visibility) && !SecurityContext.isAdmin(exchange)) {
            return Result.error(403, "仅管理员可创建平台级知识库", "FORBIDDEN");
        }
        return Result.success(knowledgeBaseService.create(name, description, userId, visibility));
    }

    @Operation(summary = "知识库详情", description = "仅可查看自己 USER 级或平台级(只读); admin 可查看任意")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KnowledgeBaseEntity.class)))
    @ApiResponse(responseCode = "404", description = "知识库不存在")
    @GetMapping("/{id}")
    public Result<KnowledgeBaseEntity> get(@Parameter(description = "知识库 ID", required = true) @PathVariable String id,
                                           ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        return Result.success(knowledgeBaseService.getAccessible(id, userId, isAdmin, false));
    }

    @Operation(summary = "更新知识库", description = "更新名称/描述(仅自己 USER 级; 平台级仅 admin)")
    @PutMapping("/{id}")
    public Result<KnowledgeBaseEntity> update(@Parameter(description = "知识库 ID", required = true) @PathVariable String id,
                                              @RequestBody Map<String, String> body,
                                              ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        return Result.success(knowledgeBaseService.update(id, body.get("name"), body.get("description"), userId, isAdmin));
    }

    @Operation(summary = "删除知识库", description = "级联删除文档、向量及落盘文件(仅自己 USER 级; 平台级仅 admin)")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "知识库 ID", required = true) @PathVariable String id,
                               ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        knowledgeBaseService.delete(id, userId, isAdmin);
        return Result.success();
    }

    // ===== 文档管理 (F-21-002) =====

    @Operation(summary = "上传文档", description = "上传文档到指定知识库; 同步落盘落库(pending)后异步入库(解析+分块+向量化); V2.2 关联当前登录用户")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DocumentEntity.class)))
    @ApiResponse(responseCode = "400", description = "格式不支持/超大小/超数量上限")
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Result<DocumentEntity>> upload(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id,
            @Parameter(description = "上传的文件", required = true,
                    schema = @Schema(type = "string", format = "binary"))
            @RequestPart("file") FilePart file,
            ServerWebExchange exchange) {
        // 从 exchange 同步段读取 userId/isAdmin, 再传入异步链路(校验归属)
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        return documentService.upload(id, file, userId, isAdmin)
                .map(Result::success);
    }

    @Operation(summary = "文档列表", description = "返回知识库内全部文档(含状态、分块数、大小; 需有读权限)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DocumentEntity.class)))
    @GetMapping("/{id}/documents")
    public Result<List<DocumentEntity>> listDocuments(@Parameter(description = "知识库 ID", required = true) @PathVariable String id,
                                                      ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        return Result.success(documentService.listDocuments(id, userId, isAdmin));
    }

    @Operation(summary = "删除文档", description = "删除文档及对应的向量与落盘文件(仅自己 USER 级; 平台级仅 admin)")
    @DeleteMapping("/{id}/documents/{docId}")
    public Result<Void> deleteDocument(@Parameter(description = "知识库 ID", required = true) @PathVariable String id,
                                       @Parameter(description = "文档 ID", required = true) @PathVariable String docId,
                                       ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        documentService.deleteDocument(docId, userId, isAdmin);
        return Result.success();
    }

    @Operation(summary = "重新入库", description = "清空旧分块后重新解析+向量化(仅自己 USER 级; 平台级仅 admin)")
    @PostMapping("/{id}/documents/{docId}/reprocess")
    public Result<Void> reprocess(@Parameter(description = "知识库 ID", required = true) @PathVariable String id,
                                  @Parameter(description = "文档 ID", required = true) @PathVariable String docId,
                                  ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        documentService.reprocess(docId, userId, isAdmin);
        return Result.success();
    }

    // ===== 检索测试 (F-21-003) =====

    @Operation(summary = "检索测试", description = "输入问题查看命中的文档块及相似度分数; 可调 Top-K(需有该知识库读权限)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RetrievalResult.class)))
    @PostMapping("/{id}/search")
    public Mono<Result<List<RetrievalResult>>> search(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id,
            @RequestBody Map<String, Object> body,
            ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        String query = body.get("query") == null ? null : String.valueOf(body.get("query"));
        Integer topK = body.get("topK") == null ? null : Integer.parseInt(String.valueOf(body.get("topK")));
        // 先同步校验读权限, 再进入异步检索
        knowledgeBaseService.getAccessible(id, userId, isAdmin, false);
        // retrieve 内部调用 embedding.embed() 会 .block() 等待 HTTP 响应,
        // 不能在 reactor-http-nio 线程执行, 需切到 boundedElastic 线程池
        return Mono.fromCallable(() -> retrievalService.retrieve(id, query, topK))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    // ===== 多知识库合并检索 (V3.2 索引测试扩展) =====

    @Operation(summary = "多知识库检索测试",
            description = "一次查询跨多个知识库合并检索, 结果标注来源知识库+文件名; 需对每个库有读权限")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RetrievalResult.class)))
    @PostMapping("/search")
    public Mono<Result<List<RetrievalResult>>> searchMulti(
            @RequestBody Map<String, Object> body,
            ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        @SuppressWarnings("unchecked")
        List<String> kbIds = (List<String>) body.get("kbIds");
        String query = body.get("query") == null ? null : String.valueOf(body.get("query"));
        Integer topK = body.get("topK") == null ? null : Integer.parseInt(String.valueOf(body.get("topK")));
        if (kbIds == null || kbIds.isEmpty()) {
            return Mono.just(Result.success(List.of()));
        }
        return Mono.fromCallable(() -> {
            // 逐个同步校验读权限, 再进入异步检索
            for (String kbId : kbIds) {
                knowledgeBaseService.getAccessible(kbId, userId, isAdmin, false);
            }
            return retrievalService.retrieve(kbIds, query, topK);
        }).subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }
}
