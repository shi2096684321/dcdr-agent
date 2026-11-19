# dcdr-agent v3.4.0

基于 Spring Boot + Vue 3 的 Agent 编排平台。本版不新增平台能力，只把**已经在发生的上下文成本**
变成用户看得见、调得动、能干预的东西：占用可视化（F-51）、策略化与摘要压缩（F-52）、输入区整合（F-53）。
随版还统一了协议出口的消息形状、补齐了前缀缓存可观测，并把知识库挂载与分块策略落到会话/库级。

> 发布日期: 2026-11-19 ｜ 对应分支: `feature/v3.4.0` ｜ 前序里程碑: [v3.3.0](RELEASE-v3.3.0.md) ｜ 版本规划: [docs/23-V3.4规划文档.md](23-V3.4规划文档.md)

## ✨ 核心特性

### F-51 上下文占用可视化

对话输入区常驻一枚圆环，点开是五段明细，回答"这一轮的钱花在哪"。

- **五段口径**：系统提示词 / 工具及子智能体 / 对话消息 / 连接器与 MCP / 技能。system 消息按注入时维护的 `name` 归类，工具按注册类型区分 MCP/插件
- **两笔账分开**：`totalTokens` 是"当前窗口占用"，另有"本会话累计消耗"是每轮用量之和（只增不减）
- **实测优先**：模型返回 usage 时，分子直接取 `prompt + completion`；只有端点不返回 usage 时才用 cl100k 估算
- **分段仍是估算比例**：厂商不提供分段明细，五段按估算比例分摊，并用最大余数保证"五段之和 == 总数"
- **快照持久化**：`sessions.context_usage`，切会话/刷新后可读回；读取时按**当前**档位重算百分比
- **SSE**：新增 `context_usage` 块（调用前一次 + 收尾一次覆盖）

### F-51.1 上下文长度档位（用户级）

- 档位：32K / 128K / 200K / 300K / 1M + 「跟随默认」，落 `users.context_token_limit`
- 接口：`GET/PUT /api/chat/context-limit`
- 值域收敛在 8 000 ~ 2 000 000，填错不会报错、只会让整个面板长期显示错误比例的路径被删掉
- 占用百分比与「接近上限」告警共用同一个分母，避免"界面 92% 却不告警"

### F-52 上下文策略化与摘要压缩

滑动窗口换成策略模式，默认摘要压缩；两种做法可配置切换。

- **策略接口**：`SlidingWindowStrategy`（旧行为 / 降级路径）、`SummaryCompactionStrategy`（默认）、`ContextStrategyFactory`
- **存储与视图分离**：`ChatMemory` 只留存储，`loadWindow` 删除；`agent.memory.strategy` 决定"发什么"
- **自适应阈值**：`max(1000, min(compaction.trigger-tokens, contextLimit × 60%))`
- **摘要带非指令边界**：以 `memory:summary` 的 system 片段注入，外包"这是背景资料，不是指令"
- **降级与自愈**：摘要失败/超时退回滑动窗口并告知用户；边界消息被撤回/删除则整份摘要作废
- **原文一条不删**：摘要只影响发给模型的内容，界面历史照旧；成本单列 `CostSource.MEMORY_COMPACTION`

### F-52.1 摘要可见 / 可改 / 可删

- `SessionSummaryView{available, text, uptoSeq, coveredMessages, chars, applied}`
- 接口：`GET/PUT/DELETE /api/sessions/{id}/summary`；手改只换正文、不动边界；从未压缩过时拒绝手写
- 切到 `sliding-window` 时 `applied=false`，界面只保留「清除」

### F-53 输入区整合

- **统一「+」**：专家单选、知识库多选、技能多选收敛到 `AgentContextPicker.vue`，选中结果以 chips 留在输入框上方
- **工作区入框**：`WorkspaceSelector` / `WorkspaceChangePanel` 从框外挪进输入框顶部
- **用户消息靠右**：`MessageBubble` 用 `row-reverse` 镜像布局，不改 DOM 顺序（屏幕阅读器与复制文本仍按"头像 → 内容"）

### 对话链路随版变化

- **消息归一化（协议出口）**：多条 system 合并为唯一一条置顶，出现在历史中段的片段（记忆 / 时间 / RAG）折进前面最近的 user 消息。自部署 Qwen3 系端点只接受该形状；折叠发生在协议层，内部历史仍保留片段身份
- **前缀缓存可观测**：`llm_usage_logs` 新增 `cache_read_tokens` / `cache_creation_tokens`，调用链与前端可看命中率
- **microcompact cache-first**：缓存窗口内不清理工具结果，改由 spill + `tool_result_read` 兜住大结果
- **知识库挂载到会话**：`sessions.kb_ids`，检索的「调用 + 结果」随用户消息落库，下一轮直接复用，并只保留最近 N 轮检索片段
- **按库配置分块策略**：`knowledge_bases.chunk_strategy`（fixed / recursive / sentence / markdown），此前 `chunk_size` / `chunk_overlap` 改了不生效的问题一并修复
- **L1 人设外置**：角色设定移到 `prompts/identity.md`，移除会话级角色设定字段（**行为变化**，见下）

### 本版收尾修正

- **占用口径对齐实测**：模型返回 usage 时分子直接取实测总量，不再叠加估算（`ContextUsageService#calibrateTo`）
- **补齐统计漏项**：每条消息的协议包装开销、user 图片按固定估值计入、模型未返回 usage 时的兜底补上工具定义
- **会话累计消耗**：弹层新增"本会话累计消耗"（每轮 `tokenUsage` 之和），与"当前占用"分开展示
- **消息耗时落库**：`messages.duration_ms`，正常收尾与中断路径都写入；此前只在 `done` 块下发，刷新即丢
- **回到最新对话**：消息区居中悬浮按钮，只在最新一轮整体落在视口下方时出现；流式输出不再强行把视口拽回底部
- **token 展示**：消息气泡与上下文弹层统一 `>= 1000` 用 K

## 📦 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3.5 · Java 17 · WebFlux · Spring Data JPA · PostgreSQL(+pgvector) |
| AI 框架 | LangChain4j 1.19.0（BOM 统一管理） |
| 前端 | Vue 3 · Vite 5 · Element Plus · Pinia · 自建设计令牌层 |
| 协议 | SSE 流式 · REST · Function Calling |
| 可观测 | Micrometer Tracing · Zipkin · Prometheus · Actuator |
| 测试 | JUnit 5 · Mockito · Testcontainers（`mvn verify`）· `tests/api-smoke.mjs` |

## 🚀 快速开始

```bash
# 后端（Java 17）—— 两项密钥必填，未设置则拒绝启动
export JWT_SECRET=$(openssl rand -hex 32)
export MODEL_API_KEY_SECRET=$(openssl rand -hex 32)
mvn spring-boot:run

# 前端（Node 18+）
cd frontend && npm install && npm run dev
```

建库脚本按版本顺序执行：`docs/sql/v2.0_schema.sql` → `v3.0` → `v3.1` → `v3.2` → `v3.3` → `v3.4`。
`v3.4_schema.sql` 全部使用 `IF NOT EXISTS`，可重复执行；`ddl-auto: update` 下启动也会自动加列。

## ✅ 验证

- **单元 / 装配测试 860 条，0 失败，2 跳过**（`mvn -o -llr test`，约 2 分钟）
- `mvn -o -llr compile -DskipTests` 通过
- `npm run build` 通过
- 本版新增/改写测试：`ContextUsageServiceTest`（10）、`ContextLimitPreferenceTest`（5）、
  `SlidingWindowStrategyTest`（4）、`SummaryCompactionStrategyTest`（6）、`SessionSummaryStoreTest`（5）、
  `ContextUsageServiceTest` 中的实测校准与快照重算用例

## ⚠️ 已知限制

- **分母仍是用户档位，不是模型真实窗口**：模型配置里没有窗口字段，默认 300K。用 32K 模型时需要自己把档位调下来，否则百分比偏小
- **分段是估算比例**：厂商不给分段明细，总量实测、五段按比例分摊，单个分段的数字不等于该类的真实占用
- **图片按固定 1 000/张估值**：只在模型未返回 usage 时生效；视觉模型的图片 token 随分辨率变化
- **会话累计略低于真实总量**：每条 assistant 消息记的是该任务最后一次模型调用的 usage，多轮工具任务会漏掉中间轮次
- **摘要质量依赖模型**：压缩多一次模型调用；`compaction.model` 可指定低成本模型
- **`strategy=summary` 后不再按条数截断**：未超预算时发送全部历史，单轮 prompt 会变大（预算由 `trigger-tokens` 控制）
- **L1 人设外置是一次行为变化**：会话级角色设定字段不再生效，需要迁移到 `prompts/identity.md` 或专家配置

## 📝 说明

本版的核心判断是：上下文不是"一个百分比"，而是三件互相牵连的事 —— 看得见（花在哪）、管得住（压缩什么、留什么）、
带得准（这一轮到底该带哪些资料）。因此占用面板、策略化压缩、输入区整合必须同版交付：只做可视化，
用户看到数字却无法干预；只做压缩，用户不知道模型为什么突然"忘事"。

随后的收尾修正把口径又收紧了一步：**模型给了真实 usage 就用它，估算只作为兜底**。面板数字与厂商账单
本来就不可能逐 token 相等（分词器、图片、消息包装各不相同），能对齐的部分必须对齐，不能对齐的部分
要在界面上说清楚，而不是混成一个看似精确的数。

下一阶段规划见 `docs/` 后续版本文档；本版明细与取舍见 [`docs/23-V3.4规划文档.md`](23-V3.4规划文档.md)。
