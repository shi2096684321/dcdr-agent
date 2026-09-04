# RAG 检索 + 工具调用 框架化迁移方案（LangChain4j 1.19.0）

## Context（为什么做）

当前知识库检索与工具执行存在"手写"成分，缺乏框架标准化：

- **检索层**：`RetrievalService` 手写 pgvector KNN SQL（`DocumentChunkRepository.findTopKWithDistance`）+ min-score/topK 阈值 + 文件名补全 `fillFilenames`。

- **工具层**：orchestrator 手写 round/tool 循环与工具名分发（`toolRegistry.executeInfo`），仅半框架化（`ToolRegistry` 已把自定义 `Tool` 转成 LangChain4j `ToolSpecification`）。

目标：把这两块收敛到 LangChain4j 框架原生抽象（`EmbeddingStore` / `ContentRetriever` / `ToolExecutor` / RRF 融合 / 可选 Rerank），**保留**现有 `document_chunks VECTOR(1536)` 表、`[知识库|文件]` metadata 前缀、流式 SSE、`LlmCallTrace` 打点、Agent 工具白名单、`ChatMemory` 与智能路由，不迁移数据。

已确认的硬事实（从本地 m2 的 1.19.0 jar 核实）：

- LangChain4j **1.19.0 没有** **`HybridRetriever`、没有** **`ToolSpecificationExecutor`**。

- 存在：`ContentRetriever`、`EmbeddingStore<T>`、`EmbeddingStoreContentRetriever`、`ReciprocalRankFuser`（RRF 融合）、`ReRankingContentAggregator` + `ScoringModel`、`service.tool.ToolExecutor`、`agent.tool.ToolExecutionRequest`。

- **无需新增依赖**：上述类型都在 `langchain4j-core`（经 `langchain4j` 传递引入）。`langchain4j-hybrid`/`langchain4j-rerank`/`langchain4j-pgvector` 均不加（1.19.0 BOM 不管理它们；库表用适配器，不引官库新表）。

- `RetrievalService` 现有 `RetrievalResult(chunkId, documentId, kbId, chunkIndex, content, score, filename)`，经 `EmbeddingRegistry.getDefault()` 取嵌入，逐库 KNN 已返回真实 cosine distance，similarity = 1 - distance。适配器需**精确复刻同一分数**，保证 `POST /{kbId}/search` 结果与迁移前逐位一致。

## 采用的方案

框架原生组件接管，orchestrator 保留（用户已选定：低回归路径，非完整 AiServices 重写）。

### 新增文件（包 `com.dcdr.agent.rag.retrieval`）

1. **`PgVectorEmbeddingStoreAdapter`** — `implements EmbeddingStore<TextSegment>`，包装现有 `DocumentChunkRepository` + `EmbeddingRegistry`。

   - `search(EmbeddingSearchRequest)`：从 request 取 query embedding → `VectorType.toVectorString` → 逐库 `findTopKWithDistance` → similarity=1-distance → minScore/topK 过滤（与 `RetrievalService` 现逻辑位级一致）→ 批量补 filename（迁移 `fillFilenames` 逻辑）→ 产出 `EmbeddingMatch<TextSegment>`，metadata 携带 `{chunkId, documentId, kbId, chunkIndex, filename, score}`，`content`（已含 metadata 前缀的原文）放 `TextSegment.from`。

   - kb 范围通过自定义 `KbIdsFilter implements Filter`（或保留便捷 `searchByKbIds(Set<String>,... )` 重载）传入。

   - `add/addAll/removeAll`（写路径）→ `throw UnsupportedOperationException("ingestion 走 DocumentService 直写")`；入库不迁移。

   - 嵌套 `static final class KbIdsFilter`。

   - 泛型用 `TextSegment` 而非 `float[]` 的原因：`EmbeddingMatch.embedded()` 要携带 content+metadata 供下游还原 `RetrievalResult`/`[知识库|文件]` 前缀。

2. **`KnowledgeBaseContentRetriever`** — `implements ContentRetriever`，框架原生检索入口。

   - `retrieve(Query)`：从 `query.metadata()` 读 kbIds/topK/minScore → `embedding.embed` → `adapter.search` → `EmbeddingMatch<TextSegment>` → `DefaultContent(TextSegment)`。

   - 可选 hybrid（`agent.rag.retrieval.hybrid.enabled`）：与 `Bm25ContentRetriever` 结果用 `ReciprocalRankFuser` 融合（RRF）。

   - 可选 rerank（`agent.rag.retrieval.rerank.enabled`）：结果过 `ReRankingContentAggregator`（需 `ScoringModel`）。

3. **`Bm25ContentRetriever`** — `implements ContentRetriever`，仅 hybrid 开启时装配。调新增 `DocumentChunkRepository` FTS 查询（`to_tsvector(content) @@ plainto_tsquery`，按 `ts_rank_cd` 排序，按 kb 限定），metadata 键与向量路径一致。

4. **`OpenAiCompatibleScoringModel`**（可选，仅 rerank）— `implements ScoringModel`，调可配置的 OpenAI 兼容 rerank 端点。默认不装配。

5. **`com.dcdr.agent.rag.config.RagRetrievalConfig`** — `@Configuration`，按开关装配：`EmbeddingStore<TextSegment>`（适配器）、`ContentRetriever`、可选 `Bm25ContentRetriever`、可选 `ScoringModel`。

### 工具层桥（包 `com.dcdr.agent.tool`）

1. **`FrameworkToolExecutorBridge`** — `implements service.tool.ToolExecutor`（命名避开框架 `ToolExecutor`），持有 `ToolRegistry` + `ObjectMapper`。

   - `execute(ToolExecutionRequest, Object memoryId)`：按 `request.name()` 走 `registry.getTool` → 解析 `request.arguments()` JSON → `tool.execute`；错误/空工具返回提示文本（对齐现 `ToolRegistry.execute`）。

   - `executeWithInfo(ToolExecutionRequest, Object memoryId)` → 委托 `registry.executeInfo`，保留 `ToolInfo`（sources 等）。orchestrator 用此路径。

   - 构造可复用的 `Map<String,ToolExecutor>`（AiServices `toolExecutors` 形状），为将来框架调用预留。

### 修改文件

| 文件                                                | 改动                                                                                                                                                                                                                                                                                                                                                                               |
| ------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `rag/service/RetrievalService.java`               | 保留两个公开 `retrieve(...)` 签名、`RAG_DISABLED` 守卫、空参数守卫、汇总日志。**替换方法体**：注入 `ContentRetriever`，用 `Query.from(query, Metadata.with(kbIds/topK/minScore))` 调 `contentRetriever.retrieve`，写 `toRetrievalResult(Content)` 从 `textSegment().metadata()` 还原七字段。**删除**私有 `fillFilenames`（移入适配器）。                                                                                                |
| `agent/AgentOrchestrator.java`                    | 仅在 `executeTools` 分发处（约 697–711 行）：把 `toolRegistry.executeInfo(name, params)` 换成 `frameworkToolExecutorBridge.executeWithInfo(ToolExecutionRequest, sessId)`。保留 dedup、image\_generation/web\_search special 处理、`t.recordRag`、`tracedRound/tracedTool/recordTool`、白名单 & `supportsStreaming`、`web_extract` 与 workflow/MCP 的 `ToolRegistry` 调用。构造器增注 `FrameworkToolExecutorBridge`。 |
| `rag/config/RagProperties.java`                   | `RetrievalConfig` 增：`hybrid.enabled`(默认 false)、`hybrid.rrf.k`/权重；`rerank.enabled`(默认 false)、`rerank.baseUrl/apiKey/model`。                                                                                                                                                                                                                                                       |
| `repository/DocumentChunkRepository.java`         | 增 BM25 原生查询（`findTopKByKbFts`，返回带分数的投影），仅 hybrid 用。                                                                                                                                                                                                                                                                                                                              |
| `docs/sql/*.sql`                                  | 增增量式、可选 DDL：`CREATE EXTENSION IF NOT EXISTS pg_trgm;` + `document_chunks.content` 的 GIN(trgm) 索引（仅 hybrid 开启需要）。非破坏。                                                                                                                                                                                                                                                             |
| `pom.xml`                                         | **无新依赖**。确认不引入 hybrid/rerank/pgvector 模块。                                                                                                                                                                                                                                                                                                                                        |
| `rag/embedding/LangChain4jEmbeddingProvider.java` | 无需改（`embed(query)` 即可）。可选加 `getEmbeddingModel()` getter 供未来 `EmbeddingStoreContentRetriever` 直用。                                                                                                                                                                                                                                                                                 |

### 关键设计点

- **分数位级一致**：适配器复刻 `RetrievalService` 的逐库 KNN + `1-distance` + minScore/topK，保证 `POST /{kbId}/search` 与迁移前后结果一致（默认 topK=5、minScore=0.30）。

- **metadata 前缀保留**：`content` 在入库时已含 `[知识库:…|文件:…]`/`[文件:…]` 前缀（`DocumentService.buildChunkPrefix`），经 `TextSegment` 原样带出，`retrieveAndInject`/`KnowledgeSearchTool` 的 `[[i]] 文件: {filename}（片段 #{chunkIndex}）` 与 `sources` 成型不变。

- **hybrid/rerank 全部默认关闭**：迁移本身是纯 drop-in，不改变默认行为。

## 验证

1. **编译**：`mvn -q -DskipTests compile`（BOM 1.19.0、无新依赖，通过即证明框架类型可解析）。编码前先用 `javap` 核对 `ReciprocalRankFuser` RRF 方法与 `rag.query.Metadata` 构造函数签名（规划阶段已标为待确认）。
2. **检索冒烟**：`POST /{kbId}/search {query, topK}` → 断言命中 id、排序、分数与迁移前**逐位一致**（默认配置下）。再分别开 `hybrid.enabled`、`rerank.enabled` 复测排序质量。
3. **回归点**：

   - agentic `knowledge_base_search`（`KnowledgeSearchTool`）—— sources 仍含 chunkId/score/filename。

   - 显式强检索 `retrieveAndInject(forced=true)`—— 仍注入 `【知识库强制检索结果】`，未命中提示路径完好。

   - `LlmCallTrace`：`recordRag`（knowledge\_base\_search 与 retrieveAndInject 两处）与 `recordTool`/`recordLlm` 每轮照常发出。
4. **入库无回归**：上传文档（`DocumentService` → `chunkRepository.saveAll`），确认适配器写路径（抛 `UnsupportedOperationException`）绝不在入库链路上被触发。

