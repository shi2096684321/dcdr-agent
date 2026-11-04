# dcdr-agent v3.3.0

基于 Spring Boot + Vue 3 的 Agent 编排平台。本版两条主线：**让模型能在用户自己的电脑上干活**（F-49 工作区与本地文件操作），以及**让定时任务的结果主动找到人**（F-50 执行结果推送）。同时把上一阶段遗留的联网检索质量问题集中修了一轮 —— 搜索与抓取的可观测性、排序责任、抓取内容质量、检索成本口径。

> 发布日期: 2026-11-04 ｜ 对应分支: `feature/v3.3.0` ｜ 前序里程碑: [v3.2.0-ui](RELEASE-v3.2.0-ui.md) ｜ 版本规划: [docs/22-V3.3规划文档.md](22-V3.3规划文档.md)

## ✨ 核心特性

### F-49 工作区与本地文件操作

用户选定一个本地目录作为工作区，模型在该目录内读写删；每一轮对话的改动都能整体撤回。

- **六个工具**：`workspace_read` / `workspace_write` / `workspace_edit` / `workspace_delete` / `workspace_list` / `workspace_search`，全部受 `agent.workspace.enabled` 控制，`local-mode=false`（服务器部署）时整体不注册
- **路径安全**：`WorkspacePathResolver` 统一解析，越出工作区、命中 `deny-read` / `deny-write` / `deny-delete` 的路径直接拒绝；软链与 `..` 归一化后才判定
- **删除进回收区**：`WorkspaceTrashService` 接管删除，不直接 `unlink`，可回捞
- **三级撤回**：
  - L1 单操作撤销 —— `POST /api/sessions/{id}/journal/{opId}/undo`
  - L2 按轮次撤回 —— `POST /api/sessions/{id}/rewind`
  - L3 整会话回滚 —— `POST /api/sessions/{id}/rewind-all`
- **变更日志与快照**：`WorkspaceJournal` 记录每次写操作；`WorkspaceChangeService` 提供 diff 视图（`GET /api/sessions/{id}/changes`），内容寻址快照支撑撤回
- **前端**：`WorkspaceSelector.vue`（目录选择与绑定）、`WorkspaceChangePanel.vue`（改动清单 + 逐条撤销），对话页有工作区徽标与撤回入口
- **配置**：`agent.workspace.*`，包含 `enabled` / `local-mode` / `browse-enabled` / `max-depth` / `deny-*` 等

> ⚠️ **安全语义**：随仓 `application.yml` 把 `enabled` 覆盖为 `true`（`WorkspaceProperties` 的字段默认值是 `false`）。也就是说**按本仓配置启动即等于把本机文件读写权限交给模型**。服务器部署请显式设 `AGENT_WORKSPACE_ENABLED=false` 并保持 `local-mode=false`。

### F-50 定时任务执行结果推送

- **两个渠道**：企业微信群机器人（`WECOM_BOT`）、钉钉群机器人（`DINGTALK_BOT`）。钉钉支持加签（`SEC` 开头密钥）
- **执行记录表** `scheduled_task_runs`：每次执行都落一条（含手动触发，便于回看；手动触发不推送），单任务保留最近 50 条，含 `status` / `session_id` / `share_token` / `share_expires_at`
- **推送内容**：默认推 `{{summary}}` 摘要（企微 900 字 / 钉钉 3000 字），完整正文走详情链接；模板目录 `config/notify/templates/`
- **节流**：成功消息按 `throttle-window-ms`（默认 10 分钟）去重，**失败消息不受节流**
- **推送失败不改任务状态**：`last_status` 只反映执行结果，推送异常单独记录并在界面展示
- **接口**：`GET /{id}/notify-preview`（预览渲染结果）、`POST /{id}/notify-test`（立即验证连通性，不触发执行、不写 `notify_last_*`、不占节流窗口）、`GET /{id}/runs`、`GET /{id}/runs/{runId}`（列表只给正文前 120 字摘要，详情才返回全文）
- **用户级推送配置**：`user_notify_configs` 表 + `/api/notify-config`（查询 / 保存 / 删除 / 测试）。每个用户配自己的 Webhook，AES-GCM 加密落库，接口只回显掩码；个人配置优先于部署级，关闭后回退部署级

#### IM 排版：表格转岗位卡片

企微（旧版 `markdown`）与钉钉的群机器人 markdown **都不支持表格**，直接发 `| 公司 | ... |` 只会显示成一堆竖线。`NotifyRender.forIm` 因此把表格转成卡片：

- 第一列（+ 第二列）合并成加粗标题，其余列按 `列名：值` 每 3 组一行
- **行尾两个空格 + 换行做硬换行** —— 钉钉会把单 `\n` 折叠成一行，只写单换行时三行字段会挤在一起，这正是"推送没有样式"的实际原因
- 正文里的 `[[1]]` 引用角标被移除（IM 没有来源卡片，角标只是噪音）
- 裸 URL 单元格压成 `[链接](url)`，省下的字符够多放一条数据

### 联网检索改造（无功能编号）

这一批不在 V3.3 规划的功能清单里，是本版随行修复的检索质量问题。

**搜索与抓取分开记录，各自保留内容**
- 调用链里一次联网从"一条只有四个数字的步骤"变成两条独立步骤：`web_search` 带每条结果的标题 / URL / 摘要，`web_extract` 带每篇的 URL / 标题 / 成败 / 字数 / 正文全文（失败带原因）
- 串联抓取标 `phase=enrich`，健康度统计据此把它排除在"联网调用次数"之外，避免一次搜索 + 一次抓取被算成两次调用、稀释空结果率与 p95
- 文件日志同步分块输出 `SEARCH CONTENT` / `FETCH CONTENT`，一次 `log.info` 打完一块

**排序责任归位**
- **DeepSeek**：官方按 `rerank_score`（0~3，综合相关性 / 权威性 / 时效性）降序返回，我们只做阈值过滤与去重，**保序**
- **SearXNG**：多引擎聚合顺序不代表相关性，清洗截断前按 `score` 降序就地重排（之前完全没排，等于随机截前 N）
- **抓取候选**：此前按 `articleScore`（URL 里有没有 `/job/`、数字 id）降序取前 N，与相关性无关 —— 官方第 1 的详情页会被第 8 位的列表页挤掉。改为按上游顺序取前 N，`articleScore` 降级为"能不能抓"的可行性判据

**抓取策略：1 篇兜底 + 模型按需**
- `web_extract` 本来就是注册给模型的独立工具，但提示词里只教了 `web_search`，模型一次都没主动调过
- 串联抓取从每次 3 篇降为**每次 1 篇**（打分最高的那篇，保证最坏情况也有正文兜底）；其余条目交给模型按需抓
- `prompts/runtime-web-search.md` 明确 `web_extract` 的触发条件（摘要缺关键细节）、限制（一次 1~2 个、已有正文的不重复抓）

**正文质量：按 query 挑段，摘要不再被顶掉**
- 抓到的正文此前取开头 N 字。招聘 / 门户页开头常是 300 字无标点拼接的导航块（`首页职位公司校园意外险下载APP 我要招聘…`），`cleanMarkdownContent` 清不掉（没有分隔符可判），配额只有 800 字时等于用"300 字噪音 + 500 字正文"换掉 600~800 字的提炼摘要
- 新增 `pickRelevantSpan`：固定长度滑窗 + query 词命中密度打分（命中按词长加权），尾窗单独计，起点回退到最近句号并加 `…` 标明节选
- `bestBody` 改为 content 挑段占 3/4 配额 + summary 占 1/4 作补充；摘要若只是正文前 20 字的复述则跳过
- **`content` 仍存全文**，挑段只发生在拼提示词时，调用链里的正文不受影响

**搜索分页与停止条件**
- `web_search` 增加可选 `page`：SearXNG 映射 `pageno`，仅允许 1~2 页，越界直接拒绝（不发网络请求，也不静默改成第 2 页）
- DeepSeek 官方 Search API 没有分页参数，schema **不暴露 `page`**，避免模型发起必然无效的调用
- 每次翻页仍算一次 `web_search`，不能借分页绕过 `max-search-per-turn`
- 提示词从"够了就停"改为"**再次搜索前必须指出具体缺口**"（哪个关键问题未覆盖、已有来源为何答不了、下次用什么不同关键词）。没有无条件宣称"资料已经足够"，避免模型拿着不足的资料硬答

**成本与健康度**
- `WebUsageService.DayUsage` 增加 `billableSearch` / `billableExtract` / `searchCost` / `extractCost` / `cost`，按**每次调用所属来源的单价**逐天累加（不是拿总费用平均摊），免费源不会被摊出费用
- 前端「联网用量与成本」新增「每日花费」表，搜索费用单独成列；未配置单价时显示 `—` 而不是伪造 `¥0`
- 修复单价 Map 的 key 归一化问题：Spring 绑定会去掉 key 里的冒号，原按字面查表永远 miss，导致成本恒为 0；现在两边归一化后比较，并在"配了单价但没有任何来源命中"时显式提示

### 其它

- **定时任务提示词输入框**：弹窗 600px → `min(920px, 94vw)`，输入框最少 10 行、自动长到 18 行、可手动拖拽，弹窗内容超高时内部滚动，禁用遮罩点击关闭（避免编辑长提示词时误关丢失）
- **检索条数上限**：`max-results-limit` 15 → 10
- **文档对齐**：`docs/21`、`docs/22` 按实际实现重写（工作区默认值、推送摘要策略、用户级配置、实际接口清单与配置项、数据模型新增列），`docs/sql/v3.3_schema.sql` 里用户级推送段落的 "V3.4" 注释改为 "V3.3 补丁"

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
cd frontend && npm install && npm run dev
```

数据库初始化脚本按版本顺序执行：`docs/sql/v2.0_schema.sql` → `v3.0_schema.sql` → `v3.1_schema.sql` → `v3.2_schema.sql` → `v3.3_schema.sql`。

## ✅ 验证

- **单元 / 装配测试 744 条，0 失败，2 跳过**（`mvn -o test`，零外部依赖，约 2 分钟）
- `mvn -o compile`、`npm run build` 通过
- 本版新增测试类：`ExtractCandidateTest`（6）、`SearxngEngineRankTest`（4）、`WebSearchToolPaginationTest`；扩充 `RebuildCompactTest`、`LlmCallTraceTest`、`WebHealthServiceTest`、`WebUsageServiceTest`、`NotifyImRenderTest`

## ⚠️ 已知限制

- **SearXNG 的 `score` 来自不同引擎，量纲不一致**，直接降序只是"能用"。要更准需要评测集 + 引擎权重，未做
- **同轮多个 `web_search` 仍是串行执行**（`for` 循环内搜索完立刻抓取），一轮 3 次搜索 + 3 次抓取约 36s。改为"先全部搜 → 合并去重 → 全局排序 → 并发抓 top-N"约 29s，且排序主键应换成各搜索内的排名 —— 方案已记录在 `TODO.md`，未实施
- **抓取正文的挑段是词命中密度**，不是 rerank 模型 / embedding。对"跳过导航块"这类问题够用，对语义改写程度高的页面不如 cross-encoder
- **企微要用真表格需 `markdown_v2`** 且客户端 4.1.38+，当前统一走卡片列表
- **工作区默认开启**（见上文安全提醒），生产部署务必显式关闭
- `docs/21` 的验收清单标注为"待逐项留档"：功能已落地，人工验证结果尚未全部回填

## 🙏 说明

本版把上一阶段被忽略的一个事实纠正了过来：**抓取并不总是让模型看到的内容变好**。当抓取正文取的是开头 N 字、而搜索结果本身带高质量长摘要时，抓取反而会用一个含大段导航的片段替换掉精炼摘要 —— 每次多花 5~10 秒换来更差的输入。判断这类问题的依据不是设计文档，而是日志里的真实正文，这也是本版把"搜索内容 / 抓取内容分开记进调用链"的原因：没有可回看的原始内容，就没法判断一次检索到底值不值。
