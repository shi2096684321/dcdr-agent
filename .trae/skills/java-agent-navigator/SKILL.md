---
name: "java-agent-navigator"
description: "Navigates the java-agent codebase via the project code map (docs/11-代码地图.md) to locate classes, modules, links, and configs without full-repo searches. Invoke FIRST when the user asks about any code location, module responsibility, call chain, or config key in this project."
---

# Java Agent 项目导航

本项目（d:\Projects\java-agent，AI 智能对话助手 V3.0）有一份权威代码地图：`docs/11-代码地图.md`。
回答任何"XX 在哪 / XX 怎么实现 / 调用链路"类问题前，**先读该地图**，按图索骥直接定位文件，只对地图未覆盖的新改动做局部 Grep/Read，避免全库搜索。

## 使用步骤

1. 读 `docs/11-代码地图.md`（一次 Read 全量即可，文件不大）
2. 按问题模块查"包结构速查"或"核心链路"小节，得到目标类路径
3. 仅当需要具体行号/实现细节时，再对已定位的文件做 Read / 精确 Grep
4. 若发现地图与实际代码不符（用户改了结构），以代码为准，并提醒用户更新地图

## 快速索引（地图详解的浓缩版）

- 对话链路：`ChatController → AgentOrchestrator → ModelRouter/IntentRouter → ModelRegistry → CircuitBreakerChatProvider → LangChain4jChatProvider`
- 记忆：`agent/memory/InMemoryChatMemory`（JPA 持久化 messages 表；`max-messages=6` 配置未实现截断，已知缺口）
- RAG：`rag/service/*` + `rag/reader` + `rag/splitter` + `rag/embedding`（text-embedding-v4, 1536 维）
- 认证：`auth/security/SecurityConfig`（路径级 authorizeExchange）+ `SecurityContextWebFilter`；白名单含 /api/models
- 工作流：`agent/workflow/WorkflowEngine`（steps/DAG 双模式，dag_json 优先）
- 多租户/插件/Ollama/限流/熔断：`agent/tenant`、`tool/plugin`、`model/ollama`、`config/RateLimitWebFilter`、`model/circuitbreaker`（默认全关）
- 配置：`application.yml` 的 `agent.*` 与 `agent.v3.*`；JWT_SECRET 环境变量
- SQL 升级顺序：`docs/sql/` v2.0 → v3.0 → v3.0_beta → v3.0_rc（ddl-auto=validate 需手动执行）
- 构建：`$env:JAVA_HOME='C:\Users\Administrator\.jdks\temurin-21'; mvn compile -DskipTests`；前端 `frontend/ npm run build`

## 维护约定

- 用户改动架构/新增模块后说"更新地图"时：同步修改 `docs/11-代码地图.md`，保持 7 个小节结构（布局/包结构/链路/配置/地址/构建/已知缺口）
- 地图中"已知缺口"小节记录未实现的配置与框架限制，是排障第一入口

## 注意

- 不在地图或本 skill 中写入密钥、token 等敏感信息
- AgentOrchestrator 用显式构造器（非 @RequiredArgsConstructor），新增注入字段必须手动更新构造器
