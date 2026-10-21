# dcdr-agent 产品知识库样例文档

> 用途：知识库检索链路（切分 / 向量化 / 召回）的固定测试素材。
> 内容取自仓库 README 与 pom.xml 的真实技术事实，不包含虚构指标。

## 这是什么

一人公司多模态 AI 助手工作台。面向单人/小团队，把对话、专家（Agent）、知识库、
工作流、长期记忆、成本治理集中在一个界面里，避免在多个 SaaS 之间来回切换。

## 技术栈事实

- 语言与框架：Java 17 + Spring Boot 3.3.5，Web 层是 WebFlux（响应式），SSE 流式输出。
- 模型接入：LangChain4j 1.19.0（core）+ 1.19.0-beta29（社区集成，含 DashScope 通义千问）。
- 数据存储：PostgreSQL 16 + pgvector，向量列 `document_chunks.embedding VECTOR(1536)`，
  建 `ivfflat` 余弦索引；Redis 7 用于分布式限流计数与定时任务租约锁。
- 联网搜索：自建 SearXNG（AGPL-3.0，无 API Key），替代第三方 Tavily。
- 代码执行：opensandbox-server 自托管沙箱，docker.sock 只挂给沙箱服务端，业务应用零特权。
- 前端：Vue 3 + Vite 5 + Element Plus + ECharts，通过 `mode` 状态与二级页签组织页面，未使用 vue-router。
- 可观测性：Micrometer + Prometheus + Zipkin 链路追踪，actuator 暴露 health/info/metrics/prometheus。

## 主要能力与接口

| 能力 | 接口前缀 | 说明 |
|---|---|---|
| 认证 | `/api/auth` | JWT 双 Token（Access 24h / Refresh 7d），空库首个注册用户自动成为 admin |
| 对话 | `/api/chat` | SSE 流式，支持工具调用、联网搜索、知识库引用、图片生成 |
| 会话 | `/api/sessions` | 增删改查、人设、消息历史、导出 Markdown/JSON |
| 专家 | `/api/agents` | 自定义专家、模板克隆、版本快照与回滚、版本 diff |
| 知识库 | `/api/knowledge-bases` | 文档上传、重新处理、单库检索与跨库检索 |
| 记忆 | `/api/memories` | 长期记忆：类型/作用域/重要度/常驻注入/归档恢复 |
| 工作流 | `/api/workflows` | 步骤定义、执行、执行历史、Webhook 触发、版本回滚 |
| 运维 | `/api/monitor`、`/api/llm-calls`、`/api/routing` | 成本统计、调用链、智能路由日志、检索健康 |

## 权限模型

- `admin`：全部权限（含 `user:manage`、`model:manage`）。
- `user`：对话、会话、知识库、专家、工作流、定时任务。
- `guest`：仅 `chat:chat`。
- 资源级越权校验统一用 `ownershipGuard`，越权与不存在都返回 404，避免资源枚举。

## 部署形态

- 依赖编排与应用编排分离：`docker-compose.yml` 只含 PostgreSQL / Redis / Zipkin / SearXNG / 沙箱服务端；
  应用本体在 `docker-compose.app.yml`，两者通过固定网络 `dcdr-net` 互通。
- 数据安全：PG 数据在命名卷，但每日 `pg_dump -Fc` 落到宿主机 `data/backup/db/`，
  因为命名卷随 Docker VM 的 vhdx 同生共死。
