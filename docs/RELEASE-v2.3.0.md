# 发布说明 (Release Notes) — DCDR Agent v2.3.0

> 发布类型: 大版本 (Major) — 架构重构 + 多模态能力 + 协作增强 + 自定义 Agent
> 发布日期: 2026-08-13
> 对应分支: `feat/v2.0`

---

## 一、版本概要 (Summary)

v2.3.0 是 agent 平台的重大版本更新，在 v1.x 单轮对话基础上，重构为**多 Agent 协作 + 工具/工作流编排**的平台化架构，并补齐**知识库 (RAG)**、**图像/视频多模态生成**、**多用户认证与数据隔离**，以及 **V2.3 自定义 Agent / 工作流 / 定时任务 / 流式图片** 能力。同时本版本对前后端契约做了规范化（布尔字段命名统一），并修复了若干存量缺陷。

> 涉及版本范围: V2.0（知识库/认证/多 Agent）+ V2.1（RAG 知识库）+ V2.2（认证/RBAC/审计/数据隔离）+ V2.3（自定义 Agent / 工作流 / 定时任务 / 流式图片），统一以 v2.3.0 发布。

---

## 二、本次新增特性 (Features)

### 2.1 多 Agent 与协作
- 新增 Agent 定义 (AgentDefinition) 体系：支持公开/模板 Agent (`isPublic` / `isTemplate`) 与用户私有 Agent 的数据隔离
- 新增 Agent 克隆 (clone) 能力
- 新增 Agent 模板市场 (公开模板可被任意用户选用)

### 2.2 知识库 (RAG)
- 新增知识库管理 (KnowledgeBase)：用户级与公开级可见性
- 知识库列表查询修复不可变集合导致的服务端异常 (`UnsupportedOperationException`)，返回可变列表

### 2.3 多模态生成
- 新增图像生成 (IMAGE) 与视频生成 (VIDEO) 模型类型，前端 `imageModels` / `videoModels` 独立分类
- 新增模型默认标记 (`isDefault`)，用于自动选中默认对话模型

### 2.4 工作流与定时任务
- 新增工作流 (Workflow) 视图与编排能力
- 新增定时任务 (ScheduledTask) 视图与调度能力

### 2.5 认证与多用户
- 引入基于 `SecurityContextWebFilter` 的请求级用户身份注入 (`ServerWebExchange` attributes)
- Repository 层按 `userId` / `visibility` 做查询过滤，实现多租户数据隔离

### 2.6 自定义 Agent (V2.3)
- 用户可创建/编辑/删除自定义 Agent：系统提示词、模型、工具 (`tools`)、关联知识库 (`kbIds`)、温度参数
- Agent 模板库（管理员预设，普通用户可克隆后编辑）
- 流式图片生成能力

---

## 三、缺陷修复 (Bug Fixes)

- **注册失败无前端提示**：后端以 HTTP 200 + `code:409` 承载业务错误，前端 `unwrap` 取 `data:null` 不 reject → 统一 `ApiError` + `extractMessage`，各视图 (Auth/Admin/KnowledgeBase/Creative/AppHeader) 改为可见提示
- **登录后模型列表未加载**：`App.vue` 静默 catch 掩盖失败 → 改为可见日志 + `watch(isLoggedIn)` 双保险
- **知识库列表 500 崩溃**：`Stream.toList()` 返回不可变集合被 `addAll` → Service / Controller 改为 `new ArrayList<>(...)`
- **Agent 编辑页无法选择模型**：`AgentView` 的 `chatModels` 被硬编码为空数组且从不赋值 → 抽出全局 `modelStore`，`AgentView` 从 store 读取，全应用模型列表仅加载一次
- **布尔字段契约不一致**：对外 DTO (`AgentDtos.AgentView` / `SaveAgentRequest`、`ModelInfo`) 的 `isPublic` / `isTemplate` / `isDefault` 显式加 `@JsonProperty("isXxx")`，前后端命名统一
- **Agent 工具列表解析失败**：数据库存量数据以裸字符串（如 `knowledge_base_search`）而非 JSON 数组存储，导致 `AgentDefinitionService.parseStrList` 抛 `JsonParseException` 且工具列表恒为空 → `parseStrList` 增加兜底，兼容「标准 JSON 数组 / 裸字符串单值 / 逗号分隔列表」三种形态

---

## 四、技术改进与重构 (Technical / Refactor)

- 新增全局模型列表 store (`frontend/src/stores/model.js`)：`loadModels` 幂等、按 `type` 提供 `chatModels` / `imageModels` / `videoModels` getter，替代各视图重复请求
- 后端错误响应规范化：`GlobalExceptionHandler` 以 `@ResponseStatus(HttpStatus.OK)` 承载业务码，`code!=200` 由前端统一 reject

---

## 五、兼容性说明 (Compatibility)

- **前端契约变更**：Agent / Model 对外布尔字段 JSON key 由 `public` / `template` / `default` 变更为 `isPublic` / `isTemplate` / `isDefault`。**前端需同步使用 `isXxx` 命名**（本项目前端早已使用该命名，本次后端补注解后前后端对齐）
- 数据库：需执行 `docs/sql/v2.0_schema.sql` 升级脚本（新增 agent_definition / knowledge_base / workflow / scheduled_task 等表与字段）

---

## 六、构建与验证 (Build & Verify)

- 后端：JDK 17 + Maven 离线编译通过 (`mvn -q -o compile -DskipTests`, exit 0)
- 前端：模型列表统一由 `modelStore` 提供，Agent 编辑页模型下拉已可用

---

## 七、升级注意事项 (Upgrade Notes)

1. 备份数据库后执行 `docs/sql/v2.0_schema.sql`
2. 前端构建产物需整体替换 (v2.0 路由/视图结构变化较大)
3. 确认认证配置 (SecurityConfig) 与既有用户数据迁移策略
4. 存量 `agent_definitions.tools` / `kb_ids` 若为裸字符串（非 JSON 数组），后端已兼容解析，无需手动清洗；如需规范化可批量改写为 JSON 数组

---

## 八、相关文档

- 需求文档: `docs/01-需求文档.md`
- V2.0 规划: `docs/03-V2.0规划文档.md`
- 数据库脚本: `docs/sql/v2.0_schema.sql`
