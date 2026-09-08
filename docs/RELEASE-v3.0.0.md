# dcdr-agent v3.0.0

基于 Spring Boot + Vue 3 的生产级 Agent 编排平台：在 v2.3「能跑」的 Agent/工作流能力之上，本版完成智能路由、可视化 DAG 编排、执行容错、治理可观测与 **LangChain4j 框架化**改造，达到生产可用水平。

> 发布日期: 2026-09-08 ｜ 对应分支: `feat/v3.0-langchain4j` ｜ 前序里程碑: [v3.0.0-alpha-self-impl](RELEASE-v3.0.0-alpha-self-impl.md)（自研五层架构终态）

## ✨ 核心特性

**智能路由与触发（F-30 / F-31）**
- 基于 LLM 的意图识别（IntentRouter），对话即自动触发工作流 / Agent / 普通对话，低置信度自动回退
- 路由判断落库 `routing_logs`，携带置信度与理由，支持准确率分析与前端路由日志查询（RoutingView）
- 触发方式扩展为 `manual / api / webhook / event / schedule / chat`；Webhook 端点 token 鉴权 + 可选签名校验

**执行容错与人工介入（F-33）**
- 工作流执行状态持久化（`workflow_executions`），失败重试（fixed / linear / exponential 退避）
- 断点续跑：中断后从最后完成步骤恢复；人工审批节点支持 `approve / cancel`

**可视化 DAG 编排（F-32）**
- 前端 DAG 画布（DagEditorView）：节点拖拽连线编排，替代纯表单填空
- 工作流支持路由节点，按条件分流执行

**LangChain4j 框架化（本版核心主题）**
- 能力层切换至 LangChain4j 1.19.0：ChatModel / EmbeddingModel / ImageModel Provider 全面框架化（LangChain4jChatProvider / LangChain4jEmbeddingProvider / LangChain4jImageProvider）
- 工具定义注册期统一转换为框架原生 `ToolSpecification` 并缓存（ToolRegistry），坏 schema 启动期即暴露；内部 JSON Schema 与框架 Schema 双向转换（JsonSchemaConverter）
- 底层定制 JDK HttpClient 连接池（强制 HTTP/1.1），消除上游网关空闲连接竞态导致的 Connection reset
- 向量检索返回真实余弦距离，修复按排序位置估算分数导致 min-score 过滤失真的问题
- 编排层（AgentOrchestrator / WorkflowEngine / IntentRouter）保留业务逻辑，数据层 / 安全层 / 接入层不动

**治理与可观测（F-34 / F-40 部分子项）**
- LLM 调用链路追踪（Micrometer Tracing + Zipkin），traceId/spanId 贯穿日志
- 监控视图（MonitorView）+ Actuator 健康检查 + Prometheus 指标暴露
- 限流（RateLimitWebFilter）与模型调用熔断（CircuitBreakerChatProvider）
- 审计日志（登录 / 管理 / 对话 / 生成等关键操作落库）
- 日志轮转（单文件 100MB，保留 30 天 / 总量 2GB）

**成本与配额（F-39）**
- 对话 token 用量记录（CostTracker）与用户级配额检查，超额请求 429 拦截

**模板市场（F-35）**
- Agent / 工作流模板市场（TemplateMarketView），预置模板一键套用

**对话体验增强**
- 会话级 System Prompt 定制（用户可为单个会话设定人设 / 约束，优先级高于全局与场景级）
- 知识库强制检索（RAG 显式模式）与 Agentic RAG 检索引用卡片
- 流式消息按会话归属隔离：切换会话不串显、返回可见完整回答
- 文生图引擎切换优化；工具调用结果回灌与多轮交互修复

**规划调整（2026-09-08 决策）**
- F-36 多租户：完整 SaaS 化不实施，仅保留租户配额型实现；F-38 本地模型：作为私有化部署扩展不扩大投入。二者存档见 `docs/12-未来可扩展项记录.md`
- F-40.13 灾备与高可用：方案存档（`docs/13-部署与高可用.md`），当前仓库保持单实例开发能力

## 📦 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3.5 · Java 17 · WebFlux · Spring Data JPA · H2 / PostgreSQL(+pgvector) |
| AI 框架 | LangChain4j 1.19.0（BOM 统一管理） |
| 前端 | Vue 3 · Vite · Element Plus · Pinia |
| 协议 | SSE 流式 · REST · Function Calling · Webhook |
| 可观测 | Micrometer Tracing · Zipkin · Prometheus · Actuator |

## 🚀 快速开始

```bash
# 后端（Java 17）
mvn spring-boot:run

# 前端
cd frontend
npm install
npm run dev
```

在 `application.yml` 中配置模型 API Key；生产环境建议 PostgreSQL + pgvector 并执行 `docs/sql/v3.0_schema.sql`（新增 `routing_logs` / `webhook_endpoints` / `workflow_executions` 三表）。V3 各能力经 `agent.v3.*` 配置开关独立启停，未开启智能路由时对话行为与 V2.3 一致。

## 📝 说明

v3.0.0 聚焦「生产级编排平台」：智能路由补齐"对话即触发"，DAG 编排替代表单填空，执行容错保证可靠运行，框架化让能力层站在 LangChain4j 抽象之上，链路追踪 / 限流 / 熔断 / 配额让平台具备生产运维条件。

- 本版为 v3.0 系列正式版，汇总 [v3.0.0-alpha-self-impl](RELEASE-v3.0.0-alpha-self-impl.md) 的全部 alpha 交付
- 已规划未实现项（F-36 完整 SaaS / F-38 本地模型 / F-40.13 灾备）见 `docs/12-未来可扩展项记录.md` 与 `docs/13-部署与高可用.md`
- 框架对照学习过程存档见 `docs/08-框架对照版学习计划.md`
