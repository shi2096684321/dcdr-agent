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
 * <b>userId 来源</b>: V2.2 从 {@link SecurityContext} 取当前登录用户 ID;
 * 未登录(auth.enabled=false 或匿名访问)时退化为 null, 行为与 V2.1 单用户一致。
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

    @Operation(summary = "知识库详情")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KnowledgeBaseEntity.class)))
    @ApiResponse(responseCode = "404", description = "知识库不存在")
    @GetMapping("/{id}")
    public Result<KnowledgeBaseEntity> get(@Parameter(description = "知识库 ID", required = true) @PathVariable String id) {
        return Result.success(knowledgeBaseService.get(id));
    }

    @Operation(summary = "更新知识库", description = "更新名称/描述(其余字段如 embedding 模型由全局配置统一管理)")
    @PutMapping("/{id}")
    public Result<KnowledgeBaseEntity> update(@Parameter(description = "知识库 ID", required = true) @PathVariable String id,
                                              @RequestBody Map<String, String> body) {
        return Result.success(knowledgeBaseService.update(id, body.get("name"), body.get("description")));
    }

    @Operation(summary = "删除知识库", description = "级联删除文档、向量及落盘文件")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "知识库 ID", required = true) @PathVariable String id) {
        knowledgeBaseService.delete(id);
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
        // 从 exchange 同步段读取 userId, 再传入异步链路
        String userId = SecurityContext.getUserId(exchange);
        return documentService.upload(id, file, userId)
                .map(Result::success);
    }

    @Operation(summary = "文档列表", description = "返回知识库内全部文档(含状态、分块数、大小)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DocumentEntity.class)))
    @GetMapping("/{id}/documents")
    public Result<List<DocumentEntity>> listDocuments(@Parameter(description = "知识库 ID", required = true) @PathVariable String id) {
        return Result.success(documentService.listDocuments(id));
    }

    @Operation(summary = "删除文档", description = "删除文档及对应的向量与落盘文件")
    @DeleteMapping("/{id}/documents/{docId}")
    public Result<Void> deleteDocument(@Parameter(description = "知识库 ID", required = true) @PathVariable String id,
                                       @Parameter(description = "文档 ID", required = true) @PathVariable String docId) {
        documentService.deleteDocument(docId);
        return Result.success();
    }

    @Operation(summary = "重新入库", description = "清空旧分块后重新解析+向量化; 用于切换 embedding 模型或修复失败文档")
    @PostMapping("/{id}/documents/{docId}/reprocess")
    public Result<Void> reprocess(@Parameter(description = "知识库 ID", required = true) @PathVariable String id,
                                  @Parameter(description = "文档 ID", required = true) @PathVariable String docId) {
        documentService.reprocess(docId);
        return Result.success();
    }

    // ===== 检索测试 (F-21-003) =====

    @Operation(summary = "检索测试", description = "输入问题查看命中的文档块及相似度分数; 可调 Top-K")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RetrievalResult.class)))
    @PostMapping("/{id}/search")
    public Mono<Result<List<RetrievalResult>>> search(
            @Parameter(description = "知识库 ID", required = true) @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? null : String.valueOf(body.get("query"));
        Integer topK = body.get("topK") == null ? null : Integer.parseInt(String.valueOf(body.get("topK")));
        // retrieve 内部调用 embedding.embed() 会 .block() 等待 HTTP 响应,
        // 不能在 reactor-http-nio 线程执行, 需切到 boundedElastic 线程池
        return Mono.fromCallable(() -> retrievalService.retrieve(id, query, topK))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }
}
