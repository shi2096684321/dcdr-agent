# DCDR Agent

<div align="center">

**基于 Spring Boot 3 + Java 17 的智能 Agent 平台**

统一对话 · 知识库 RAG · 多模态生成 · 自定义 Agent 编排 · 多用户协作

[![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

</div>

---

## 一、版本

| 项 | 值 |
|----|----|
| 当前版本 | **v2.3.0** |
| 发布日期 | 2026-08-13 |
| Java | 17 |
| Spring Boot | 3.3.5 |
| 构建工具 | Maven |

> 版本演进：V1.x（单轮对话）→ V2.0（知识库 / 认证 / 多 Agent）→ V2.1（RAG 增强）→ V2.2（RBAC / 审计 / 数据隔离）→ V2.3（自定义 Agent / 工作流 / 定时任务 / 流式图片），统一以 **v2.3.0** 发布。

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

### 自定义 Agent
- Agent 定义：系统提示词、绑定模型、工具列表 (`tools`)、关联知识库 (`kbIds`)、温度参数
- 公开 / 模板 Agent (`isPublic` / `isTemplate`)，支持克隆
- 工作流编排（Workflow）与定时任务调度（ScheduledTask）

### 多用户与协作
- 基于 Token 的认证（`auth/` 模块 + `SecurityContextWebFilter` 请求级身份注入）
- 基于用户 / 可见性的数据隔离（Repository 按 `userId` / `visibility` 过滤）
- 管理员后台（用户管理、审计日志 `audit/`）

---

## 三、技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.3.5 · WebFlux (响应式) · Java 17 |
| 持久化 | Spring Data JPA · H2 / MySQL |
| 向量 | 向量检索（RAG 模块） |
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
│   ├── 03-V2.0规划文档.md
│   ├── RELEASE-v2.3.0.md
│   └── sql/v2.0_schema.sql
├── src/main/java/com/dcdr/agent/
│   ├── JavaAgentApplication.java
│   ├── controller/              # REST 接口 (Chat/Agent/KnowledgeBase/Workflow/ScheduledTask/Image/Video/Model/Auth...)
│   ├── agent/
│   │   ├── AgentOrchestrator.java   # 多 Agent 编排核心
│   │   ├── definition/          # Agent 定义 / 模板 (AgentDefinitionService)
│   │   ├── workflow/            # 工作流
│   │   ├── schedule/            # 定时任务
│   │   ├── creative/ image/ memory/ prompt/ session/  # 各能力子模块
│   │   └── ModelRouter.java     # 模型路由
│   ├── rag/                     # 知识库 RAG (文档/分块/向量/检索)
│   ├── auth/                    # 认证 / 鉴权 / 安全上下文
│   ├── audit/                   # 审计日志
│   ├── tool/                    # 工具注册与实现 (Calculator/Time/Weather/Image/MCP)
│   ├── model/                   # 模型适配 (Chat/Image/Video)
│   ├── entity/                  # JPA 实体 (User/AgentDefinition/KnowledgeBase/Workflow/ScheduledTask/AuditLog...)
│   ├── dto/                     # 请求/响应 DTO (含对外布尔字段 @JsonProperty 规范)
│   ├── repository/              # 数据访问 (含 userId/visibility 隔离过滤)
│   ├── service/ config/ common/ admin/
├── frontend/
│   └── src/
│       ├── api/                 # 接口封装 (@/api)
│       ├── stores/              # Pinia (auth.js 认证 / model.js 全局模型列表)
│       ├── views/               # ChatView / AgentView / KnowledgeBaseView
│       │                         #   / WorkflowView / ScheduledTaskView / CreativeView
│       │                         #   / AdminView / AuthView
│       ├── components/          # AppHeader 等
│       └── App.vue              # 路由与全局模型加载
└── data/                        # 本地数据 / 上传文件
```

---

## 五、快速开始

### 5.1 后端 (JDK 17)

```bash
# 要求 JDK 17 (示例路径, 按本机调整)
export JAVA_HOME=/path/to/jdk17

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
| 对话 | `POST /api/chat` / `GET /api/sessions` | 流式对话、会话管理 |
| Agent | `/api/agents` | 自定义 Agent 增删改查、克隆、模板 |
| 知识库 | `/api/knowledge-bases` | 知识库与文档管理 (RAG) |
| 工作流 | `/api/workflows` | 工作流定义与执行 |
| 定时任务 | `/api/scheduled-tasks` | 定时任务调度 |
| 多模态 | `/api/image` `/api/video` `/api/audio` | 图像 / 视频 / 音频生成 |
| 模型 | `/api/models` | 模型列表（按 `type` 提供 CHAT/IMAGE/VIDEO） |
| 认证 | `/api/auth` | 登录 / 注册 / 用户管理 |

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

从 V1.x 升级需执行 `docs/sql/v2.0_schema.sql`（新增 `agent_definition` / `knowledge_base` / `workflow_definition` / `scheduled_task` / `audit_log` 等表）。

存量 `agent_definitions.tools` / `kb_ids` 若为裸字符串（非 JSON 数组），后端已兼容解析，无需手动清洗。

---

## 九、相关文档

- 需求文档：`docs/01-需求文档.md`
- V2.0 规划：`docs/03-V2.0规划文档.md`
- 发布说明：`docs/RELEASE-v2.3.0.md`

---

## 十、License

[MIT](LICENSE)
