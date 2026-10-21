# dcdr-agent v3.2.0-ui

基于 Spring Boot + Vue 3 的 Agent 编排平台。本版**不新增后端业务能力**，是一次**前端设计体系与全站界面重构**版（版本号后缀 `-ui` 即为此意）：把此前「逐页手写样式」的前端收敛到一套设计令牌 + 一层 Element Plus 组件覆盖，并在对话、创作、知识库、记忆、专家、运维、管理后台、DAG 编辑器八个主界面按设计稿重排；同时完成编排拆分、脚本收敛与真实接口回归三件工程化收口。

> 发布日期: 2026-10-21 ｜ 对应分支: `feature/v3.2.0-ui` ｜ 前序里程碑: [v3.1.0](RELEASE-v3.1.0.md) ｜ 后端能力基线: v3.1.0 ｜ 回归报告: [docs/19-生产级测试报告-v3.2.md](19-生产级测试报告-v3.2.md)

## ✨ 核心特性

**设计体系（本版主线）**
- **设计令牌层** `frontend/src/styles/theme.css`：一套结构、两套取值（深色为默认主题，浅色覆盖），变量统一 `--dcdr-*` 前缀（背景 / 面板 / 文字三级 / 强调 / 语义色 / 圆角 / 动效 / 辉光强度）；**切换主题只改色彩与投影，不改布局、圆角与字号**
- **浅深双模式 + 无闪烁切换**：`index.html` 内联脚本在框架挂载前定主题（避免首屏白闪），`stores/theme.js` 提供 `auto / light / dark` 三态并监听系统偏好；`data-theme`（自有令牌）与 `class="dark"`（Element Plus 暗色变量）同时写入，不依赖构建期插件
- **Element Plus 组件覆盖层** `frontend/src/styles/element.css`：此前只映射了颜色变量、没覆盖形状，全站按钮仍是 EP 默认的 32px / 4px 圆角；本版统一到设计稿的 **34px 高 / 8px 圆角 / 主按钮品牌渐变**，并覆盖按钮尺寸档、页签（胶囊化）、标签、输入框、表格、弹层等容器。**颜色不在此文件重复声明**，全部走 `--el-* → --dcdr-*` 映射，避免两处口径打架
- **字体**：自托管等宽 JetBrains Mono（拉丁子集，3 个自适应字重约 65KB，OFL 授权，授权文本随仓）；中文正文不打包字体（Noto Sans SC 全量子集 6–8MB，不划算），改为按字形接近度排序系统字体（PingFang → HarmonyOS Sans / MiSans → 思源黑体 → 雅黑兜底）
- **令牌化收敛**：20 个页面与组件的硬编码颜色收敛到主题令牌；清理全站旧品牌紫与 emoji 图标，图标改线性 SVG；空状态改线性图标
- 设计资产随仓：设计稿 PDF、11 张界面稿、`docs/design/tokens.json`（DTCG 格式导出，`theme.css` 为唯一实现来源）、应用图标（PNG + SVG mark）

**布局与导航**
- 顶栏收敛为**全局命令入口**（不再承载业务按钮），左侧导航改为**图标轨道**（`IconRail`），主内容区留出整块宽度
- 新增**命令面板** `CommandPalette.vue`：一处检索页面跳转与主题切换，避免在 20 个页面间来回找入口
- 新增**主题切换器** `ThemeToggle.vue`（三态循环：跟随系统 / 浅色 / 深色）

**页面重构（按设计稿逐页落地）**
- **对话**：补齐三栏骨架与右侧上下文面板（会话信息 / 记忆 / 知识库上下文）；会话列表标题不再被悬浮操作按钮挤成三个字；气泡与对话页硬编码色全部令牌化；页头字号收敛到 15px
- **创作**：改为**两栏 + 底部胶片条**布局；主按钮统一品牌蓝紫渐变；**导出 ZIP 落地**（自研最小 ZIP 写入器 `utils/zip.js`，store 模式不压缩 —— 导出的 PNG / JPEG / MP4 本身已压缩，为省这点体积不值得引入 jszip）
- **知识库**：改为「列表 + 文档表 + 常驻检索测试」三段式，检索测试不再需要跳页
- **记忆**：改为**左列表 + 右统计栏**；页头降密、工具栏分组、卡片操作与「常驻」标记统一
- **专家**：改为 **2×2 卡片网格 + 右栏（技能 / 概览）**，去掉重复页签；移除与专家无关的全局调用日志表，运行概览补「最近一次调用时间」
- **运维**：调用链与智能路由改为**指标条 + 行式列表**，展开阅读链路与判断依据；调用链详情改**粘性头部 + 时间轴**；路由详情改置信度刻度 + 分节排版；内层页签胶囊化；修复抽屉被顶栏合成层遮挡
- **管理后台**：页签胶囊化，指标卡与表格按设计稿重排
- **DAG 编辑器**：画布按设计稿重做（节点、连线、缩放控制条）
- 跨页修复：浅色模式整页发灰、两套主题对比度不达标、空数据下时长格式化出 `NaN`、按钮禁用态缺描边

**后端随版修复（不属 UI，但随本版发布）**
- **Embedding 模型维度与 schema 不匹配（阻塞级）**：随仓配置的 `qwen3.7-text-embedding-flash` 即使传 `dimensions=1536` 也只返回 **1024 维**，写库时报 `expected 1536 dimensions, not 1024` —— 即**知识库入库 100% 失败**，且启动期只校验「配置值 vs schema」，一路绿灯。已固定为实测返回 1536 维的 `text-embedding-v4`，并在 `application.yml` 留实测注释
- **平台自述注入**：`prompts/chat.md` 新增【平台自述】，让模型知道自己在什么系统里运行；实测真实对话回答「我是 dcdr（多肉）……一人公司多模态 AI 助手工作台」，截图 `docs/images/10-self-intro.png`
- 配置注释口径修正：明确「代码默认关闭、随仓 `application.yml` 覆盖为开启」的三项（沙箱 / 记忆自动抽取 / 技能市场），并对 `platform-kb-ids` 标注为本部署环境特定值

**工程化与运维**
- **编排拆分**：`docker-compose.yml` 只跑中间件，应用独立到 `docker-compose.app.yml`（通过固定网络 `dcdr-net` 互通）。改代码只需重建应用，不再连带重启 PG / Redis；`docker-compose.ha.yml` 需叠加在 app 文件之上（跨文件服务不可见，故 app 文件里不能写 `depends_on`）
- **运维脚本收敛**：删除 `scripts/run-tests.ps1`（纯包装 `mvn test` / `mvn verify`）与 `scripts/backup-db.sh`（第二实现且默认库名过期）；新增 `scripts/README.md` 作为单一索引，明确「只放运维动作，不放开发流程」
- **SQL 按主版本合并 11 → 4**（`v2.0_schema` / `v3.0_schema` / `v3.1_schema` / `README`）：原 beta / rc / llm_trace 三份并入 v3.0，原 5 份功能分脚本与原 `v3.2_rag_hybrid_schema.sql`（pg_trgm + trigram GIN）并入 v3.1，**段落顺序即依赖顺序**；全新部署零手工 SQL
- **前端环境约束**：`engines` 锁定 Node 18+ / 20+，`predev` / `prebuild` 先跑 `check-node.mjs`（把「Node 版本不对导致构建异常」提前到命令入口）
- **镜像同步**：GitHub Actions 每日从 Gitee 单向同步（Gitee 为源头，GitHub 为镜像）

**测试与文档**
- 新增**真实接口回归脚本** `tests/api-smoke.mjs`（51 条，真实 HTTP + 真实 PostgreSQL，数据落库保留），逐条记录 HTTP 状态 / 业务码 / 耗时，结果 JSON 落 `tests/results/`；当前基线 **PASS 49 / FAIL 1 / SKIP 1**
- 单元/装配测试 **392 条，全部通过**（`mvn test`，零外部依赖，约 1 分钟）
- 新增文档：[本地运行与部署教程](18-本地运行与部署教程.md)、[生产级测试报告 v3.2](19-生产级测试报告-v3.2.md)、[项目自述（供知识库导入）](20-项目自述（供知识库导入）.md)
- 新增 10 张产品截图（`docs/images/`），README 特性总览与目录结构树与现状对齐

## 📦 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3.5 · Java 17 · WebFlux · Spring Data JPA · PostgreSQL(+pgvector) |
| AI 框架 | LangChain4j 1.19.0（BOM 统一管理） |
| 前端 | Vue 3 · Vite 5 · Element Plus · Pinia · 自建设计令牌层与 EP 覆盖层 |
| 协议 | SSE 流式 · REST · Function Calling · Webhook |
| 可观测 | Micrometer Tracing · Zipkin · Prometheus · Actuator |
| 测试 | JUnit 5 · Mockito · Testcontainers（`mvn verify`）· `tests/api-smoke.mjs`（真实接口回归） |

## 🚀 快速开始

```bash
# 后端（Java 17）—— 两项密钥必填，未设置则拒绝启动
export JWT_SECRET=$(openssl rand -hex 32)
export MODEL_API_KEY_SECRET=$(openssl rand -hex 32)
mvn spring-boot:run

# 前端（Node 18+）
cd frontend
npm install
npm run dev
```

纯容器方式（本版起**依赖与应用分开编排**）：

```bash
docker compose up -d                                    # 1) 中间件（postgres / redis / zipkin / searxng / sandbox）
docker compose -f docker-compose.app.yml up -d --build  # 2) 应用（含前端静态资源）
```

版本号已升至 **3.2.0-ui**（`pom.xml`、`frontend/package.json`）。

**从 v3.1.0 升级**：
- **无数据库结构变更**，不需要执行任何 SQL；SQL 脚本的目录合并只是整理，内容等价于 v3.1.0 已发布版本
- **无后端接口破坏性变更**，前端重新构建即可（`npm run build` 或重建应用镜像）
- ⚠️ **改了 embedding 模型，向量空间随之改变**：若库中已有用旧模型成功入库的文档，需**重新向量化**（重建索引）后再检索，新旧向量不可混用。此前该配置下文档基本全为 `failed`，这种情况下无需额外处理，重新上传/重跑入库即可
- ⚠️ 随仓默认开启的三项不变（沙箱 / 记忆自动抽取 / 技能市场），对外交付前按 [v3.1.0 发布说明](RELEASE-v3.1.0.md) 中「升级注意」一节的表逐个确认

## 📝 说明

v3.2.0-ui 交付的是「看得见的部分」：把设计稿变成可维护的实现 —— 令牌层是唯一颜色来源，EP 覆盖层是唯一形状来源，页面里不再出现散落的十六进制色与 emoji 按钮。功能面与 v3.1.0 保持一致，另随版修复了 embedding 维度这个会直接让知识库失效的问题。

- 本版为 **UI 版**，不替代功能版本号：下一功能版仍为 V3.2 / V3.3（规划见 [docs/07-V3.0规划文档.md](07-V3.0规划文档.md) 远景表与 [docs/14-V3.1规划文档.md](14-V3.1规划文档.md) 约束）
- **已知问题**（详见 [回归报告 §三](19-生产级测试报告-v3.2.md)，本版未修复）：
  - **DEF-03（高）**：模型上游调用失败时 `POST /api/chat` 仍返回 `code=200`，把 `调用失败: {厂商错误}` 直接写进回答正文 —— 前端把错误当正常回答渲染，成本与成功率也会计为成功
  - **DEF-01（中）**：`/api/auth/refresh` 成功后旧 Access Token 立即失效（`TOKEN_REVOKED`），静默刷新瞬间在飞的请求会 401
  - **DEF-02（低）**：业务错误（不存在 / 越权 / 校验失败）HTTP 状态恒为 200，错误放在 `body.code`，网关与 APM 无法按状态码判定
  - **OBS-01（低）**：`/actuator/health` 恒为 DOWN（外部模型探测进入主 health，超时 3s），可能被 K8s / LB 误摘
- **未覆盖**（不得视为已验证）：流式 SSE 与工具调用端到端、混合检索 / 重排、图片·视频·音频生成（缺 Key 与配额）、工作流真实执行与断点续跑、多实例 + PG 主从演练、前端 E2E（本轮以真实截图人工核对代替）
- **构建产物**：前端单 chunk 约 2.18MB（gzip 732KB），本轮未做路由级代码分割 —— 内网 / 桌面使用可接受，公网弱网首屏会偏慢，后续可接 `manualChunks` 或动态路由
- 冻结项不变：完整 SaaS 多租户、本地模型扩大投入（见 [docs/12-未来可扩展项记录.md](12-未来可扩展项记录.md)）
