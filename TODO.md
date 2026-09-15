# TODO / 待优化项

## 已知遗留（非本次改动引入）

### [BUG] 测试代码编译失败，导致 `mvn spring-boot:run` / `mvn package` 无法直接执行
- **状态**：待处理
- **现象**：`src/test/java/com/dcdr/agent/model/langchain4j/LangChain4jProviderTest.java:182`
  调用 `new LangChain4jEmbeddingProvider(config)` 只传 1 个参数，而该构造器已需要
  `(RagProperties.EmbeddingProviderConfig, LlmCircuitBreaker)` 两个参数 → `testCompile` 失败。
- **影响**：`spring-boot:run` 会在 `test-compile` 阶段中断；临时绕过用 `-Dmaven.test.skip=true`。
- **修复方向**：同步更新该测试的构造器调用（补 `LlmCircuitBreaker` 入参或改用 mock）。

## 待优化 BUG

### [BUG] 多轮对话时 agent 重复回答历史内容
- **状态**：✅ 已修复（2026-08-07，commit `87be529`）
- **记录时间**：2026-08-06
- **现象**：同一会话内连续提问（多轮对话）时，agent 会重复回答历史中已经回答过的内容，而不是聚焦在当前这一轮的新问题上。
- **期望行为**：每次回答都应针对用户**当轮的最新提问**；历史消息仅作为背景/上下文参考，不应复述或重复先前已给出的答案。
- **排查方向**：
  - 检查 `src/main/java/com/dcdr/agent/agent/AgentOrchestrator.java` 中 history 的组装/截断逻辑，确认是否把过多历史原文带入了对话上下文。
  - 在 system prompt 或运行时约束（`injectRuntimeSystemPrompts`）中明确加入约束，例如：“只回答用户当前的问题，历史记录仅作参考，不要重复已有回答”。
  - 必要时对超长 history 做摘要/裁剪，避免模型被历史答案带偏。
- **关联记忆**：agent memory `knowledge_id=69948070`

---

## 已修复（近期）

- ✅ **V3.1 F-41 安全加固收口 —— 统一归属框架（Ownership）**（2026-09-14）：
  - 新增 `com.dcdr.agent.auth.ownership` 包：`OwnedEntity` / `ResourceType` / `AuthContext` / `Ownership` /
    `ResourceOwnershipProvider` / `OwnershipRegistry` / `OwnershipGuard`，共 11 个 `ResourceOwnershipProvider`。
  - **声明式接入（Spring Security 官方方案）**：Controller 方法标注
    `@PreAuthorize("@ownershipGuard.requireRead(#id, 'SESSION', principal)")` 即完成校验 ——
    当前用户由 Spring Security 以 SpEL 变量 `principal` 注入，**无需再声明 `ServerWebExchange` 参数**。
    前提：`SecurityConfig` 开启 `@EnableReactiveMethodSecurity`，且被保护方法返回 `Mono`
    （因此接入端点统一返回 `Mono<Result<T>>`，对外 JSON 契约不变；返回 `Flux` 的流式端点改用
    `OwnershipGuard.currentContext()` 手工包一层）。
  - 另提供 `currentContext()`（响应式读取 userId，供需要按用户隔离/写入归属的接口）与
    `check/canAccess`（Service 层与非 HTTP 链路显式调用）。
  - **已实测排除的方案（勿重复踩坑）**：曾自备 `ThreadLocalAccessor` +
    `Hooks.enableAutomaticContextPropagation()`，想让同步 AOP 切面从 `SecurityContextHolder` 取用户 ——
    在「同步返回 `Result<T>` 的 Controller + 同步切面」下 `SecurityContextHolder` 始终为空，
    导致用户访问自己的资源也 404（fail-closed 全量拒绝），已放弃。结论与根因写在 `OwnershipGuard` 类注释中。
  - **修复越权面**：会话 6 个裸奔端点（delete / title / persona / messages / deleteMessages / export）；
    对话内 RAG 的 `ChatRequest.knowledgeBaseIds` 越权检索（在 `ChatController` 入口静默剔除无权限 kbId）。
  - **全量迁移**：知识库 / 文档 / Agent / 工作流 / 工作流执行 / 定时任务 / Webhook / 用户模型配置 /
    反馈 / 创作项目 / 生成记录 共 11 类资源的归属判定全部收敛到 `OwnershipGuard`。
  - **口径统一**：越权与不存在一律 **404**（原先混用 403/404，403 可被用于资源枚举探测）；
    越权尝试统一写审计 `access_denied`；admin 统一放行；匿名仅在 `auth.enabled=false` 时兼容放行。
  - `ProjectEntity` 补 `userId` 归属列 + PG DDL 脚本 `docs/sql/v3.1_ownership_schema.sql`。
  - 已验证（H2 启动 + 端到端）：**会话域 9 项 + 创作项目域 8 项**横向越权矩阵全部符合预期 ——
    用户 A 访问/删除自己的资源返回 200；用户 B 跨用户读取/改名/改人设/读消息/删消息/导出/删除一律 404。
    详见 `docs/10-完整测试用例文档.md` §2.1。
  - **迁移状态（全部完成）**：12 类受保护资源的 HTTP 入口均已切换到
    `@PreAuthorize` + `OwnershipGuard.currentContext()`，Controller 方法**不再声明 `ServerWebExchange` 参数**。
    覆盖 SessionController / ProjectController / GenerationController / VideoController / ImageController /
    AgentController / WorkflowController / ScheduledTaskController / WebhookController（管理端点）/
    UserModelController / FeedbackController / KnowledgeBaseController / TemplateMarketController。
    Service 层校验保留作为纵深防御。
  - `ResourceType` 新增 `TEMPLATE_SHARE`（共 12 类），配套 `TemplateShareOwnershipProvider`。
  - **写入侧自动化（V3.1 增强）**：`CurrentUserHolder`（线程局部当前用户）+ `OwnershipFillListener`
    （JPA `@PrePersist` 回调，11 个归属实体通过 `@EntityListeners` 注册）+ `OwnedEntity` 契约。
    insert 前若实体 `userId` 为空则自动填充，**消除"新增写入点漏设归属"导致的无主数据**；
    显式设置过的一律不覆盖。
    - 上下文贯通方式：`SecurityContextWebFilter` 采用「每个请求先清空 → 认证后写入 → 链尾再清空」
      三段式防残留。实测确认 `reactor-http-nio-*` 线程上 JPA 回调可读到当前用户，且**换用户/换线程不串号**。
    - 失效边界：跨异步边界（`boundedElastic`、定时任务、工作流内部）读不到，此时**不填充**（fail-closed），
      需继续显式传 userId —— 现有 Service 签名均保留 userId 参数正是为此。
  - **实测发现一个 V3.0 遗留缺陷（非本次引入）**：市场分享只写 `template_share` 记录，
    未把被分享资源置为 `isPublic`，导致他人**无法克隆**已分享的模板（迁移前 403，现统一 404）。
    已记入 `docs/10-完整测试用例文档.md` §2.1 的 TC-OWN-29，建议后续修复。

- ✅ 刷新页面后 token 使用量消失：`SessionManager.getMessages` 主路径补充 `setTokenUsage(msg.getTokenUsage())`。
- ✅ `application.yml` 的 chat 场景 prompt 含未填充的 `{user_question}` / `{search_context}` 占位符：移除占位符，system 消息保持静态，用户问题与搜索结果由 history（user 消息 + tool 结果）自然提供。
- ✅ agentic 多轮工具调用循环失效：修正每轮 tool_calls 快照与 history 回填逻辑。
- ✅ 工具轮噪声（空气泡）显示在会话记录中：后端过滤 tool 消息、前端聚合为可折叠的「🔧 已调用 N 个工具」。
