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
- 内置工具：计算器、时间、天气、图像生成、**自建联网搜索**（SearXNG + 正文提取）、
  **长期记忆读写**（`memory` 工具）、**代码执行沙箱**（`code_interpreter`）、MCP 工具注册（`ToolRegistry`）

### 知识库 (RAG)
- 知识库管理（用户级 / 公开级可见性），文档上传 → 分块 → 向量化 → 相似度检索（`rag/` 模块）
- **混合检索**（向量 + BM25 全文，RRF 融合）与可选**相关性重排**；Agentic 检索（模型自主调用 `knowledge_base_search`）
- 注入加固（边界标记 / 长度预算）与**引用忠实度校验**（回答中的 `[[n]]` 必须落在本次来源集内）

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

### V3.1 新增
- **长期记忆**：分层作用域（用户级 / Agent 级）+ 常驻与关键词触发召回 + 过期归档；对话内「请记住 X」
  （`memory` 工具）与 **LLM 自动抽取**（两层开关：部署闸门 + 用户同意），记忆页可见、可改、可溯源
- **专家与技能体系**：技能定义 / 技能市场（SkillHub 发现层）/ 专家工作台（专家·自动化·运维三个 Hub）
- **统一归属框架**（`auth/ownership`）：14 类资源的越权收口，声明式 `@PreAuthorize` 接入，越权统一 404
- **代码执行沙箱**：受控容器运行 Python（禁网 / CPU 内存配额 / 双超时 / 非 root / 全局并发上限）
- **治理**：Agent 版本快照 / 结构化 diff / 回滚，成本分析报表与 CSV 导出
- **多实例协调**：集中式限流（Redis）+ 定时任务租约锁
- **回答反馈**：点赞 / 点踩 + 差评归因看板；**用户自定义模型**（Key 加密存储）；检索健康度看板

---

## 三、技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.3.5 · WebFlux (响应式) · Java 17 |
| AI 框架 | LangChain4j 1.19.0（BOM 统一管理） |
| 持久化 | Spring Data JPA · PostgreSQL 16 + pgvector（H2 仅作测试替身） · Redis |
| 向量 | pgvector 余弦检索（RAG 模块） |
| 可观测 | Micrometer Tracing · Zipkin · Prometheus · Actuator |
| 前端 | Vue 3 · Vite · Element Plus · Pinia |
| 部署 | Docker · docker-compose |

---

## 四、目录结构

```
dcdr-agent/
├── pom.xml                        # Maven 构建 (JDK 17, v3.1.0)
├── Dockerfile / .dockerignore     # 多阶段构建; 凭据不进构建上下文
├── docker-compose.yml             # 单机部署: agent / postgres / redis / searxng / opensandbox-server / zipkin
├── docker-compose.ha.yml          # 多实例 + PG 主从 (profile: ha)
├── docker/                        # nginx(SSE 反代) / pg-standby / searxng 配置
├── scripts/                       # backup-db / restore-db / ha-standby-init / run-tests
├── docs/                          # 需求 / 规划 / 发布说明 / SQL / 教程
│   ├── 01-需求文档.md  14-V3.1规划文档.md  RELEASE-v3.1.0.md
│   ├── 16-多实例与高可用演练.md  17-专家与技能体系.md
│   ├── 18-本地运行与部署教程.md   # ← 从零跑起来 / Docker 部署 / 升级 / 常见问题
│   ├── sql/                       # v2.0 / v3.0 / v3.1 建库脚本 + README(脚本清单)
│   └── eval/                      # memory-health / retrieval-health 巡检 SQL
├── src/main/java/com/dcdr/agent/
│   ├── JavaAgentApplication.java
│   ├── controller/                # REST 接口 (Chat/Agent/KnowledgeBase/Workflow/ScheduledTask/
│   │                              #   Image/Video/Audio/Model/Auth/Routing/Webhook/Admin/
│   │                              #   Memory/Skill/SkillMarket/Feedback/Monitor/UserModel...)
│   ├── agent/
│   │   ├── AgentOrchestrator.java # 多 Agent 编排核心 (含记忆注入 / RAG 注入加固 / 引用校验)
│   │   ├── router/                # 智能路由 (IntentRouter, LLM 意图识别)
│   │   ├── definition/            # Agent 定义 / 模板 / 版本管理 (AgentDefinitionService)
│   │   ├── workflow/              # 工作流 (DAG 编排 / 执行容错 / 断点续跑 / 人工审批)
│   │   ├── memory/                # 会话记忆 + longterm/(长期记忆: CRUD/召回/LLM 自动抽取/memory 工具)
│   │   ├── governance/            # 成本记录与报表 (CostTracker / CostReportService)
│   │   ├── feedback/ market/ version/ creative/ image/ prompt/ schedule/ session/ tenant/
│   │   └── ModelRouter.java       # 模型路由
│   ├── skill/                     # 技能体系 (定义/目录/加载) + skill/market/(SkillHub 发现层)
│   ├── rag/                       # 知识库 RAG (retrieval 混合+重排 / tool 知识库搜索 / ocr / config)
│   ├── auth/                      # 认证 / RBAC / ownership(统一归属框架, 14 类资源)
│   ├── cluster/                   # 多实例协调 (Redis 限流 / 定时任务租约锁)
│   ├── tool/                      # 工具 (sandbox 代码沙箱 / result 结果治理 / websearch 自建搜索 / mcp)
│   ├── model/                     # 模型适配 (Chat/Image/Video/Audio, LangChain4j 框架化 + 熔断)
│   ├── entity/ dto/ repository/   # JPA 实体 / DTO / 数据访问 (含 userId/visibility 隔离过滤)
│   ├── service/ config/ common/ admin/ audit/
│   │   └── common/util/SecretGuard.java   # 启动期默认密钥拦截(两项密钥必填)
├── frontend/
│   └── src/
│       ├── api/                   # 接口封装 (@/api)
│       ├── stores/                # Pinia (auth.js 认证 / model.js 全局模型列表)
│       ├── constants/             # 后端枚举取值镜像 (仅用于判断; 展示文案由后端下发)
│       ├── views/                 # Chat / Agent / KnowledgeBase / Workflow / DagEditor
│       │                          #   / Memory / SkillManage / SkillMarket / HubShell(专家·自动化·运维)
│       │                          #   / TemplateMarket / ScheduledTask / Routing / Monitor
│       │                          #   / RetrievalHealth / LlmCallLog / Creative / Admin / Auth
│       ├── components/            # AppHeader / MessageBubble(点赞点踩) 等
│       └── App.vue                # 路由与全局模型加载
└── data/                          # 运行时数据 (知识库文档/上传文件/生成图片/DB), 已加入 .gitignore 不入库
```

---

## 五、快速开始

> **完整教程见 [`docs/18-本地运行与部署教程.md`](docs/18-本地运行与部署教程.md)** ——
> 密钥生成、依赖启动、Docker 部署、升级路径与常见问题都在那里；下面是最短路径。

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
| 长期记忆 | `/api/memories` | 记忆 CRUD / 概览 / 归档恢复 / 「AI 自动记忆」开关 |
| 技能 | `/api/skills` `/api/skill-market` | 技能定义管理 / 技能市场（分类·搜索·安装） |
| 回答反馈 | `/api/feedback` | 点赞 / 点踩采集与看板统计 |
| 治理 | `/api/monitor/cost/report` `/api/retrieval-health/overview` | 成本报表（CSV 导出）/ 检索健康度 |
| 用户模型 | `/api/user-models` | 用户自定义模型（API Key 加密存储、不回显） |

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
- 升级到 V3.1 需执行 `docs/sql/v3.1_schema.sql`（长期记忆 / 归属 / 治理 / 技能 / 反馈 / 混合检索索引）。
  全部脚本幂等可重复执行；**全新部署由 `docker compose up -d` 自动完成**，无需手工执行任何 SQL。

存量 `agent_definitions.tools` / `kb_ids` 若为裸字符串（非 JSON 数组），后端已兼容解析，无需手动清洗。

---

## 九、相关文档

- 需求文档：`docs/01-需求文档.md`
- **本地运行与部署教程**：`docs/18-本地运行与部署教程.md`（从零跑起来 / Docker 部署 / 升级 / 常见问题）
- V3.1 规划：`docs/14-V3.1规划文档.md`（V3.0 见 `docs/07-V3.0规划文档.md`）
- 发布说明：`docs/RELEASE-v3.1.0.md`（历史版本见 `docs/RELEASE-*.md`）

---

## 十、License

[MIT](LICENSE)
