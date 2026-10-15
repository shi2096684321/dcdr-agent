# dcdr-agent v3.1.0

基于 Spring Boot + Vue 3 的 Agent 编排平台：在 v3.0「生产可用」的骨架上，本版做**安全收口、体验与治理补完、生态扩展、运维落地**四件事，并新增**长期记忆**这一贯穿对话的能力。

> 发布日期: 2026-10-14 ｜ 对应分支: `feature/v3.1.0` ｜ 前序里程碑: [v3.0.0](RELEASE-v3.0.0.md) ｜ 版本规划: [docs/14-V3.1规划文档.md](14-V3.1规划文档.md)

## ✨ 核心特性

**安全加固收口（F-41）**
- 新增**统一归属框架** `com.dcdr.agent.auth.ownership`：受保护资源只需「一个枚举 + 一个 Provider」，目前覆盖 SESSION / KNOWLEDGE_BASE / DOCUMENT / AGENT / WORKFLOW / WORKFLOW_EXECUTION / SCHEDULED_TASK / WEBHOOK / USER_MODEL / PROJECT / GENERATION / TEMPLATE_SHARE / MEMORY / SKILL 共 14 类
- 接入方式用 **Spring Security 官方响应式方法级安全**（`@EnableReactiveMethodSecurity` + `@PreAuthorize("@ownershipGuard.requireRead/Write(...)")`），Controller 方法**零额外上下文参数**；当前用户由框架从 Reactor Context 提供
- 会话修改类接口（persona / title / delete / messages / export）越权缺口关闭，**越权统一 404**（避免资源枚举探测），越权尝试写审计 `access_denied`
- 对话内检索的 kbId 归属校验：请求携带的越权 kbId **静默剔除**，不报错也不检索
- ⚠️ **如实说明**：横向越权矩阵用例（TC-OWN-01~29）记录在 [docs/10-完整测试用例文档.md](10-完整测试用例文档.md) §2.1，**尚未全部自动化**（部分为"待验证"），规划文档中"纳入 CI"的表述超前于现状

**专家与技能体系（F-45，范围调整见下）**
- 技能定义管理：`/api/skills`（增删改查 / 启用停用 / 导入导出）
- 技能市场：接入 SkillHub 作为**发现层**（状态 / 分类 / 排序 / 搜索 / 安装），`/api/skill-market`
- 技能目录注入 system prompt + 平台级 `skill_load` 工具（**不受 Agent 工具白名单限制**，由平台机制保证"看得见就取得到"）
- 专家体系：专家工作台（专家 / 自动化 / 运维三个 Hub）+ 专家维度字段（profession / category / tags / quick-prompts）
- 表 `skill_definitions`、`agent_definitions` 扩列，见 [docs/sql/v3.1_schema.sql](sql/v3.1_schema.sql)（「F-45 专家与技能」段落）；设计见 [docs/17-专家与技能体系.md](17-专家与技能体系.md)
- ⚠️ **范围调整**：规划中 F-45 为「**远程模板市场**」，实际交付的是「专家与技能体系」；**远程模板仓库（`agent.market.remote-repos[]`）未交付**（`docs/17` §9.4 已记录）

**代码执行沙箱（F-43）**
- 新增 `code_interpreter` 工具：在受控沙箱中运行 Python（数据分析 / 计算类任务闭环）
- 安全边界：唯一出口 `OpenSandboxService`（业务应用**不持有 docker.sock**）、网络 `DENY`、CPU / 内存配额、存活超时 + 单命令超时双保险、非 root（uid/gid 65534）、全局并发信号量、输出截断
- 静态代码审查 `CodeGuard` + 审计只记摘要（`sandbox_exec`）
- 配置键 `agent-tools.sandbox.*`；⚠️ **随仓配置默认为开启**（`enabled=${AGENT_TOOLS_SANDBOX_ENABLED:true}`），与规划"默认关闭"的口径相反 —— **对外交付请显式设为 `false`**（见"升级注意"）

**治理完善（F-44）**
- **Agent 版本管理**：变更生成全量快照（`agent_definition_versions`）、历史版本 diff、回滚、模板发布钉版本；工作流版本复用既有 `workflow_versions`，不迁移不改名
- **成本分析报表**：`GET /api/monitor/cost/report`、`/cost/export`（CSV），按时间 / 模型 / 来源 / 用户多维聚合；前端 `MonitorView.vue` 页签化并新增"成本分析"
- SQL 见 [docs/sql/v3.1_schema.sql](sql/v3.1_schema.sql)（「F-44 治理」段落）

**灾备与高可用落地（F-46）**
- **多实例集群协调**：集中式限流（`RateLimitStore` 的 Redis / 内存双实现）+ 定时任务租约锁（`ClusterLock`），替换原先"每实例各算各的"限流与"每个实例都会跑一遍"的定时任务
- 备份恢复与主从演练：`scripts/backup-db.ps1|sh`、`restore-db.sh`、`ha-standby-init.ps1`、`docker-compose.ha.yml`、nginx SSE 配置
- 文档：[docs/16-多实例与高可用演练.md](16-多实例与高可用演练.md)（落地与演练）、[docs/13-部署与高可用.md](13-部署与高可用.md)（方案）
- ⚠️ **未做**：Patroni / etcd 自动选主与 VIP、WAL 连续归档 + PITR（**RPO 仍为一天**）、指标与熔断状态的多实例聚合、多实例下 `ToolResultSpillStore` 的共享存储

**工具结果体积治理（F-47）**
- **单点收口**：工具结果在产出处统一压缩一次，回灌模型 / 前端下发 / 落库三处共用同一份，避免"三处各截一次必然漂移"
- 三级降级：超阈值**落盘**（模型可用 `tool_result_read` 分页取回全文）→ **LLM 摘要** → **头尾保留硬截断**（只保头部会丢掉结论与总计行）
- 阈值全可配（`agent.tools.result.*`）；字符上限默认 `6000` 与 V3.0 现值一致，**升级后"上限"行为不变**
- ⚠️ **如实说明**：`window-token-budget` 与 `summary.enabled` 在代码里的默认值（`0` / `false`）被随仓 `application.yml` 覆盖为 `6000` / `true`，即**随仓默认已开启窗口预算与 LLM 摘要**，"与 V3.0 逐字一致"并不完全成立

**长期记忆（本版新增能力）**
- **分层作用域**：用户级（跨 Agent 通用）+ Agent 级（该专家专属），冲突时 **Agent 级覆盖用户级**
- **召回**：常驻（`pinned` / 重要度 ≥ 0.9 / 画像·偏好·纠正三类）+ 关键词条件触发；受上下文预算裁剪，超出部分**整条不注入**（不做半条截断）；过期条目自动归档
- **对话内写入**：`memory` 工具让"请记住 X"真正落库（按内容相似度定位要改/归档的那条，不向模型暴露内部 id）
- **LLM 自动抽取**：每隔 N 轮回看原文，输出 ADD / UPDATE / DELETE / NOOP 做冲突消解；**DELETE 走归档而非硬删**（模型判断是概率性的，归档可一键恢复）；代码侧 2-gram 查重兜底；单次条数上限
- **两层开关**：部署闸门 `agent.memory.auto-extract.enabled`（决定要不要付这笔周期性模型费用）**且** 用户同意 `users.auto_memory_enabled`（可空三态：未表态时跟随部署默认）
- **可见与可管**：记忆页展示、编辑、归档、恢复；自动写入的条目带「**自动记录**」标识与来源会话；概览显示召回策略（由实际参数生成，不写死文案）与开关状态
- 表结构与列见 [docs/sql/v3.1_schema.sql](sql/v3.1_schema.sql)（「长期记忆」段落）；召回健康度巡检 [docs/eval/memory-health.sql](eval/memory-health.sql)

**回答反馈（F-48，计划外新增）**
- 回答点赞 / 点踩 + 原因标签采集（`message_feedback` 表，唯一索引防重复）
- 反馈看板：好评率趋势 + 差评归因；接口 `/api/feedback/*`
- SQL 见 [docs/sql/v3.1_schema.sql](sql/v3.1_schema.sql)（「回答反馈」段落）

**检索与联网**
- **混合检索（向量 + BM25 全文，RRF 融合）**：`agent.rag.retrieval.hybrid.*`，随仓默认开启；所需 pg_trgm 扩展与 GIN 索引已并入建库脚本（见下方脚本合并说明）
- RAG 重排接入：dashscope 走官方 `QwenScoringModel`，OpenAI 兼容走自研 `OpenAiCompatibleScoringModel`（默认关闭，`agent.rag.retrieval.rerank.*`）
- **自建联网搜索替代 Tavily**：`SearxngWebSearchTool`（`web_search`）+ 结果清洗与正文提取，MCP Tavily 服务已移除
- RAG 注入加固（边界标记 / 长度预算）与**引用忠实度校验**（回答中的 `[[n]]` 必须落在本次来源集内，违规写 citation 步骤）
- 检索健康度看板（`/api/retrieval-health/overview`）+ `docs/eval/retrieval-health.sql`

**平台与工程化**
- 用户自定义模型：`/api/user-models`，API Key 加密存储
- 会话角色设定（persona）：`PATCH /api/sessions/{id}/persona`
- 分层测试体系：单元 / 装配 / 集成三层 + 本地验证脚本 `scripts/run-tests.ps1`（`-Full` 跑 Testcontainers 集成测试）
- H2 退出开发与生产路径，降为**测试替身**（`test` scope）：打包产物不再包含 H2
- 统一产品命名「一人公司多模态 AI 助手工作台」

## 📦 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3.5 · Java 17 · WebFlux · Spring Data JPA · PostgreSQL(+pgvector) |
| AI 框架 | LangChain4j 1.19.0（BOM 统一管理） |
| 前端 | Vue 3 · Vite · Element Plus · Pinia |
| 协议 | SSE 流式 · REST · Function Calling · Webhook |
| 可观测 | Micrometer Tracing · Zipkin · Prometheus · Actuator |
| 测试 | JUnit 5 · Mockito · Testcontainers（集成测试按需跑 · H2 仅作测试替身） |

## 🚀 快速开始

```bash
# 后端（Java 17）
# 两项密钥必填：启动期会校验（见下方「破坏性变更」）
export JWT_SECRET=$(openssl rand -hex 32)
export MODEL_API_KEY_SECRET=$(openssl rand -hex 32)
mvn spring-boot:run

# 前端
cd frontend
npm install
npm run dev
```

版本号已升至 **3.1.0**（`pom.xml`）。

**数据库：全新部署零手工操作** —— `docker compose up -d` 会按版本顺序自动执行 `docs/sql/` 下的全部建表脚本（`01_v2.0 → 02_v3.0 → 03_v3.1`，见 `docker-compose.yml` 的 initdb 挂载）。

**从旧版本升级**（已有数据卷，initdb 不会重跑）：

```bash
# 每主版本一个文件，一条命令即可（脚本全部幂等，可重复执行）
psql -U dcdr_agent -d dcdr_agent \
  -f docs/sql/v3.0_schema.sql -f docs/sql/v3.1_schema.sql
```

> 脚本文件已于发布后合并（2026-10-15）：原 `v3.1_ownership / f44_governance / memory / expert_skill / feedback` 五个分脚本并入 **`docs/sql/v3.1_schema.sql`**（段落顺序即依赖顺序 —— f44 建的 `agent_definition_versions` 会被 expert_skill 段 ALTER）；原 v3.0 的 beta / rc / llm_trace 三个分脚本并入 **`docs/sql/v3.0_schema.sql`**；原 **`v3.2_rag_hybrid_schema.sql`**（混合检索所需的 pg_trgm 扩展与 GIN 索引）同样并入 `v3.1_schema.sql` —— 混合检索已随本版交付且随仓默认开启，这段索引此前不进 initdb，全新部署会漏掉。各段落保留了来源标注。
> 另：应用侧 `ddl-auto=update` 会自动补齐表与列，SQL 脚本的价值在于可控、可审查 —— `document_chunks.embedding` 的 `VECTOR` 类型与 pgvector 扩展必须由脚本创建，JPA 建不出来。

### ⚠️ 破坏性变更：两项密钥从"有默认值"改为必填

v3.1.0 起 `JWT_SECRET` 与 `MODEL_API_KEY_SECRET` **必须显式注入**，否则**服务拒绝启动**（`SecretGuard`，报错里直接给出生成命令）。原因：这两个内置默认值随仓发布、**公开可知**，而它们不会自己暴露 —— 服务照常跑、功能照常用，只有"密钥是公开的"被静默吞掉：

| 密钥 | 不配置的后果 |
|---|---|
| `JWT_SECRET` | 签名密钥公开 → 任何人都能**伪造任意用户（含 admin）的 Token**，不需要任何密码 |
| `MODEL_API_KEY_SECRET` | 加密主密钥公开 → 拿到数据库里的密文即可**解出用户真实的模型 API Key** |

生成随机值：

```bash
openssl rand -hex 32                                                   # Linux / macOS（两个密钥各生成一次）
[guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')      # PowerShell
```

- **统一写在项目根目录的 `.env`**：compose 会自动加载它；本机 `mvn spring-boot:run` / IDE 也通过 `spring.config.import: optional:file:.env[.properties]` 读取同一份文件（`optional` 保证容器内不存在该文件时不报错）
- **Docker 部署**：compose 已把两项声明为必填（`${VAR:?}`），未设置会在**启动前**报错
- **本机快速跑通**：可用 `agent.auth.jwt.allow-default-secret=true` 与 `agent.models.allow-default-key-secret=true` 显式放行（**仅限不对外暴露的环境**）
- ⚠️ 更换 `JWT_SECRET` 会使**所有已签发的 Token 立即失效**，用户需重新登录（这是预期的安全属性，不是 bug）
- ⚠️ **已保存的用户自定义模型需要重新填一次 API Key**：升级前若没配 `MODEL_API_KEY_SECRET`，库里的密文是用当时的默认主密钥加密的 —— 换成新密钥后解不出来，页面会提示"主密钥可能已变更, 请重新保存该模型"。这是换密钥的必然结果，不是数据损坏。
  建议接受这一次性重填；**不要**为了省事把新密钥设成旧默认值（那个值同样随仓公开，等于没换）。

### ⚠️ 升级注意：随仓配置里"默认开启"的三项

以下开关在代码里的默认值仍是"关闭"，但随仓 `application.yml` 覆盖为**开启** —— 与新部署方的成本与安全口径相关，对外交付前请按需改回：

| 配置 | 随仓值 | 影响 |
|---|---|---|
| `agent-tools.sandbox.enabled` | `${AGENT_TOOLS_SANDBOX_ENABLED:true}` | 代码执行沙箱默认可被调用（建议对外交付改 `false`） |
| `agent.memory.auto-extract.enabled` | `true` | 每 N 轮一次真实模型调用（**持续性费用**） |
| `agent.skill.hub.enabled` | `true` | 技能市场会访问外部 SkillHub |

## 📝 说明

v3.1.0 聚焦"把 v3.0 打开的能力收干净"：越权面用统一框架闭合，治理补齐版本与成本两块，工具结果从"三处各截"收敛为单点收口，灾备从方案走到可用，并新增长期记忆这条贯穿对话的能力（可见、可改、可溯源、可关）。

- 本版为 V3.1 正式版，版本规划详见 [docs/14-V3.1规划文档.md](14-V3.1规划文档.md)
- **未交付 / 部分交付项**（不得视为已交付）：
  - **F-42 语音对话（STT/TTS）**：后端接口为 V1.1 遗留实现（配置键 `agent.models.audio`，两个语音模型均 `enabled:false`），**前端无录音/朗读入口、无测试** → 本版**未交付**，设计继续存档于 [docs/12-未来可扩展项记录.md](12-未来可扩展项记录.md) 主题②
  - **F-45 远程模板仓库**：仅交付专家与技能体系（技能市场为**发现层**，安装产出元数据骨架 + 待补全正文），远程仓库源未实现
  - **F-46**：自动选主 / VIP、WAL + PITR（RPO 仍一天）、指标与熔断多实例聚合未做
  - **F-41**：横向越权用例尚未全部自动化
- 继续冻结项：完整 SaaS 多租户、本地模型扩大投入（见 [docs/12-未来可扩展项记录.md](12-未来可扩展项记录.md)）
- 高可用实操与未做清单见 [docs/16-多实例与高可用演练.md](16-多实例与高可用演练.md)，专家与技能体系见 [docs/17-专家与技能体系.md](17-专家与技能体系.md)
