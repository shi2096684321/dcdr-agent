# 一人公司多模态 AI 助手工作台

<div align="center">

**基于 Spring Boot 3 + Java 17 + LangChain4j 的生产级 Agent 平台**

智能路由 · 可视化 DAG 编排 · 执行容错 · 知识库 RAG · 多模态生成 · 链路追踪与治理

[![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

</div>

---

## 一、版本

| 项 | 值 |
|----|----|
| 当前版本 | **v3.1.0** |
| 发布日期 | 2026-10-14 |
| Java | 17 |
| Spring Boot | 3.3.5 |
| AI 框架 | LangChain4j 1.19.0 |
| 构建工具 | Maven |

> 版本演进：V1.x（单轮对话）→ V2.0（知识库 / 认证 / 多 Agent）→ V2.1（RAG 增强）→ V2.2（RBAC / 审计 / 数据隔离）→ V2.3（自定义 Agent / 工作流 / 定时任务 / 流式图片）→ V3.0（智能路由 / 可视化 DAG / 执行容错 / LangChain4j 框架化 / 治理可观测）→ **V3.1（统一归属框架 / 专家与技能体系 / 代码执行沙箱 / 版本与成本治理 / 多实例协调 / 长期记忆）**。发布说明见 [`docs/RELEASE-v3.1.0.md`](docs/RELEASE-v3.1.0.md)，历史版本见 `docs/RELEASE-*.md`。

---

## 二、特性总览

### 对话与多模态
- 流式对话（SSE），支持上下文记忆与多轮会话
- **图像生成 / 视频生成**（按模型 `type` 分类：`CHAT` / `IMAGE` / `VIDEO`）
- **音频生成**（TTS）与音频文件处理
- 内置工具：计算器、时间、天气、图像生成、MCP 工具注册（`ToolRegistry`）

### 知识库 (RAG)
- 知识库管理（用户级 / 公开级可见性）
- 文档上传 → 分块 → 向量化 → 相似度检索（`rag/` 模块）
- 对话中按 `kbIds` 关联知识库进行检索增强

### 自定义 Agent 与编排
- Agent 定义：系统提示词、绑定模型、工具列表 (`tools`)、关联知识库 (`kbIds`)、温度参数
- 公开 / 模板 Agent (`isPublic` / `isTemplate`)，支持克隆
- **智能路由**（LLM 意图识别）：对话即自动触发工作流 / Agent，低置信度自动回退，路由日志可查
- **可视化 DAG 编排**（拖拽画布 + 路由节点）与表单式工作流、定时任务调度
- **执行容错**：失败重试（fixed / linear / exponential 退避）、断点续跑、人工审批节点
- **模板市场**：Agent / 工作流模板一键套用

### 治理与可观测
- LLM 调用链路追踪（Micrometer Tracing + Zipkin），traceId 贯穿日志
- 监控视图（MonitorView）、Actuator 健康检查、Prometheus 指标
- 限流（RateLimitWebFilter）与模型调用熔断（CircuitBreakerChatProvider）
- 成本与配额：对话 token 用量记录、用户级配额检查（超额 429）
- 审计日志、日志轮转（100MB / 文件，保留 30 天 / 总量 2GB）

### 多用户与协作
- 基于 Token 的认证（`auth/` 模块 + `SecurityContextWebFilter` 请求级身份注入）
- RBAC 权限模型（admin / user / guest，`Permission.java` 角色-权限映射）
- 基于用户 / 可见性的数据隔离（Repository 按 `userId` / `visibility` 过滤）
- 管理员后台（用户管理、审计日志 `audit/`）

---

## 三、技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.3.5 · WebFlux (响应式) · Java 17 |
| AI 框架 | LangChain4j 1.19.0（BOM 统一管理） |
| 持久化 | Spring Data JPA · H2 / PostgreSQL |
| 向量 | pgvector 余弦检索（RAG 模块） |
| 可观测 | Micrometer Tracing · Zipkin · Prometheus · Actuator |
| 前端 | Vue 3 · Vite · Element Plus · Pinia |
| 部署 | Docker · docker-compose |

---

## 四、目录结构

```
dcdr-agent/
├── pom.xml                       # Maven 构建 (JDK 17)
├── Dockerfile
├── docker-compose.yml
├── docs/                         # 需求 / 规划 / 发布说明 / SQL 脚本
│   ├── 01-需求文档.md
│   ├── 14-V3.1规划文档.md
│   ├── RELEASE-v3.1.0.md
│   └── sql/v3.1_*.sql
├── src/main/java/com/dcdr/agent/
│   ├── JavaAgentApplication.java
│   ├── controller/              # REST 接口 (Chat/Agent/KnowledgeBase/Workflow/ScheduledTask/Image/Video/Model/Auth/Routing/Webhook/Admin...)
│   ├── agent/
│   │   ├── AgentOrchestrator.java   # 多 Agent 编排核心
│   │   ├── router/              # 智能路由 (IntentRouter, LLM 意图识别)
│   │   ├── definition/          # Agent 定义 / 模板 (AgentDefinitionService)
│   │   ├── workflow/            # 工作流 (含 DAG 编排 / 执行容错 / 断点续跑)
│   │   ├── schedule/            # 定时任务
│   │   ├── creative/ image/ memory/ prompt/ session/  # 各能力子模块
│   │   └── ModelRouter.java     # 模型路由
│   ├── rag/                     # 知识库 RAG (文档/分块/向量/检索)
│   ├── auth/                    # 认证 / RBAC 鉴权 / 安全上下文
│   ├── audit/                   # 审计日志
│   ├── tool/                    # 工具注册与实现 (Calculator/Time/Weather/Image/MCP, 注册期转 ToolSpecification)
│   ├── model/                   # 模型适配 (Chat/Image/Video, LangChain4j 框架化 + 熔断)
│   ├── entity/                  # JPA 实体 (User/AgentDefinition/KnowledgeBase/Workflow/RoutingLog/AuditLog...)
│   ├── dto/                     # 请求/响应 DTO (含对外布尔字段 @JsonProperty 规范)
│   ├── repository/              # 数据访问 (含 userId/visibility 隔离过滤)
│   ├── service/ config/ common/ admin/
├── frontend/
│   └── src/
│       ├── api/                 # 接口封装 (@/api)
│       ├── stores/              # Pinia (auth.js 认证 / model.js 全局模型列表)
│       ├── views/               # ChatView / AgentView / KnowledgeBaseView
│       │                         #   / WorkflowView / DagEditorView / TemplateMarketView
│       │                         #   / ScheduledTaskView / RoutingView / MonitorView
│       │                         #   / CreativeView / AdminView / AuthView
│       ├── components/          # AppHeader 等
│       └── App.vue              # 路由与全局模型加载
└── data/                        # 运行时数据 (知识库文档/上传文件/生成图片/DB), 已加入 .gitignore 不入库
```

---

## 五、快速开始

### 5.1 后端 (JDK 17)

```bash
# 要求 JDK 17 (示例路径, 按本机调整)
export JAVA_HOME=/path/to/jdk17

# 两项密钥**必填**: 启动期会校验(SecretGuard), 为空或仍是内置默认值会被拒绝启动
#   理由: 公开可知的 JWT 签名密钥 = 任何人都能伪造任意用户(含 admin)的 Token;
#         公开可知的加密主密钥 = 拿到库里的密文即可解出用户真实的模型 API Key
#
# 推荐做法: 写进项目根目录的 .env（本地运行与 docker compose 都会读它, 见 application.yml 的
# spring.config.import）—— 不必每次开 shell 都 export, 也不必往 IDE 运行配置里填:
#   JWT_SECRET=<随机值>
#   MODEL_API_KEY_SECRET=<随机值, 不要与上面复用>
#
# 生成随机值(Windows PowerShell):
#   [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
# 也可以直接注入环境变量(仅当前 shell 有效):
export JWT_SECRET=$(openssl rand -hex 32)
export MODEL_API_KEY_SECRET=$(openssl rand -hex 32)

# 编译 (离线)
mvn -q -o compile -DskipTests

# 运行
mvn spring-boot:run
```

> 注意：本项目使用 **Java 17**。若系统默认 `JAVA_HOME` 指向 JDK 8 等低版本，请先切换到 JDK 17 再构建。

### 5.2 前端

```bash
cd frontend
npm install
npm run dev
```

### 5.3 Docker

```bash
docker compose up -d --build
```

---

## 六、主要 API

| 模块 | 路径 | 说明 |
|------|------|------|
| 对话 | `POST /api/chat` / `GET /api/sessions` | 流式对话（含智能路由分发）、会话管理 |
| Agent | `/api/agents` | 自定义 Agent 增删改查、克隆、模板 |
| 知识库 | `/api/knowledge-bases` | 知识库与文档管理 (RAG) |
| 工作流 | `/api/workflows` | 工作流定义、DAG 编排、执行 / 续跑 / 审批 |
| 定时任务 | `/api/scheduled-tasks` | 定时任务调度 |
| 智能路由 | `/api/routing` | 路由日志分页查询 |
| Webhook | `/api/webhooks` | Webhook 端点管理与外部触发 |
| 多模态 | `/api/image` `/api/video` `/api/audio` | 图像 / 视频 / 音频生成 |
| 模型 | `/api/models` | 模型列表（按 `type` 提供 CHAT/IMAGE/VIDEO） |
| 认证 | `/api/auth` | 登录 / 注册 / 用户管理 |
| 管理后台 | `/api/admin` `/api/tenants` | 用户管理 / 租户配额（仅 admin） |

---

## 七、前端契约规范

对外 DTO 的布尔字段统一使用 `@JsonProperty("isXxx")` 显式声明 JSON key，前后端命名一致：

| 字段 | JSON key |
|------|----------|
| `AgentView.isPublic` / `isTemplate` | `isPublic` / `isTemplate` |
| `SaveAgentRequest.isPublic` | `isPublic` |
| `ModelInfo.isDefault` | `isDefault` |

全局模型列表由 `frontend/src/stores/model.js` 统一管理（`loadModels` 幂等，按 `type` 提供 `chatModels` / `imageModels` / `videoModels`），避免各视图重复请求。

---

## 八、数据库升级

- 从 V1.x 升级需执行 `docs/sql/v2.0_schema.sql`（新增 `agent_definition` / `knowledge_base` / `workflow_definition` / `scheduled_task` / `audit_log` 等表）。
- 从 V2.x 升级到 V3.0 需执行 `docs/sql/v3.0_schema.sql`（新增 `routing_logs` / `webhook_endpoints` / `workflow_executions` 三表，脚本可重复执行）。

存量 `agent_definitions.tools` / `kb_ids` 若为裸字符串（非 JSON 数组），后端已兼容解析，无需手动清洗。

---

## 九、相关文档

- 需求文档：`docs/01-需求文档.md`
- V3.1 规划：`docs/14-V3.1规划文档.md`（V3.0 见 `docs/07-V3.0规划文档.md`）
- 发布说明：`docs/RELEASE-v3.1.0.md`（历史版本见 `docs/RELEASE-*.md`）

---

## 十、License

[MIT](LICENSE)
