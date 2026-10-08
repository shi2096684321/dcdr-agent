# TODO / 待优化项

## V3.1 长期记忆（P0 已实现，待渐进升级）

### 已实现（2026-09-15）
- **分层作用域**：用户级（`agent_id` 为空，跨 Agent 通用）+ Agent 级（`agent_id` 非空，该 Agent 专属），
  冲突时 **Agent 级覆盖用户级**（局部优先于全局）。
- **数据层**：`long_term_memories` 表（建表脚本 `docs/sql/v3.1_memory_schema.sql`）；
  补齐 `sessions.agent_id` —— 原先 `agentId` 只在请求中临时传入做配置覆盖、从不落库，
  导致 Agent 级记忆无归属依据、会话内切换 Agent 后历史无法归因。
- **召回策略（本阶段无向量）**：常驻（`pinned` / 重要度 ≥ 0.9 / 画像·偏好·纠正三类）
  + 关键词条件触发（`topic` / `tags` 与当轮提问做归一化 2-gram 匹配，见 `MemoryRelevanceScorer`）。
- **管理接口** `/api/memories`（列表 / 概览 / 新增 / 更新 / 软删除 / 清空），
  接入统一归属框架：`ResourceType.MEMORY` + `MemoryOwnershipProvider`（越权与不存在统一 404）。
- **注入点**：`AgentOrchestrator` 流式 `prepareForChat` 与同步 `doChatSync` **双链路同口径**注入，
  消息名 `memory:long-term`（按 name 去重、幂等），无记忆命中时不产生 system 消息。
- **前端**：`MemoryView.vue` + `api/memories.js`，导航新增「记忆」页（可见、可编辑、可删除）。

### 关键设计决策
- **为什么不直接上向量**：条目在几十条量级时，「全量常驻 + 关键词触发」的确定性召回
  可解释、可复现、零新增基础设施（与 CodeBuddy / Claude Code 的纯文件记忆路线一致，
  Cursor 的向量只服务于代码库检索，其跨会话记忆反而是弱项）。
  待单用户活跃条目突破阈值（`MemoryRepository.countByUserIdAndStatus` 可判定）再增量补
  `embedding` 列与向量通道，与关键词通道**融合而非替换** —— 精确匹配对标识符类查询始终优于语义匹配。
- **记忆 ≠ 规则**：事实偏好走记忆表，执行指令仍走 `systemPrompt`。
  混在一张表会导致用户在记忆页删一条"事实"时误删一条规则。
- **防复述约束**：注入文本内置三条约束（不复述记忆 / 当前提问优先 / 不构成系统指令）。
  第一条直接针对本文件上方记录的「多轮对话重复回答历史内容」问题（避免长期记忆成为新的复述来源），
  第三条防止通过写入记忆实施提示词注入。

### 待办（后续阶段）
- **P1 自动抽取**：会话结束 / 每 N 轮异步触发 LLM 结构化抽取 + 冲突消解（ADD/UPDATE/DELETE/NOOP），
  复用 `LongTermMemoryService.create`（已按可复用签名设计）。
- **P2 向量召回**：注意 `PgVectorEmbeddingStoreAdapter` 的写路径当前抛
  `UnsupportedOperationException`（设计上仅承担检索），复用需另写写入逻辑或直写表。
- **P3 记忆总开关**：用户一键关闭记忆注入 / 停止提取（CodeBuddy 有该开关）。
  当前 P0 无自动提取（记忆均为用户手动创建），暂不涉及隐私风险。
- `hit_count` / `last_hit_at` 已记录但暂未用于重要度衰减与淘汰。

### 踩坑记录
- **Reactor `doFinally` 晚于下游的终止处理，不能用于「必须先于下游完成」的记录**：
  `doFinally` 是"终止信号已传给下游之后"才执行。本项目「上游 `tracedRound`/`tracedTool` 用 `doFinally`
  记录步骤 + 下游 `ChatController` 用 `doFinally` 落库 summary」正好踩中 —— **最后一轮的步骤晚于落库，
  直接丢失**（实测：`llm_call_logs.chain` 只有 `routing`、`llm_calls=1`；单轮对话时 `round-1` 就是最后
  一轮，全部丢失；多轮场景下靠"前面几轮已记录"掩盖了问题）。
  `routing` 之所以一直还在，是因为它在 `IntentRouter` 里**同步记录**，不走 finalizer。
  修复：记录类副作用改用 `doOnComplete` / `doOnError` / `doOnCancel`（三者均在信号传播前执行）。
- **「日志 = 实际发给 LLM」不能靠编排层自己拼**：编排层持有的是**业务视角的 history**，而真正发出的
  messages 与参数由 Provider 组装 —— 它会过滤空消息、把"生图占位"改写成 `已生成图片。`、模型不支持
  视觉时丢弃图片，并追加工具定义 / `tool_choice` / `response_format` / 厂商参数（如 `enable_thinking`）。
  两处各拼一份必然漂移（实测：日志显示 5 条消息、实际只发 3 条，还有一条内容被改写）。
  解法：`ChatModelProvider.describeWireRequest(...)` 让 Provider 复用**同一次组装**产出请求体快照，
  编排层只负责记录 → 单一来源，后续 Provider 改动日志自动跟随。
- **`Map.of()` 不能用于「key 可能为 null」的查询**：`Map.of()` 返回的是 JDK ImmutableCollections，
  其 `get(null)` 会抛 **NPE**（`HashMap.get(null)` / `Collections.emptyMap().get(null)` 才返回 null）。
  长期记忆的 `loadAgentNames()` 因「纯用户级记忆（`agentId` 全为 null）」命中此坑 ——
  而用户级正是新增记忆的默认作用域，会导致 `GET /api/memories` 必然 500。
  已改为 `Collections.emptyMap()`，并在调用处对 null 显式短路。
- **方法引用不能绑定静态方法**：`stream().filter(this::staticMethod)` 编译报
  「在绑定查找中找到意外的静态方法」，静态方法必须写成 `ClassName::staticMethod`。
- **PowerShell 下验证 Maven 构建结果要认准 `$LASTEXITCODE`**：直接写 `mvn ... ; if ($LASTEXITCODE -eq 0)`
  存在假阳性 —— `mvn` 未被当作可执行文件执行时 `$LASTEXITCODE` 保留旧值，实际编译失败却报 OK，
  会让"编译通过"的结论失真。可靠写法：
  `& D:\Maven\apache-maven-3.9.0\bin\mvn.cmd -q -o test-compile; Write-Host "EXIT=$LASTEXITCODE"`。
- **前端日期不能直接传 `toISOString()`**：后端字段是 `LocalDateTime`，带时区偏移与毫秒的
  `...Z` 格式会反序列化失败。`MemoryView.vue` 内用 `toLocalDateTime()` 统一格式化为
  `yyyy-MM-ddTHH:mm:ss`（其他页面传 `from`/`to` 给 `Instant` 字段时可继续用 `toISOString()`，两者不可混用）。

---

## V3.1 F-43 代码执行沙箱（进行中，2026-09-23）

### 选型决策
- **采用 OpenSandbox**（阿里开源，Apache 2.0，CNCF Landscape，GitHub `alibaba/OpenSandbox`）。
  理由：Java SDK 现成（`com.alibaba.opensandbox:sandbox:1.1.0`）；**自托管**（不是第三方 SaaS，
  Server 与业务同 compose / 同内网）；**业务应用零特权**（不挂 docker.sock，docker 权限只在 Server 侧）；
  网络策略 / 资源配额 / 沙箱池 / 凭证保险箱均为现成能力；隔离可平滑升级
  （Docker → gVisor / Kata / Firecracker，**业务代码不动**）。
- **已排除的方案**：
  - 自研 `ProcessBuilder` + `docker run`：应用需挂 socket（等于把宿主 root 交给应用容器），
    且资源 / 网络 / 隔离全要自己拼装；
  - `langchain4j-community-code-execution-engine-docker`：同样需挂 socket，且
    `DockerExecutionConfig` 与 `DockerContainerManager.buildHostConfig()` **均无 pidsLimit**
    （第 2 层受控维度少一维，fork 炸弹无防护）；
  - `Piston`：需 `--privileged` 容器 + cgroup v2 + 语言运行时自装，同样是自建服务但能力弱于 OpenSandbox；
  - 本地进程级 OS 沙箱（bubblewrap / Seatbelt）：属"本机形态"，服务端不适用。
- **场景收敛**：一期只做**纯计算**（代码经文件投递，**不挂载数据目录**）。
  依据：真需求是"让模型能精确算数"——现有 `CalculatorTool` 在 Java 17 下**已失效**
  （Nashorn 在 Java 15 被移除，`getEngineByName("js")` 恒为 null，只剩"两操作数"降级分支）；
  "表格数据分析"虽是真实痛点，但 `docs/15-产品定位.md` §4.3 明确暂不纳入财务 / 票据场景。

### 架构
```
CodeInterpreterTool (com.dcdr.agent.tool.Tool)
  → OpenSandbox Java SDK (HTTP)
    → opensandbox-server (自托管中间件，唯一持有 docker 权限的组件)
      → Docker runtime(一期) / Kubernetes / Firecracker microVM(升级路径)
```

### 实施进度（2026-09-23）
- [x] 1. 修复 Maven http-blocker → 引入 SDK 依赖并验证可编译
- [x] 2. `ToolsProperties.SandboxConfig` + `application.yml`（`agent-tools.sandbox.*`，默认 `enabled=false`）
- [x] 3. `CodeInterpreterTool` + `OpenSandboxService` + `CodeGuard` + `SandboxContextHolder`
      （代码经文件投递避免转义 + NetworkPolicy DENY 禁网 + 单命令超时 + 非 root(65534) 执行
       + 输出截断 + 审计只记摘要）
- [x] 4. `AgentOrchestrator.executeTools` 接入 userId / sessionId
      （方法签名补 2 参数 + 与 `KnowledgeSearchContextHolder` 同点 set / finally clear）
- [x] 5. 前端 `AgentView.vue` 的 `suggestedTools` 补 `code_interpreter`
- [x] 6. `AgentDefinitionService` 白名单收口：`validateTools`（非 admin 写入受限工具 → 403）
      + `filterAdminOnlyTools`（克隆时静默剔除 —— 克隆产物始终是普通 Agent，
      若按传入的 isAdmin 过滤，管理员克隆含沙箱工具的模板会被 create 的 403 卡住）
- [x] 7. 端到端验收（2026-09-23 全部通过，结果见下方「已验收通过的能力」）

### 已改动文件
- 新增（Java）：`tool/sandbox/{CodeInterpreterTool,OpenSandboxService,CodeGuard,SandboxContextHolder}.java`
- 新增（部署）：`sandbox/Dockerfile`（Server 镜像，装 PyPI `opensandbox-server==1.1.0`）、
  `sandbox/sandbox.toml`（Server 运行时配置）
- 修改：`pom.xml`、`config/ToolsProperties.java`、`application.yml`、
  `AgentOrchestrator.java`、`agent/definition/AgentDefinitionService.java`、
  `frontend/src/views/AgentView.vue`、`docker-compose.yml`
- 编译状态：`mvn -o test-compile` 通过（依赖已补齐，离线编译恢复正常）
- compose 校验：`docker-compose config --quiet` 通过

### 运行方式（组件已进 compose）
- 组件 `opensandbox-server` 已加入 `docker-compose.yml`（与业务同 compose / 同内网）；
  **docker.sock 只挂给它**，业务应用零特权，通过 `OPEN_SANDBOX_DOMAIN=opensandbox-server:8080` 访问。
- 宿主端口映射 **`18080:8080`**（避开 agent 已占用的 8080）。
  → IDE 里直跑应用时需设 `OPEN_SANDBOX_DOMAIN=localhost:18080`（yml 默认是 localhost:8080）。
- 启动：`docker compose up -d opensandbox-server`
  （首次构建 Server 镜像，并会拉取 `opensandbox/execd:v1.1.0`、`opensandbox/egress:release-1.1.0` 与沙箱基础镜像）
- **必须设置 API Key**：`.env` 中提供 `SANDBOX_API_KEY=<强随机值>`；
  未设置时 compose 用占位值 `change-me-in-env`（仅本机调试可用，服务端不允许空 Key 启动）。
- 三个已处理的配置约束（写在 `sandbox/sandbox.toml`）：
  1. `[runtime] execd_image` **必填且无默认值** → `opensandbox/execd:v1.1.0`
  2. `[docker] network_mode` 默认 `host`，但 `networkPolicy` 与 egress sidecar **要求 bridge** → 已设 `bridge`
  3. `[egress] image` 在客户端携带 `networkPolicy` 时**必需** → `opensandbox/egress:release-1.1.0`
     （CodeInterpreterTool 正是用 DENY 策略，故必须配）

### 联调踩坑记录（2026-09-23，全部来自真实报错）
1. **Docker 20.10.8 旧 seccomp profile 拦 glibc `clone3`**（本项目 postgres 早就踩过同一个坑）：
   - 构建阶段：pip 的 rich 进度条依赖线程 → `can't start new thread`；解法：Dockerfile 设
     `PIP_PROGRESS_BAR=off` / `--progress-bar off`。
   - 运行阶段：Python 服务端必须有线程 → 容器加 `security_opt: seccomp=unconfined`（已加）。
   - **升级 Docker 到新版本后可移除该 workaround**。
2. **PyPI 的 `opensandbox-server` 包不完整**：wheel 与 sdist **都缺**
   `opensandbox_server/services/fast_sandbox/generated`（3 个 protobuf/gRPC 生成文件，共 51KB），
   启动即 `ModuleNotFoundError`；官方镜像走整仓源码安装所以没事。解法：装完包后用 CDN 补齐该目录。
3. **github.com 与 raw.githubusercontent.com 在本网络不可达**（宿主/容器实测 443 超时）：
   改用 `cdn.jsdelivr.net/gh/<owner>/<repo>@<ref>/<path>`（实测 200）。
4. **SDK 必须开 `useServerProxy`**：客户端（宿主应用 / compose 容器）与沙箱不在同一网络，
   直连沙箱 `172.x` 内网 IP 会导致健康检查 **300s 超时** → `SandboxReadyTimeoutException`。
5. **`[docker] network_mode` 必须 `bridge`，且 server 容器也要 `network_mode: "bridge"`**：
   - `networkPolicy`（禁网）仅在 `network_mode='bridge'` 下被支持，自定义网络直接
     400 `SANDBOX::INVALID_PARAMETER`；
   - server 与沙箱必须同处 docker0，否则 server 代理转发 execd 端口报
     502 `BACKEND_CONNECTION_FAILED`。
6. **server 用 `network_mode: "bridge"` 后脱离 compose 网络** → agent 改用
   `host.docker.internal:18080` 访问（bridge 模式下端口映射仍生效）。
7. **`docker compose down <service>` 在 compose v2 下会停掉全部服务**（实测 pg/redis/zipkin 被一并停止）：
   只想重启单个服务请用 `docker compose up -d --force-recreate <service>`。
8. **禁网（networkPolicy）排查进展：egress 已能运行、nftables 规则已应用，但沙箱流量仍未受控**。
   以下均为实测结论：
   - **缺 `[docker] host_ip`**：server 跑在容器内且 `server.host=0.0.0.0` 时，
     探测 egress 用的是 `127.0.0.1`（源码 `_resolve_proxy_host()`），而容器内的 127.0.0.1
     是它自己 → `Connection refused` → 沙箱创建 500。**已修**：`host_ip = "172.17.0.1"`。
   - **默认 `[egress] mode = "dns"` 只做 DNS 代理，不拦 IP 直连**（实测沙箱内
     `socket.create_connection(('8.8.8.8', 53))` 可通）。**已改为 `dns+nft`**。
   - **Windows 保留端口段**导致 Docker 绑定失败（Winsock 10013
     "An attempt was made to access a socket in a way forbidden by its access permissions"）：
     沙箱随机端口 `59587` 落在保留段 `59583-59682` 内 → egress 启动 500。
     **已修**：端口范围收缩到 `40000-49800`（本机保留段见命令
     `netsh int ipv4 show excludedportrange protocol=tcp`；该段会随 Hyper-V/WSL 重启变化）。
   - **仍存在的卡点**：改用 `dns+nft` 后 egress 日志确认
     `nftables static policy applied (table inet opensandbox)`，
     **但沙箱内网络依然可通** → 判断为 egress 的 nft 规则未覆盖到沙箱的网络命名空间
     （egress 自身 `NetworkMode=bridge`，与沙箱疑似各自独立 netns；
     上游源码注释提到 "shared namespace"，**待确认沙箱容器的 NetworkMode 是否应为
     `container:<egress容器>`，以及由哪个配置项决定**）。
   - 结论：**禁网尚未真正生效**，当前防线为 `CodeGuard` 静态审查（可被绕过）。
     F-43 上线前此项必须闭环。

### 禁网（networkPolicy）闭环结论（2026-09-23 实测通过）
- **状态：✅ 已生效**。同一沙箱内实测：
  `1.1.1.1:80` 与 `8.8.8.8:443` 直连 → `TimeoutError`；`example.com` DNS 解析 → `gaierror`；
  同沙箱内 `print(sum(range(100)))` → `4950`（功能不受影响）。
- **缺任一项都会失败的配置（`sandbox/sandbox.toml`）**：
  1. `[docker] host_ip = "172.17.0.1"` —— 否则 server 在容器内用 `127.0.0.1` 探测 egress 的
     宿主映射端口 → `Connection refused` → 沙箱创建 500。
  2. `[egress] mode = "dns+nft"` —— 默认 `dns` 只劫持 DNS 解析，**不拦 IP 直连**。
  3. `[docker] port_range_max = 49800` —— 避开 Windows 保留端口段，否则 Docker 绑定失败
     （Winsock 10013）。核查：`netsh int ipv4 show excludedportrange protocol=tcp`
     （**该段随 Hyper-V/WSL 重启会变化，换机器/重启后需重新核对**）。
  4. `[docker] network_mode = "bridge"` —— `networkPolicy` 仅在 Docker 默认 bridge 下被支持
     （自定义网络直接 400）。
- **工作原理（排查参考）**：沙箱容器以 `network_mode=container:<egress容器>` 与 egress
  **共享网络命名空间**；egress 在其中装 `table inet opensandbox`（`allow_v4` / `deny_v4` /
  `dyn_allow_v4` 动态集合）与 `table ip nat` 的 OUTPUT 链（把 53 端口重定向到本地 DNS 代理）。
- **⚠️ 验收陷阱（曾因此误判为"禁网无效"）**：用
  `socket.create_connection(('8.8.8.8', 53))` 测网络是**错的** —— 53 端口被 nft 规则
  `redirect to :15353` 劫持到 egress 的本地 DNS 代理，TCP 握手因此"成功"，看起来像网络可达。
  **必须用非 53 端口（`:80` / `:443`）或 DNS 解析结果来验证。**

### 已验收通过的能力（2026-09-23 实机验证）
- 沙箱创建 → 代码投递 → 执行 → 取输出 → 销毁 全链路正常，**单次约 2~4 秒**（镜像已缓存）。
- `print(sum(range(100)))` → `4950`（真执行，非模型口算）。
- `print((1+2)*3, round(1234*5678/7, 2))` → `9 1000950.29`
  （注：我注释里心算写成 `1001312.86`，**实际值不同** —— 正好印证 F-43 要解决的"模型口算不可靠"问题）。
- `CodeGuard` 静态审查 4 条用例全部符合预期（`os.system` / `subprocess` / `eval` 拒绝，正常计算放行）。
- Server 鉴权正常：`/health` 无需 Key 返回 200；业务接口无 Key 401、带 Key 200。
- **禁网生效**：沙箱内 `1.1.1.1:80` / `8.8.8.8:443` 直连 → `TimeoutError`；
  `example.com` DNS 解析 → `gaierror`；同沙箱内计算不受影响。
- **执行超时生效**：`while True: pass` → `ok=false, exitCode=-1`，端到端约 12s
  （单命令超时 10s + 沙箱创建/销毁开销）。
- **并发闸生效**：`maxConcurrent=2` 下并发发起 3 次 → `ok=2, rejected=1`。
- **审计落库路径生效**：经 `CodeInterpreterTool` 执行代码后，`AuditLogRepository.save()`
  被调用（用 Mockito 伪 repository 验证；实际落表需启动应用后在 `audit_logs` 查 `sandbox_exec`）。
- 验收脚本：`target/sandbox-check/SandboxCheck.java`（临时程序，`mvn clean` 会一并删除；
  需要时按本文件描述重建即可）。

### 已识别的坑（实现时必须遵守）
- **`sandbox.close()` 只释放本地句柄，必须显式 `sandbox.kill()`** 才终止远端沙箱 →
  放 `finally` 中调用，否则沙箱容器堆积（有 `timeout` 兜底但会浪费资源）。
- **不要用 `python -c <code>` 传代码**（引号 / 换行转义地狱）：先 `sandbox.files().write(...)`
  写文件，再 `commands().run("python -I -B /tmp/code.py")`。
- `NetworkPolicy.defaultAction = DENY` 即为禁网（无需额外配置）；将来需要装包时再按 target 加 egress 白名单。
- 审计只记 `codeHash` + 前 200 字符，**不落全文代码**（防敏感内容进审计表）。
- 输出截断与 `TOOL_RESULT_MAX_LEN=6000` 同口径，保证模型上下文 / 落库 / 前端三处一致。

### 环境阻塞与处置（2026-09-23）
- ✅ **已解决 —— Maven 无法下载任何新依赖**：`D:\Maven\apache-maven-3.9.0\conf\settings.xml` 中
  `alimaven` 镜像原为 **http**（`http://maven.aliyun.com/nexus/content/repositories/central/`），
  被 Maven 3.9 内置的 `maven-default-http-blocker`（`mirrorOf=external:http:*`）阻断
  （实测报 `Blocked mirror for repositories: [alimaven ...]`；**这也是本项目长期只能离线编译的原因**）。
  已改为 `https://maven.aliyun.com/repository/public`，依赖下载恢复正常。
  注：原文件未单独留备份，原值即上行括号内地址，需要回退直接改回即可。
- ⏳ **未解决 —— 沙箱运行环境**：本机 docker 为 **20.10.8**，本地镜像仅有 `redis:7-alpine`；
  需 `docker pull` OpenSandbox server 镜像与沙箱基础镜像（如 `python:3.12-slim`），
  否则功能只能停在"编译通过"，无法端到端验收（含 `NetworkPolicy` 生效性、非 root 写文件权限等）。

---

## 环境事故：Docker Desktop 重置导致数据全丢（2026-09-28，已处置）

### 现象
PostgreSQL 全部业务数据（用户账号 / 会话 / 知识库 / Agent / 工作流 / 审计 / 用量）一次性消失，
`docker ps -a` 与 `docker images` 均空，但 `docker volume ls` 仍列出 `java-agent_pgdata`。

### 根因（证据链）
1. 当日 17:04–17:13 Docker Desktop 的安装/修复流程连续执行 9 次（`install-log.1~9.txt` 轮转），
   最后一次 `check-for-update` 报 `HttpRequestException`。
2. 同期 `%APPDATA%\Docker\settings.json` 被**重置为默认值**：`dataFolder` 由
   `E:\appdata\docker\DockerDesktop` 变回 `C:\ProgramData\DockerDesktop\vm-data`、
   `memoryMiB` 由 4096 变 2048、`filesharingDirectories` 变空数组，
   且后端由 Hyper-V 切到了 WSL2（`wslEngineEnabled: true`）。
3. `E:\appdata\docker\DockerDesktop\DockerDesktop.vhdx`（旧数据盘）随之被删除，目录变空。
4. `docker volume ls` 里的 `java-agent_pgdata` 是 17:15 新建的**空卷**（`CreatedAt` 为当天），
   不是原卷 —— 旧卷数据物理上在旧 vhdx 内部，随 vhdx 一起消失。
5. 恢复路径全部断掉：回收站无大文件、`E:\appdata\docker\docker-data-backup.tar` 为 0 字节、
   `E:\appdata\docker\{pgdata\pgdata,pgtest,pginit}` 均为空目录、全盘无 `*.dump` 备份。
   E 盘为 NVMe SSD，删除时 TRIM 已解映射，文件级 carve 无望。
6. 唯一残留的可读数据是 **H2 开发库**（`data/database/agent.mv.db`，9/20），但其中 18 个用户
   全是 9/14–9/15 越权测试生成的夹具账号（`ownA1734` / `usrA5565` / `scA5172`…），
   **不含真实账号** → 真实用户数据无法补救，只能重新注册（`AuthService` 首位注册用户自动为 admin）。

### 结论（关键认知）
- **命名卷的数据藏在 Docker VM 的 vhdx 里** —— vhdx 一被删，卷数据全没。
  "把 vhdx 放到 E 盘"只解决 C 盘占用，不解决安全性。
- **bind mount 的数据在宿主机文件系统上**，Docker 重装/重置/换后端都不受影响。
  本次事故中 `./data`（上传文件 / 知识库原文）与 `E:/appdata/docker/redisdata` 因是 bind mount
  而完好无损，正好是反面对照。
- **备份不能留在容器文件系统内**：`/var/backups/...` 同样活在 vhdx 里，与数据一损俱损。

### 已落地改动
| 项 | 文件 / 位置 | 内容 |
|----|------------|------|
| 备份落点 bind mount | `docker-compose.yml` | `./data/backup:/backup`（宿主机 `<项目>/data/backup`，已被 `.gitignore` 的 `data/` 覆盖） |
| PG 数据仍用命名卷（**bind mount 方案已实测排除**） | `docker-compose.yml` | 保持 `pgdata:/var/lib/postgresql/data`；数据安全改由「落宿主机备份」兜底 |
| 备份脚本落点修正 | `scripts/backup-db.sh` | `BACKUP_DIR` 默认由 `/var/backups/dcdr-agent` 改为 `/backup` |
| Windows 定时备份 | `scripts/backup-db.ps1`（新增） | 容器内 `pg_dump -f /backup/...` 直写 bind mount（规避 PS 二进制重定向损坏），含校验 + 保留策略 |
| 计划任务 | Windows 任务计划程序 | `dcdr-agent-db-backup`，每日 02:00 |
| Docker 镜像源 | `~/.docker/daemon.json` | 新增 `registry-mirrors: ["https://docker.m.daocloud.io"]`（Docker Hub 被 SNI 阻断，TLS 握手 EOF） |
| Docker Desktop 配置 | `%APPDATA%\Docker\settings.json` | 后端改回 Hyper-V、`dataFolder` 回 E 盘、`memoryMiB` 4096、补 `filesharingDirectories`、`disableUpdate: true` |
| Hyper-V 宿主默认路径 | `Set-VMHost` | VM 配置与 VHD 默认路径改到 `E:\appdata\docker\HyperV\{VM,VHD}` |

### 踩坑记录
- **PG 数据无法 bind mount 到 Windows 宿主目录**：initdb 启动即报
  `FATAL: data directory "/var/lib/postgresql/data" has invalid permissions`
  `DETAIL: Permissions should be u=rwx (0700) or u=rwx,g=rx (0750)`。
  实测容器内挂载点恒为 `777 root:root`，且 `chmod 700` 后仍为 `777` ——
  Docker Desktop 的宿主目录共享层不保留 Unix 权限位（历史遗留的
  `E:\appdata\docker\pgdata\pgdata` 嵌套空目录，正是此前尝试同一方案失败后的残留）。
  → **结论：Windows 上 PG 数据只能留在命名卷里，安全兜底靠定时 pg_dump 落宿主机目录，
  而不是试图把数据文件搬出 vhdx。** 该方案已回退，compose 中保留命名卷。
- **`$PSScriptRoot` 在 `param()` 默认值里是空串**：PS 5.1 求值参数默认值时该变量尚未赋值，
  `Join-Path (Split-Path -Parent $PSScriptRoot) 'data\backup'` 会报
  `Cannot bind argument to parameter 'Path' because it is an empty string`。
  必须改为在脚本体内解析。
- **`dataFolder` 只管 vhdx，不管 VM 本体**：Hyper-V 的 VM 配置与 4096MB 内存内容文件（`.VMRS`）
  走 Hyper-V 宿主默认路径（`C:\ProgramData\Microsoft\Windows\Hyper-V`）。C 盘仅剩 3.8GB 时 VM 启动
  报 `0x80070070 磁盘空间不足`，必须同时 `Set-VMHost -VirtualMachinePath/-VirtualHardDiskPath`。
- **`filesharingDirectories` 被重置为空会让所有 bind mount 失败**（`FileSharing.DoShareAsync` 异常），
  Hyper-V 后端必须显式共享宿主目录。
- **Docker Desktop 的 "system 代理" 读的是 WinHTTP 而非 WinINET**：本机 WinINET 配了
  `127.0.0.1:7890` 而 WinHTTP 是"直连"，Docker 因此拿不到代理；同时该代理软件当时未运行。
- **PowerShell 5.1 的 `Invoke-WebRequest` 默认走 WinINET 系统代理**，代理不可用时会**全部**报
  "无法连接到远程服务器" —— 据此判断"站点不可达"会误判。用
  `curl.exe --noproxy "*" --ssl-no-revoke` 才准（后者跳过 schannel 的 CRL 检查，
  否则会看到误导性的 `0x80092013 CRYPT_E_REVOCATION_OFFLINE`）。
- **`Get-VHD` 对正在运行的 VM 磁盘报 `AccessDenied`**：是文件被占用，不是权限不足。
- **pg16 的 `PGDATA=/var/lib/postgresql/data`**：bind mount 挂载点必须精确到该目录，
  挂到父目录会多出一层 `pgdata/`（历史遗留的 `E:\appdata\docker\pgdata\pgdata` 即此坑）；
  且 initdb 要求目标目录为空，残留空子目录也会导致初始化失败。

---

## 待优化 BUG

### [BUG] 多轮对话时 agent 重复回答历史内容
- **状态**：✅ 已修复（2026-08-07，commit `87be529`）
- **记录时间**：2026-08-06
- **现象**：同一会话内连续提问（多轮对话）时，agent 会重复回答历史中已经回答过的内容，而不是聚焦在当前这一轮的新问题上。
- **期望行为**：每次回答都应针对用户**当轮的最新提问**；历史消息仅作为背景/上下文参考，不应复述或重复先前已给出的答案。
- **排查方向**：
  - 检查 `src/main/java/com/dcdr/agent/agent/AgentOrchestrator.java` 中 history 的组装/截断逻辑，确认是否把过多历史原文带入了对话上下文。
  - 在 system prompt 或运行时约束（`injectRuntimeSystemPrompts`）中明确加入约束，例如：“只回答用户当前的问题，历史记录仅作参考，不要重复已有回答”。
  - 必要时对超长 history 做摘要/裁剪，避免模型被历史答案带偏。
- **关联记忆**：agent memory `knowledge_id=69948070`

---

## 已修复（近期）

- ✅ **调用链日志丢失「最后一轮」步骤**（2026-09-15）：`doFinally` 执行时机导致 ——
  上游 `tracedRound` / `tracedTool` 用 `doFinally` 记录步骤，晚于下游 `ChatController` 的
  `doFinally` 落库 summary，导致最后一次 LLM 调用 / 工具执行的步骤来不及进入快照
  （单轮对话时表现为「日志里只有 routing」）。改用 `doOnComplete` / `doOnError` / `doOnCancel`。
  存量记录不追溯，修复后新产生的日志才完整。
- ✅ **调用链日志与真实请求一致性改造**（2026-09-15）：排查长期记忆注入时发现日志不完整 ——
  快照层有截断（`systemPrompt` 4000 字符从尾部掐、`messages` 每条 1000 字符），且**编排层拼的 history
  本身就不等于实际发出的 messages**（Provider 会过滤/改写）。改造为：
  - 去掉快照层截断，日志不做内容截断（体积控制下沉到接口层）。
  - 新增 `ChatModelProvider.describeWireRequest(...)`：由 Provider 复用真实组装逻辑产出请求体快照，
    补齐此前日志完全缺失的**工具定义全文 / tool_choice / response_format / 厂商参数**。
  - 接口拆分：列表接口剔除 `systemPrompt` / `messages` / `wireRequest` 三个重字段；
    新增 `GET /api/llm-calls/logs/{id}` 详情接口返回全量（越权与不存在统一 404）。
  - 前端详情抽屉改为按需拉取，新增「实际发给上游的请求体」折叠区。
  - 注意：**存量日志的内容补不回来**（快照是写入时生成的），只有改造后新产生的日志才完整。
- ✅ **测试代码编译失败 —— `mvn package` / `spring-boot:run` 恢复可直接执行**（2026-09-15）：
  `LangChain4jProviderTest:182` 的 `new LangChain4jEmbeddingProvider(config)` 补齐 `LlmCircuitBreaker` 入参
  （用默认配置实例 `new LlmCircuitBreaker(new V3Properties())`，该测试不依赖 Spring 上下文，正常调用不会开断）。
  修复后 `mvn -o test-compile` 通过，`test-compile` 阶段不再中断。
- ✅ **V3.1 F-41 安全加固收口 —— 统一归属框架（Ownership）**（2026-09-14）：
  - 新增 `com.dcdr.agent.auth.ownership` 包：`OwnedEntity` / `ResourceType` / `AuthContext` / `Ownership` /
    `ResourceOwnershipProvider` / `OwnershipRegistry` / `OwnershipGuard`，共 11 个 `ResourceOwnershipProvider`。
  - **声明式接入（Spring Security 官方方案）**：Controller 方法标注
    `@PreAuthorize("@ownershipGuard.requireRead(#id, 'SESSION', principal)")` 即完成校验 ——
    当前用户由 Spring Security 以 SpEL 变量 `principal` 注入，**无需再声明 `ServerWebExchange` 参数**。
    前提：`SecurityConfig` 开启 `@EnableReactiveMethodSecurity`，且被保护方法返回 `Mono`
    （因此接入端点统一返回 `Mono<Result<T>>`，对外 JSON 契约不变；返回 `Flux` 的流式端点改用
    `OwnershipGuard.currentContext()` 手工包一层）。
  - 另提供 `currentContext()`（响应式读取 userId，供需要按用户隔离/写入归属的接口）与
    `check/canAccess`（Service 层与非 HTTP 链路显式调用）。
  - **已实测排除的方案（勿重复踩坑）**：曾自备 `ThreadLocalAccessor` +
    `Hooks.enableAutomaticContextPropagation()`，想让同步 AOP 切面从 `SecurityContextHolder` 取用户 ——
    在「同步返回 `Result<T>` 的 Controller + 同步切面」下 `SecurityContextHolder` 始终为空，
    导致用户访问自己的资源也 404（fail-closed 全量拒绝），已放弃。结论与根因写在 `OwnershipGuard` 类注释中。
  - **修复越权面**：会话 6 个裸奔端点（delete / title / persona / messages / deleteMessages / export）；
    对话内 RAG 的 `ChatRequest.knowledgeBaseIds` 越权检索（在 `ChatController` 入口静默剔除无权限 kbId）。
  - **全量迁移**：知识库 / 文档 / Agent / 工作流 / 工作流执行 / 定时任务 / Webhook / 用户模型配置 /
    反馈 / 创作项目 / 生成记录 共 11 类资源的归属判定全部收敛到 `OwnershipGuard`。
  - **口径统一**：越权与不存在一律 **404**（原先混用 403/404，403 可被用于资源枚举探测）；
    越权尝试统一写审计 `access_denied`；admin 统一放行；匿名仅在 `auth.enabled=false` 时兼容放行。
  - `ProjectEntity` 补 `userId` 归属列 + PG DDL 脚本 `docs/sql/v3.1_ownership_schema.sql`。
  - 已验证（H2 启动 + 端到端）：**会话域 9 项 + 创作项目域 8 项**横向越权矩阵全部符合预期 ——
    用户 A 访问/删除自己的资源返回 200；用户 B 跨用户读取/改名/改人设/读消息/删消息/导出/删除一律 404。
    详见 `docs/10-完整测试用例文档.md` §2.1。
  - **迁移状态（全部完成）**：12 类受保护资源的 HTTP 入口均已切换到
    `@PreAuthorize` + `OwnershipGuard.currentContext()`，Controller 方法**不再声明 `ServerWebExchange` 参数**。
    覆盖 SessionController / ProjectController / GenerationController / VideoController / ImageController /
    AgentController / WorkflowController / ScheduledTaskController / WebhookController（管理端点）/
    UserModelController / FeedbackController / KnowledgeBaseController / TemplateMarketController。
    Service 层校验保留作为纵深防御。
  - `ResourceType` 新增 `TEMPLATE_SHARE`（共 12 类），配套 `TemplateShareOwnershipProvider`。
  - **写入侧自动化（V3.1 增强）**：`CurrentUserHolder`（线程局部当前用户）+ `OwnershipFillListener`
    （JPA `@PrePersist` 回调，11 个归属实体通过 `@EntityListeners` 注册）+ `OwnedEntity` 契约。
    insert 前若实体 `userId` 为空则自动填充，**消除"新增写入点漏设归属"导致的无主数据**；
    显式设置过的一律不覆盖。
    - 上下文贯通方式：`SecurityContextWebFilter` 采用「每个请求先清空 → 认证后写入 → 链尾再清空」
      三段式防残留。实测确认 `reactor-http-nio-*` 线程上 JPA 回调可读到当前用户，且**换用户/换线程不串号**。
    - 失效边界：跨异步边界（`boundedElastic`、定时任务、工作流内部）读不到，此时**不填充**（fail-closed），
      需继续显式传 userId —— 现有 Service 签名均保留 userId 参数正是为此。
  - **实测发现一个 V3.0 遗留缺陷（非本次引入）**：市场分享只写 `template_share` 记录，
    未把被分享资源置为 `isPublic`，导致他人**无法克隆**已分享的模板（迁移前 403，现统一 404）。
    已记入 `docs/10-完整测试用例文档.md` §2.1 的 TC-OWN-29，建议后续修复。

- ✅ 刷新页面后 token 使用量消失：`SessionManager.getMessages` 主路径补充 `setTokenUsage(msg.getTokenUsage())`。
- ✅ `application.yml` 的 chat 场景 prompt 含未填充的 `{user_question}` / `{search_context}` 占位符：移除占位符，system 消息保持静态，用户问题与搜索结果由 history（user 消息 + tool 结果）自然提供。
- ✅ agentic 多轮工具调用循环失效：修正每轮 tool_calls 快照与 history 回填逻辑。
- ✅ 工具轮噪声（空气泡）显示在会话记录中：后端过滤 tool 消息、前端聚合为可折叠的「🔧 已调用 N 个工具」。
- ✅ **V3.1 F-44 治理完善（版本管理 + 成本分析，F-34 收尾项）**（2026-09-28）：
  - **落地前先纠正两处「规划与代码不符」**（否则会做出重复能力）：
    ① 规划里的 `cost_records` **不存在** —— F-39 落地用的就是 `llm_usage_logs`，故不新建成本表；
    ② 规划里的 `workflow_definition_versions` **已存在**（V3.0 建的表叫 `workflow_versions`，已含
    `WorkflowEngine.snapshotVersion/listVersions/rollback` 与前端版本弹窗）→ 工作流版本**复用现有表**，
    只新建 Agent 侧同构表。
  - **F-44.1 版本管理**：
    - 新增 `agent_definition_versions`（全量快照，`UNIQUE(agent_id, version)`）+ `AgentDefinitionService`
      的 `snapshotVersion / rollback / listVersions / cloneFromVersion`：创建→v1、更新→max+1、
      回滚→回写快照并**再生成一版**（历史不被覆盖、可继续回滚）；快照失败仅 warn 不阻断主流程（与工作流侧同策略）。
    - **结构化 diff**（新增 `agent/version/{DiffSupport,AgentVersionDiffer,WorkflowVersionDiffer}`）：
      不引 java-diff-utils —— 行级 diff 无法表达"哪个字段/哪个节点改了"。Agent 做字段级；
      工作流把 `dag_json` 与 `steps_json` **统一成"节点/边"两个维度**比较（节点按 `id`/步骤名对齐，
      边按 `source->target(label)`），输出用 TreeMap/TreeSet 保证顺序稳定；工具/知识库列表先排序再比，忽略顺序差异。
    - 端点：`GET /api/agents/{id}/versions`、`POST .../versions/{v}/rollback`、`GET .../versions/{a}/diff/{b}`；
      工作流补 `GET .../versions/{a}/diff/{b}`。全部复用既有 `@PreAuthorize` + `OwnershipGuard`，
      **未新增 `ResourceType`** —— 版本是定义的子资源，归属由父资源判定。
    - 模板市场「发布即钉版本」：`template_shares.source_version` + `ShareRequest.version`（缺省取最新版本，
      显式传入则校验存在、否则 404）；克隆**按该版本快照还原**（`cloneFromVersion`）；
      预置模板与存量分享 `source_version` 为 null 时保持 V3.0 行为（按当前定义克隆）。
  - **F-44.2 成本分析**：
    - 新增 `V3Properties.Cost.pricing`（元/千 token，精确匹配优先、支持前缀匹配）→ `CostTracker.record`
      折算并落 `cost_amount`。**未配置定价的模型落 null 而不是 0**，报表用 `costDataAvailable` 标记让前端金额列
      降级为 `—`（避免把"没定价"显示成"零成本"）。默认 `pricing: {}` 为空 —— 单价是事实数据，
      预设一组编造值会让错误金额静默入库且事后无法与真实计价区分。
    - 新增 `CostReportService`：按 用户/模型/时间 聚合。**周/月趋势在 Java 侧 rollup**（数据库只查天粒度）——
      JPQL 无 `date_trunc`，nativeQuery 会绑死 PostgreSQL 并在 H2（开发/测试）下直接报错（项目已有同类踩坑）。
      新增 5 个区间聚合 `@Query`（统一左闭右开 `[from, to)`）。
    - 端点：`GET /api/monitor/cost/report`（`granularity=day|week|month`、`topN`、`scope`）与
      `GET /api/monitor/cost/export?dimension=trend|model|user|source`（CSV）。
      **用户维度仅在管理员 `scope=global` 下返回**（否则即跨用户信息泄露）；单次查询区间上限 366 天。
    - 新增 `common/CsvWriter`：**UTF-8 BOM**（否则 Windows Excel 打开中文乱码）+ **公式注入转义**
      （`= + - @` 开头加 `'`，用户名/模型名属用户数据）+ RFC4180 转义。不引 Commons CSV（轮子太小，
      且 BOM/注入防护是本项目特有需求）。
  - **前端**：`AgentView.vue` 加「版本历史」弹窗（版本表 + 勾选两版对比 + 回滚；diff 只展示变化字段、左右并排高亮）
    + 编辑框加「变更说明」；`MonitorView.vue` 顶部改为页签（执行监控 / **成本分析** / 回答反馈），
    成本页含 粒度切换、日期区间、趋势条、模型占比、Top 用户、逐卡导出 CSV（不引 echarts，沿用现有手写 CSS 图）。
  - **脚本/配置**：`docs/sql/v3.1_f44_governance_schema.sql`（幂等）+ `application.yml` 的 `agent.v3.cost.pricing`。
  - **端到端实测**（在 18081 端口另起实例验证，未动 8080 上正在运行的应用）：
    创建→v1、更新→v2、版本列表 2 版；diff 精确列出「描述/角色设定/工具/温度」
    （`工具: [calculator] -> [calculator, time]`、`温度: [0.30] -> [0.80]`）；回滚 v1 后定义还原且历史变 3 版
    （v3 说明"回滚到 v1"）；删除 Agent 后版本快照按 FK 级联清空。
    成本报表插入 3 条临时用量（1/2/8 天前，qwen-max/qwen-plus，chat/workflow/routing）实测：
    周粒度 `2026-W38=600token` / `2026-W39=4500token`（ISO 周 rollup 正确）、月粒度 `2026-09=5100token`、
    总额 0.0696 元、Top 用户回填出 `admin`；**模型占比印证了"成本占比 ≠ token 占比"**
    （qwen-plus 占 58.8% token 但仅 5.2% 金额）；非全局视图 `byUser=null`（权限口径正确）；
    CSV 前 3 字节 `EF BB BF`、`Content-Disposition` 正常、金额列有值。验证后临时数据与测试 Agent 已清理。
  - ⚠️ **需重启应用**加载新代码（`ddl-auto=update` 启动时会补表，但索引/外键以
    `v3.1_f44_governance_schema.sql` 为准）。
  - ⚠️ 已知限制（沿用工作流侧现状，未引入重试框架）：版本号 `max+1` 存在并发竞态，
    冲突时靠唯一约束兜底、该次快照被静默丢弃（有 warn 日志）。
- ✅ **V3.1 F-47 工具结果体积治理**（2026-09-28）：
  - **落地前纠正原文三处不符**：① `MESSAGE_SNAPSHOT_LIMIT=1000` 在代码中**不存在**
    （`snapshotMessages` 明确"不做内容截断"）；② 「模型/落库/前端三处同口径」**不成立** ——
    实际只有"回灌模型"与"`role=tool` 消息落库"两处截断，**前端下发 `ChatChunk.result` 与
    assistant 正文里拼接的工具结果都没截**（缺口比原文描述更严重）；③ `TokenEstimator` 还承担
    模型未返回 usage 时的兜底估算，不只是上限提示。另：原有工具配置前缀是 `agent-tools.*`，
    本项按"回灌治理属编排层行为"落在 **`agent.tools.result.*`**。
  - **核心：单点收口**。新增 `tool/result/ToolResultCompressor`，在**工具结果产出处**压缩一次，
    "回灌模型 / 下发前端 / 落库"三处共用同一份文本 —— 避免 V3.0 那种"三处各截、必然漏一处"的漂移。
  - **三级降级**：① 超 `spill.threshold`(32KB) 全文落盘，回灌"路径 + 规模 + 前 N 行"，
    模型用新工具 `tool_result_read` 分页取回（**信息不丢**，这是硬截断做不到的）；
    ② 超 `summary.threshold`(20K) 走 LLM 摘要（失败一律回退硬截断），摘要调用本身计入
    `llm_usage_logs`（source=`tool_summary`）—— 否则 F-39/F-44 的成本报表会漏掉这笔费用；
    ③ 超 `max-length`(6000，与 V3.0 同值) 或 token 预算 → **头 70% + 标记 + 尾 30%**（修 F-47.3 只保头部）。
  - **安全**：落盘读取只允许落盘根目录下**当前会话**目录内的文件（`ToolResultSpillStore.resolveSafe`）：
    既防 `../` 路径穿越，也防跨会话读取他人工具结果；会话目录名做非字母数字替换防路径注入。
  - **配套**：`InMemoryChatMemory` 的 `sources` 落库前截断（F-47.6）；`AgentOrchestrator` 删除本地
    `TOOL_RESULT_MAX_LEN`/`truncate`（消除第二种口径）；配置块见 `application.yml` 的 `agent.tools.result.*`。
  - **实测**：新增 `ToolResultGovernanceTest` 11 条用例全绿（压缩口径 / token 预算生效 / 落盘内容完整 /
    窗口预算 / 路径穿越 / 会话隔离 / 分页续读 / 工具契约）。
  - **过程中修掉自身两个缺陷**：① token 预算原用固定系数 0.6 收缩，预算远小于上限时 4 轮收敛不到
    （等于预算失效），改为按「预算 / 实际估算」比例收缩；② 落盘文件名仅毫秒时间戳，同毫秒两次落盘会
    互相覆盖，加序号兜住。
  - **与规划的三处偏差（显式记录）**：① "超总量预算对最旧结果做摘要"改为"触顶后本轮收紧到下限"
    （不回溯改写历史，避免额外模型调用）；② F-47.8 只做日志 + 回灌文本内的截断标记，**未写审计**
    （每次工具调用都写审计会把审计表变成噪声源，与 F-41 口径冲突）；③ 未加摘要超时开关，
    依赖 Provider 的 HTTP 客户端超时。
- ✅ **调用链日志补记「本轮模型输出」**（2026-09-28）：
  - **背景（缺口的真实影响）**：`llm_call_logs` 只有 token/耗时/链路，**没有任何模型输出** ——
    复盘时看到 `LLM=2次 工具=1步` 却不知道这轮答了什么；更关键的是 `ON_CANCEL` / `ON_ERROR` 下
    答案可能**根本没落进 `messages`**（流中断/取消/写库失败），此时链路日志是唯一现场却恰好缺了
    "模型吐到哪一步"，连"中断发生在首 token 之前还是之后"都判断不了。
    澄清一个易误判点：中间轮的答案其实**已间接存在**（下一轮请求的 `messages` 快照里含上一轮 assistant 输出），
    真正缺的是**最后一轮的输出**（它不在任何后续请求中）。
  - **方案：不新增表列，写进既有 `steps_json`**。`LlmCallTrace.recordLlm` 的 `params` 会 `putAll` 进步骤 Map，
    故新增静态工具 `LlmCallTrace.putOutput(params, output)` 统一产出两个字段：
    `outputPreview`（截断 500 字符，与工具参数/结果摘要同口径）+ `outputChars`（原始字符数，用于判断是否被截断）。
    优势：零 DDL 变更、复用既有"列表剔重字段"机制；不足：完整原文只在详情接口可见（这是刻意口径）。
  - **三个记录点全覆盖**：① 流式每一轮（输出在 `tracedRound` 内**就地累积**，卷在模型流上而非下游消费点，
    故 `doOnComplete` / `doOnError` / `doOnCancel` 三条终止路径都能拿到**已产出的部分**——这是本改造最大价值）；
    ② `chatSync` 单轮同步路径（`response.getContent()` 即最终答案）；③ 路由判断（决策 JSON，
    可直接验证"为什么路由到此目标"）。
  - **接口口径（含一次上线后修正）**：`outputPreview` / `outputChars` **列表与详情都返回**。
    最初按"列表只给元数据"的口径把 `outputPreview` 从列表剔除，实测**页面根本看不到输出**
    （正文只在详情抽屉里，要点两层才见；而"看这一轮答了什么"本就是本页面的主要用途）→ 修正为列表也返回：
    预览已截断 500 字符，与 `systemPrompt` / `messages` / `wireRequest` 三个"完整原文"字段
    （单条可达数十 KB）不是一个量级，一并剔除属口径过度。
  - **明确定位：不做全文留档**。完整答案的权威来源仍是 `messages.content` —— 再存一份即双写，
    且流式拼接/后处理/重试下有"存的和用户看到的不一致"风险；同时"答案"定义本身也不清晰
    （是否含 `reasoningContent`、工具轮中间文本等）。故只做**可对账的摘要**，不做内容留档。
  - **前端**（`LlmCallLogView.vue`）：列表卡片**默认显示**「模型输出」行（取最后一轮 LLM 的本轮输出，
    最多 4 行省略）；展开「步骤明细」可逐轮对照（每轮最多 3 行）；详情抽屉新增「本轮模型输出」块，
    超 500 字时提示"完整正文见该会话消息"。
  - 注意：**存量日志的输出补不回来**（快照写入时生成），只有改造后新产生的日志才有该字段。
- ⚠️ **构建环境坑：命令行 `mvn` 默认跑在 JDK 8 上**（本机 `JAVA_HOME=D:\jdk1.8.0_202`），
  而项目要求 Java 17 —— 直接 `mvn compile` 会在 `switch` 箭头语法处（如 `CostReportService`）
  报一大串 **"需要: / 非法的表达式开始 / 需要 class, interface 或 enum" 的级联假错误**，
  极易误判成"代码被改坏了"。正确姿势：`$env:JAVA_HOME='D:\app\jdk17'; mvn -DskipTests compile`
  （IDEA 里用配置好的 JDK 不受影响，所以长期没暴露）。另：`mvn -o` 离线编译会因缺
  `xml-apis:1.4.01` 失败，需联网。
