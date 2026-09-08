# 发布说明 (Release Notes) — DCDR Agent v3.0.0-alpha-self-impl

> 发布类型: 里程碑预发布 (Alpha Milestone) — 智能路由 + 触发层扩展 + 执行容错 + 生产化加固规划
> 发布日期: 2026-08-19
> 对应分支: `feat/v3.0`
> 对应标签: `v3.0.0-alpha-self-impl`（自研五层架构终态，引入框架前的最后一个版本）

---

## 一、版本概要 (Summary)

v3.0.0-alpha-self-impl 是自研五层架构（触发 / 路由 / 编排 / 执行 / 治理）的**最终里程碑版本**。在 v2.3.0 自定义 Agent / 工作流编排的基础上，补齐了**智能路由层**（LLM 自动意图识别）、**触发层扩展**（API / Webhook / 事件驱动）、**执行容错**（失败重试 + 断点续跑 + 人工审批）三大核心能力，并完善了工作流执行历史的检索与路由日志的可观测性。

本版本同时完成 [07-V3.0规划文档.md](file:///d:/Projects/java-agent/docs/07-V3.0规划文档.md) 的 F-40 **生产化加固**章节规划（可观测性 / 限流 / 熔断 / 配置管理 / 一致性 / 测试 / 运维 / 安全 / 性能 / 灾备 共 14 子项），为后续引入 LangChain4j 框架开发提供对照基线。

> 涉及版本范围: V3.0-alpha 智能路由 (F-30) + 触发层扩展 (F-31) + 执行容错与人工介入 (F-33) + 路由日志治理 + F-40 生产化加固规划，统一以 v3.0.0-alpha-self-impl 发布。
> 后续版本 (feat/v3.1-langchain4j) 起将引入 LangChain4j 框架替换能力层，本版本作为自研实现的对照基线保留。

---

## 二、本次新增特性 (Features)

### 2.1 智能路由层 (F-30)
- 新增 [IntentRouter](file:///d:/Projects/java-agent/src/main/java/com/dcdr/agent/agent/router/IntentRouter.java)：基于 LLM 的意图识别，自动将用户消息路由到 workflow / agent / 普通对话
- 路由结果携带置信度 (`confidence`)，低于阈值时自动回退普通对话，避免误路由
- 路由判断记录落库 `routing_logs` 表，支持路由准确率分析与用户反馈修正
- 对话入口 [ChatController](file:///d:/Projects/java-agent/src/main/java/com/dcdr/agent/controller/ChatController.java) 集成智能路由，用户无需手动选择执行路径

### 2.2 触发层扩展 (F-31)
- 新增 [WebhookController](file:///d:/Projects/java-agent/src/main/java/com/dcdr/agent/controller/WebhookController.java)：Webhook 端点管理（创建 / 查询 / 启停 / 删除）与外部触发处理
- Webhook 端点配置表 `webhook_endpoints`：随机 token 鉴权 + 可选 secret 签名校验，避免未授权触发
- 触发方式扩展为 `manual / api / webhook / event / schedule / chat`，执行记录全程留痕
- 工作流定义支持关联 Webhook 端点，外部系统可通过 HTTP 调用触发工作流执行

### 2.3 执行容错与人工介入 (F-33)
- [WorkflowEngine](file:///d:/Projects/java-agent/src/main/java/com/dcdr/agent/agent/workflow/WorkflowEngine.java) 执行状态持久化到 `workflow_executions` 表，每步状态与输出以 `steps_state_json` 存储
- **失败重试**：支持 `fixed / linear / exponential` 三种退避策略，重试次数与间隔可配
- **断点续跑**：执行中断后可从最后完成步骤恢复，避免从头执行
- **人工审批节点**：执行到审批节点时进入 `paused` 状态，支持 `approve / cancel` 操作推进或终止
- 执行记录支持 `resume`（续跑）、`approve`（审批通过）、`cancel`（取消）等生命周期操作

### 2.4 路由日志与执行历史治理
- 新增 [RoutingController](file:///d:/Projects/java-agent/src/main/java/com/dcdr/agent/controller/RoutingController.java)：路由日志分页查询，支持按用户、时间范围筛选
- 路由记录视图回填 `targetName`（工作流名 / Agent 名），通过 `nameCache` 批量解析避免重复查询数据库
- 工作流执行历史支持多条件搜索筛选分页：关键词、状态、工作流 ID、触发方式，条件变更自动重置到首页
- 前端 [RoutingView.vue](file:///d:/Projects/java-agent/frontend/src/views/RoutingView.vue) / [WorkflowView.vue](file:///d:/Projects/java-agent/frontend/src/views/WorkflowView.vue) 适配分页结构与筛选工具栏

### 2.5 V3 配置体系
- 新增 [V3Properties](file:///d:/Projects/java-agent/src/main/java/com/dcdr/agent/config/V3Properties.java)：统一管理 `agent.v3.*` 配置项
  - 智能路由开关与置信度阈值 (`agent.v3.routing.enabled` / `enabled-threshold`)
  - 重试策略与退避参数 (`agent.v3.retry.*`)
  - Webhook 基址 (`agent.v3.webhook.base-url`)

---

## 三、缺陷修复 (Bug Fixes)

- **路由日志查询 JDBC 异常 (lower(bytea) does not exist)**：PostgreSQL 在 JPQL `CONCAT` 操作中将 `:keyword` 参数推断为 `bytea` 类型，导致 `lower(bytea)` 报错。修复方式为显式 `CAST(:keyword AS string)` 锁定 text 类型。参见 [WorkflowExecutionRepository.java](file:///d:/Projects/java-agent/src/main/java/com/dcdr/agent/repository/WorkflowExecutionRepository.java)
- **v3.0_schema.sql 清理**：移除误导性的 `bytea` DO 块、移除冗余的 `idx_webhook_token` 索引（`UNIQUE` 约束已自动建索引）、新增 `idx_routing_type` 索引以支持按路由类型过滤查询
- **WebhookEndpointEntity 冗余索引**：移除 `@Index(name = "idx_webhook_token", columnList = "token", unique = true)`，避免与 `UNIQUE` 约束自动创建的索引重复
- **RoutingDtos 编译错误**：缺失 `java.util.List` 导入导致 Maven 编译失败，补充 import
- **WorkflowView 字段缺失**：`WorkflowEngine.toView` 调用 `setTemplate` / `setEditable`，但 `WorkflowDtos.WorkflowView` 未声明对应字段，补充 `private boolean template;` 与 `private boolean editable;`

---

## 四、技术改进与重构 (Technical / Refactor)

- **路由目标名称批量回填**：`RoutingController` 使用 `nameCache` 缓存目标 ID→名称映射，单次分页查询内同类型同 ID 只解析一次，避免 N+1 查询
- **分页响应结构统一**：路由日志返回 `RoutingLogPageView`（content / page / size / totalElements / totalPages），前端自适应分页结构无需改造
- **规划文档完善**：[07-V3.0规划文档.md](file:///d:/Projects/java-agent/docs/07-V3.0规划文档.md) 补充 F-40 生产化加固章节（14 子项），同步更新目录、功能总览表、架构图、新增组件表、版本里程碑与技术风险表
- **架构图演进**：架构图新增"生产化加固层（F-40, 横切各层）"，API Gateway 标注加入限流 + 熔断能力

---

## 五、数据库变更 (Database)

执行 [v3.0_schema.sql](file:///d:/Projects/java-agent/docs/sql/v3.0_schema.sql) 新增三张表：

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `routing_logs` | F-30 智能路由判断记录 | route_type / target_id / confidence / reason |
| `webhook_endpoints` | F-31 Webhook 端点配置 | token (UNIQUE) / secret / workflow_id / enabled / trigger_count |
| `workflow_executions` | F-33 工作流执行记录（断点续跑核心） | status / current_step / steps_state_json / cost_tokens |

新增索引：`idx_routing_user` / `idx_routing_time` / `idx_routing_type` / `idx_webhook_user` / `idx_wfexec_workflow` / `idx_wfexec_user` / `idx_wfexec_status`。

---

## 六、兼容性说明 (Compatibility)

- **数据库**：`ddl-auto=validate` 不会自动建表，需手动执行 `docs/sql/v3.0_schema.sql`（脚本使用 `CREATE TABLE IF NOT EXISTS` / `ALTER ... IF NOT EXISTS`，可在已有库上重复执行）
- **配置**：V3 能力默认通过 `agent.v3.*` 属性控制，未配置时使用默认值（路由开关可关闭以兼容 V2.3 行为）
- **Webhook 安全**：Webhook 触发端点需 token 认证；管理端点需 `workflow:execute` 权限
- **向后兼容**：未开启智能路由时，对话行为与 V2.3 完全一致（普通对话 + 手动选择 Agent / 工作流）

---

## 七、构建与验证 (Build & Verify)

- 后端：JDK 17 + Maven 编译通过
- 前端：路由日志与工作流执行历史分页筛选功能验证通过
- 数据库：v3.0_schema.sql 在 PostgreSQL 16 + pgvector 环境执行通过

---

## 八、升级注意事项 (Upgrade Notes)

1. 备份数据库后执行 `docs/sql/v3.0_schema.sql`（依赖 `users` / `workflow_definitions` 表已存在，需在 v2.0_schema.sql 之后执行）
2. 在 `application.yml` 中按需配置 `agent.v3.*` 属性（路由开关、置信度阈值、重试策略、Webhook 基址）
3. 智能路由依赖 LLM 调用进行意图识别，确保对话模型可用；路由失败会自动降级为普通对话，不影响主链路
4. Webhook 端点创建后生成的 token 需妥善保存，外部触发时通过 URL 携带 token 鉴权
5. 工作流执行历史中存量数据（V2.3 之前）无 `workflow_executions` 记录，不影响新执行

---

## 九、已规划未实现 (Planned, Not Yet Implemented)

以下功能已在 [07-V3.0规划文档.md](file:///d:/Projects/java-agent/docs/07-V3.0规划文档.md) 中规划，本版本未实现，留待后续：

- F-32 可视化 DAG 编排
- F-34 治理与可观测性（业务层仪表盘 / 版本管理 / 成本分析）
- F-35 Agent 与工作流模板市场
- F-36 多租户 SaaS 化（⚠️ 完整 SaaS 化不实施，仅保留配额型实现，见 [docs/12](12-未来可扩展项记录.md)）
- F-37 插件系统
- F-38 本地模型支持（⚠️ 降级为未来扩展项，基础能力已实现、不扩大投入，见 [docs/12](12-未来可扩展项记录.md)）
- F-39 成本与配额管理
- F-40 生产化加固（14 子项，详见规划文档 §14）

---

## 十、架构演进决策 (Architecture Decision)

本版本标记自研五层架构的终态。自下一版本 (`feat/v3.1-langchain4j`) 起，开发模式转为**引入框架开发**：

| 层 | 迁移策略 |
|----|---------|
| 能力层 (ChatModelProvider / ToolRegistry / RAG 检索 / ChatMemory) | 引入 LangChain4j 替换 |
| 编排层 (AgentOrchestrator / WorkflowEngine / IntentRouter) | 部分适配框架抽象，保留业务编排逻辑 |
| 数据层 / 安全层 / 接入层 | 保持不动 |

回溯本版本：`git checkout v3.0.0-alpha-self-impl`

---

## 十一、相关文档

- V3.0 规划文档: [07-V3.0规划文档.md](file:///d:/Projects/java-agent/docs/07-V3.0规划文档.md)
- 数据库脚本: [v3.0_schema.sql](file:///d:/Projects/java-agent/docs/sql/v3.0_schema.sql)
- 上一版本发行说明: [RELEASE-v2.3.0.md](file:///d:/Projects/java-agent/docs/RELEASE-v2.3.0.md)
