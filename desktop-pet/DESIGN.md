# 桌面宠物开发设计文档

| 项 | 值 |
|---|---|
| 版本 | v0.11（姿态库扩充至 12 套；框架、边界、形象沿用 v0.3/v0.4） |
| 日期 | 2026-11-12 |
| 状态 | 框架与边界已定稿；实现已落盘并通过单测与预览验证；62 个动作、10 个特效、9 个道具贴纸、6 种表情、12 个整身姿态（**待机自动巡演**）；Rust 壳待有工具链环境编译 |
| 落点 | `E:/program/back-end/dcdr-agent/desktop-pet/`（独立子项目，不改动现有 dcdr-agent 任何文件） |
| 代号 | `stepboy`（皮肤包 id，正式名称待用户命名） |
| 对标 | `dsh-pet`（大肥鱼桌宠），借鉴交互与状态联动设计，不沿用其素材 |
| 形象来源 | 用户提供的 AI 生成插画 → 二头身 Q 版重绘，已抠图去水印；详见 §6.0，仅本地个人使用 |
| v0.4 修订 | 修正 §6.3 示例、§6.4 动作部件、§7.1 alpha 合成公式与 pivot 语义；新增 §6.7 落盘清单、§6.8 皮肤加载与热重载 |
| v0.5 修订 | 动作库 13 → 30，特效部件 4 → 8，新增动作级气泡台词 `line`，待机随机间隔缩短至 1.2–3.8 s |
| v0.6 修订 | 新增 6 个表情覆盖层与 `follow` 继承契约，眨眼已实现；动作 30 → 31 |
| v0.7 修订 | 状态拆为专属动作，新增 10 个思考与工作类动作 + 灯泡/齿轮特效；动作 31 → 41 |
| v0.8 修订 | 新增 **9 个道具贴纸**（三态电脑 / 书 / 便签 / 咖啡 / 放大镜 / 清单 / 耳机）与 `group` 互斥组契约、手动换贴纸与试动作面板；新增 9 个道具动作、12 个已有动作挂道具；动作 41 → **50**；架构与 Scope 不变 |
| v0.9 修订 | 新增 12 个整身姿态替身部件（`group = 'pose'` 互斥、`follow = body`）+ 6 个配套动作，以 body↔替身交叉淡化；动作 50 → 56、部件 26 → 32 |
| v0.10 修订 | 姿态动作接入自动巡演，新增 `pools.pose` 与间隔配置；用户坐姿图替换 `pose_sit`；`classic` 无替身时跳过巡演 |
| v0.11 修订 | 按用户「多补充一些」**新增 6 套整身姿态立绘**（用电脑写代码 / 托腮思考 / 捧书看 / 捧杯喝 / 叉腰赌气 / 趴桌睡）与 6 个配套动作；姿态 6 → **12**、动作 56 → **62**、部件 32 → **38**、`pools.pose` 12 项、`manifest.version` 8；新增 6 张 WebP 压到 quality 82 让运行时资源回到预算内 |

---

## 0. 一页速览

| 维度 | 定稿 |
|---|---|
| 形态 | 独立 Windows 桌面程序：透明、无边框、置顶、可拖拽、系统托盘 |
| 框架 | **Tauri 2（Rust 壳）+ 原生 ESM JavaScript 前端，零构建、零运行时依赖** |
| 渲染 | Canvas 2D 分层部件渲染器，接口隔离，后续可换 Pixi.js / Live2D；当前 38 个部件（1 主体 + 6 整身姿态 + 10 特效 + 9 道具 + 6 表情） |
| 行为 | 纯 JS 状态机 + 四层动画叠加 + 弹簧/重力物理 + 心情数值 |
| 内容 | 外置皮肤包（`manifest.json` + `skin.json` + **50 个** `motions/*.json` + WebP/PNG 透明部件 + **10 个**特效 + **9 个**道具贴纸 + **6 个**表情覆盖层），改文件即热更新，不改代码 |
| 联动 | 打字节奏、全局空闲、指针靠近、可选构建状态 / 余额数值；**不接大模型对话、不联网、不自动发帖** |
| 形象 | 二头身 Q 版眼镜少年：大头圆脸、黑发圆眼镜、白 T 恤、浅蓝牛仔裤、帆布鞋；正面待机站姿、双脚完整；263×520 透明素材，另有立绘备用皮肤 |
| 性能目标 | exe ≤ 15 MB，常驻内存 ≤ 150 MB（需实测校准），静止 CPU < 1%，静止 30fps / 动作 60fps / 隐藏 0fps |

**核心判断**：用户要的「灵活 / 轻量 / 动态效果好」三者互相制约（Live2D 效果好但重，序列帧轻但不灵活）。解法不是挑一个最强引擎，而是把 **资源、行为、渲染、平台壳四层彻底解耦**——换形象只换资源包，换引擎只换适配器，行为逻辑对二者完全无感。

---

## 1. 需求与对标

### 1.1 三个词的真实含义

- **灵活**：新增角色 / 动作 / 表情不需要改代码、不需要重新编译；行为参数可通过 JSON 调整。
- **轻量**：包体小、常驻内存低、静止时几乎不占 CPU，不引入 Electron / Node 运行时 / Python 解释器。
- **动态效果好**：不是动作数量多，而是动作**可叠加、可打断、有物理惯性、有随机微动作**，避免整段动画硬切带来的僵硬感。

### 1.2 对标 dsh-pet：借鉴什么、不借鉴什么

| dsh-pet 的做法 | 评价 | 本项目 |
|---|---|---|
| 六档 Agent 状态 → 动画 + 气泡（思考/工作/整理/等待/成功/出错） | 最有价值的设计，把不可见的运行状态翻译成宠物语言 | ✅ 已实现专属动作 + `coding` 写代码态与三态电脑贴纸，另增 searching / debugging 扩展态，见 §8.2 |
| 余额每降 20% 换一套表情（6 档） | 数值驱动表情分档，不新增状态机复杂度 | ✅ 借鉴为「数值分档」通用机制 |
| 睡觉时完全静默，只冒梦话 | 静默优先级高于一切随机动作 | ✅ 借鉴 |
| 拖拽挣扎、松手重力抛物线、跨屏投掷 | 物理层是「手感」的来源 | ✅ P3 实现基础版（重力 + 回弹），跨屏不做 |
| `manifest.json + videos/` 角色包，改完热更新 | 内容/代码分离 | ✅ 借鉴，改为部件 + JSON 关键帧（比整段视频更灵活、体积更小） |
| 100+ 段预渲染透明动画素材 | 效果好但资源重、不可组合 | ❌ 改为 4 层叠加，少量动作组合出大量表现 |
| 依赖 DSH / Node / Electron，或 Python + PySide6 | 与「轻量」冲突（插件版约 530 个 .ts 文件，独立版约 700 个 .py 文件） | ❌ 不依赖任何宿主 |
| AI 对话、语音、自动发评论、读会话记录 | 引入网络、凭据与隐私面；B 站「鱼灾」争议即源于此 | ❌ 本期不做，且不预留自动发帖能力 |
| 鲸鱼娘形象 | CC BY-NC-SA 4.0，代码 MIT 但素材禁止商用、公开二创须署名 | ❌ 不沿用；本项目使用用户提供的插画形象（见 §6.0） |

### 1.3 冲突的解法

| 冲突 | 解法 |
|---|---|
| 轻量 ↔ 效果好 | 单张透明 WebP 主部件（37.5 KB）+ 10 个特效 + 9 个道具 + 6 个表情覆盖层（代码绘制 SVG，各 < 1 KB）+ 50 组关键帧代替整段视频；静止降帧、隐藏停 RAF、脏矩形重绘 |
| 灵活 ↔ 自然 | 关键帧 JSON + 四层叠加 + 缓动交叉淡化；物理层单独叠加，不写死在动作里 |
| 灵活 ↔ 轻量 | 零构建 ESM + 零第三方库，行为层纯函数，Node 内置 `--test` 即可单测 |

---

## 2. 边界（Scope）

### 2.1 本期做

1. 透明无边框置顶窗口、系统托盘、开机自启开关、不透明兜底开关。
2. 像素热区级鼠标穿透：只有角色与气泡上可点，其余点击落到桌面。
3. 分层部件渲染、呼吸 / 视线倾斜 / 随机微动作，以及 6 种可切换表情与眨眼（见 §6.0）。
4. 配置驱动的状态机：点击、拖拽、抛掷落地、睡眠、提醒。
5. 皮肤包热加载：换 `skin.json` / 增删 `motions/*.json` 即时生效（机制见 §6.8；发布版通过外部 `skins/` 目录换皮）。
6. 系统联动：打字节奏、全局空闲、指针靠近；构建状态与余额分档为可选项。
7. 纯 Web 预览模式（无 Rust 也能验证动画与交互）。

### 2.2 本期明确不做

| 不做的事 | 理由 |
|---|---|
| 大模型对话、语音合成/识别 | 引入网络、API Key、隐私面；用户已选「纯陪伴 + 系统联动」 |
| 自动发帖 / 自动评论 / 读取聊天会话记录 | 权限与合规风险，且与桌宠陪伴定位无关 |
| Live2D / Spine / 3D（VRM）模型 | 授权与体积成本；渲染接口已隔离，后续可作为适配器接入 |
| 全局键盘钩子（Hook） | 高权限、易被安全软件误报；只读 `GetLastInputInfo` 拿空闲时长即可 |
| 多宠物同窗、跨屏投掷、宠物间碰撞 | 复杂度高、收益低；窗口按单例设计 |
| 移动端 / macOS / Linux 适配 | 本期仅 Windows；平台壳代码按可移植结构组织，不承诺适配 |
| 素材商店、云同步、账号体系 | 超出边界 |

### 2.3 能力边界矩阵

| 能力 | Web 预览 | Tauri 桌面 | 备注 |
|---|---|---|---|
| 动画 / 状态机 / 物理形变 | ✅ | ✅ | 行为层完全共用 |
| 点击 / 拖拽（页面内） | ✅ | ✅ | Web 下拖拽改为移动 DOM 元素 |
| 窗口随拖拽移动 | 模拟（DOM transform） | ✅ 原生 | 由 `bridge.moveWindowBy` 抽象 |
| 透明置顶 / 光标穿透 / 托盘 / 开机自启 | ❌ | ✅ | 必须 Rust 壳 |
| 全局空闲时长 | 页面内计时（近似） | ✅ `GetLastInputInfo` | Web 下切走标签页即视为空闲 |
| 全局打字节奏 | ❌ 仅窗口聚焦时 | 可选 | 本期不装 Hook，窗口内 keydown 足够 |
| 构建状态 / 余额 | ✅ 手动注入 | ✅ 本地文件注入 | 均不联网、不读凭据 |

### 2.4 安全与合规边界

- **零网络请求**：前端不发起任何外部 fetch；皮肤资源全部本地相对路径。
- **零凭据**：不读取、不存储任何 API Key、Cookie、会话记录；余额/Token 仅为本地配置文件中的普通数字，用于驱动表情分档。
- **最小权限**：Rust 侧只用窗口管理、托盘、光标位置、系统空闲计数；不装全局钩子、不扫描文件系统（构建状态若启用，仅读用户显式指定的单个日志路径；唯一例外是 P4 的外部 `skins/` 皮肤目录，见 §6.8）。
- **素材授权**：形象来自用户提供的 AI 生成插画，并以其为参考二次生成 Q 版形象，两代素材均已去除水印 / 标记，仅用于本地个人桌宠。不引入 DeepSeek Logo、鲸鱼娘、溟月或任何 CC BY-NC-SA 4.0 素材。若日后公开发布或商用，需按各生成平台条款分别核对权利；代码中不硬编码任何第三方形象。
- **心跳 Fail-Safe**：渲染层崩溃或心跳超时必须自动恢复鼠标穿透，绝不锁死桌面（见 §10.3）。

---

## 3. 技术选型（定稿）

### 3.1 候选对比

| 方案 | 包体 | 常驻内存 | 动画上限 | 灵活度 | 结论 |
|---|---|---|---|---|---|
| **Tauri 2 + WebView2** | 3–15 MB | 约 80–230 MB（WebView2 基线） | 高（Canvas / Pixi / Live2D 均可） | 高，资源热替换 | ✅ **定稿** |
| Electron | 80–180 MB | 200 MB+ | 高 | 高 | ❌ 与「轻量」冲突 |
| Python + PySide6（dsh-pet 独立版路线） | 40–80 MB | 100 MB+ | 中，动画生态弱 | 中 | ❌ 解释器 + 素材链过重 |
| 原生 Win32 + Direct2D 分层窗口 | < 2 MB | < 20 MB | 全靠自己写 | 低 | ⚠️ 最轻但开发成本高、热更新难 |
| 纯 Web 页面 | 0 | — | 好 | 高 | ⚠️ 无法透明置顶，仅作预览模式 |

社区实证：同类项目从 Electron 180 MB 换到 Tauri 2 后 release exe 3.3 MB；BongoCat、VPet 等轻量桌宠亦走 Tauri / Web 渲染路线。

### 3.2 定稿技术栈

| 层 | 技术 | 版本/约束 |
|---|---|---|
| 平台壳 | Rust + Tauri 2 | `tauri = "2"`，仅窗口/托盘/IPC；Windows 下用 `windows-sys` 取光标与空闲 |
| 前端语言 | 原生 ESM JavaScript（ES2022）+ JSDoc 类型标注 | **不引入 TypeScript 编译步骤**，`jsconfig.json` 开启 `checkJs` 提供编辑器类型检查 |
| 前端构建 | **无构建** | 浏览器直接加载 `web/src/*.js`；Tauri `frontendDist = "../web"` |
| 渲染 | Canvas 2D（可替换适配器） | 无第三方库；预留 `Renderer` 接口 |
| 开发预览 | `dev-server.mjs`（Node 原生 `http` + `fs`） | 零依赖，`node dev-server.mjs` 起在 5180 |
| 单测 | Node 内置 `node --test` | 零依赖，只测行为层纯函数 |
| 素材 | WebP（带 alpha，主）/ PNG（2x 母版与兼容备份，不参与运行）；特效 / 道具 / 表情覆盖层用 SVG | 运行时资源 ≤ 500 KB（当前 WebP+SVG 实测约 470 KB；PNG 备份约 2.3 MB，发布时可剔除） |
| 配置 | JSON（`manifest.json` / `skin.json` / `motions/*.json`） | 全部外置，可热重载 |

### 3.3 为什么坚持「零构建」

1. 本机实测 npm registry 不可达（见 §4），任何 `npm install` 都会成为阻塞；零依赖方案保证**现在就能跑起来并验证效果**。
2. 桌宠前端体量很小，Vite/TS 带来的收益（打包、HMR、类型）小于其引入的工具链成本。
3. 皮肤包本来就是运行时加载的 JSON + 图片，配置驱动的正确落点是「没有编译期」。
4. 代价：失去静态类型强校验，用 JSDoc + `checkJs` 补偿；行为层保持纯函数便于单测。

### 3.4 被否决的具体项

- **TypeScript + Vite**：需联网装依赖；改为 JS + JSDoc，接口用 `typedef` 描述。
- **Pixi.js / Live2D 作为 P0 渲染器**：绑定 WebGL 与 Cubism Core（需单独下载、非开源分发）；先用 Canvas 2D 打底，接口稳定后按需替换。
- **`@tauri-apps/api` npm 包**：改用 `window.__TAURI_INTERNALS__.invoke()` 直接调用 IPC，前端因此完全不需要 Tauri 前端依赖。
- **整段透明视频/序列帧**：体积大、动作不可组合；改为部件 + 关键帧。

---

## 4. 运行环境与约束（本机实测，2026-11-12）

| 项 | 实测结果 | 影响 |
|---|---|---|
| OS | Windows（win32），Git Bash | 目标平台 Windows |
| Node | v22.22.2，npm 10.9.7 | 可跑零依赖预览服务器与 `node --test` |
| pnpm | 未安装 | 不使用 pnpm |
| rustc / cargo | **未安装** | Rust 壳源码可交付，但本机无法 `cargo check` |
| npm registry | 探测 `npm view vite version` 30 s 超时无响应，视为不可达 | 不能依赖任何 npm install；Tauri CLI 需在有网环境预置 |
| WebView2 | 未单独验证 | Win10/11 一般自带；需在首次运行时检测并提供不透明兜底 |

**三种运行模式**

| 模式 | 入口 | 前置条件 | 可验证范围 |
|---|---|---|---|
| Web 预览 | `node dev-server.mjs` → `http://127.0.0.1:5180` | 仅 Node | 动画、状态机、物理形变、皮肤热重载、页面内交互 |
| Tauri 开发 | `npm run tauri:dev`（内部 `npx --yes @tauri-apps/cli@2 dev`） | Rust 工具链 + Tauri CLI + crates.io 可达 | 全能力 |
| Tauri 发布 | `npm run tauri:build` | 同上 | 单文件 exe |

> **当前可执行性**：§4 列出的入口均已可用（`package.json` / `dev-server.mjs` 已落盘）。访问地址统一用 `127.0.0.1`，避免 `localhost` 在部分机器上被解析到 IPv6 而命中其他服务。

> **交付边界声明**：Rust 侧代码未经本机编译验证。首次 `cargo build` 可能因 Tauri 2 小版本 API 漂移需要微调，文档已把易变点集中在 §10，改动面可控。

---

## 5. 架构

### 5.1 四层

```
┌─ 资源层（外置，可热替换）  web/assets/skins/<id>/**
│    manifest.json / skin.json / motions/*.json / parts/*.webp|*.svg
├─ 行为层（纯 JS，零依赖）   web/src/core/**
│    clock / easing / motion / compositor / state-machine / physics / signals
├─ 渲染层（适配器可换）      web/src/render/**
│    renderer.js（接口约定） / canvas-renderer.js
└─ 平台壳（Tauri 2 / Rust）  src-tauri/**
     窗口 / 托盘 / 光标穿透 / IPC 命令 / 空闲计数
```

依赖方向严格单向向下：行为层**不知道**底下是 Canvas 还是 Live2D，也**不知道**跑在浏览器还是 Tauri（通过 `bridge` 隔离）。

### 5.2 模块清单

| 路径 | 职责 | 依赖 | 可替换 |
|---|---|---|---|
| `web/index.html` / `web/src/style.css` | 透明舞台、canvas、气泡 | — | — |
| `web/src/main.js` | 装配各层，主循环编排 | 全部 | ❌ |
| `web/src/core/clock.js` | RAF、deltaTime、动态帧率、隐藏暂停 | — | ❌ |
| `web/src/core/easing.js` | 缓动表、关键帧采样 `sampleTrack`、恒等变换 | — | ❌ |
| `web/src/core/motion.js` | `MotionBank`：加载/归一化/求值动作 JSON | easing | ❌ |
| `web/src/core/compositor.js` | 四层叠加、交叉淡化、输出 `Map<partId, Transform>` | motion | ❌ |
| `web/src/core/state-machine.js` | 状态表、优先级、打断规则、转换 | — | ❌ |
| `web/src/core/physics.js` | 弹簧惯性、重力、落地回弹；输出形变 + 窗口位移 | — | ❌ |
| `web/src/core/signals.js` | 信号采集、心情数值、随机池权重 | bridge | ❌ |
| `web/src/render/renderer.js` | `Renderer` / `Transform` 的 JSDoc 接口约定 | — | — |
| `web/src/render/canvas-renderer.js` | 图片加载、z 序、变换绘制、命中检测 | renderer | ✅ |
| `web/src/platform/bridge.js` | Tauri IPC 封装 + Web 兜底 | — | ✅ |
| `dev-server.mjs` | 零依赖静态服务器 | Node | ✅ |
| `src-tauri/src/lib.rs` / `main.rs` | 窗口、托盘、命令、穿透轮询 | Tauri | ✅ |

### 5.3 每帧数据流

1. `Clock.tick(dt)` → 按帧率门控后触发。
2. `Signals.collect(dt)` → 产出事件（`typing` / `idle` / `pointer_near` / `alert`）与心情值。
3. `StateMachine.update(events, dt)` → 决定当前状态与 event 层动作 id，处理打断与退出。
4. `Physics.step(dt)` → 输出 `{ parts: {...}, window: {dx, dy} }`。
5. `Compositor.compose(dt, physics)` → 依次求值 base / micro / event / physics 四层，加权叠加成每部件 transform。
6. `Renderer.draw(transforms)` → Canvas 绘制。
7. 每 100 ms：`Bridge.reportHotspots(rects)` 上报热区与心跳。
8. 拖放期间：`Bridge.moveWindowBy(physics.window)` 移动窗口。

### 5.4 关键机制：热区穿透

Tauri 的 `set_ignore_cursor_events` 是**整窗开关**，没有 Electron 的 `forward: true`，窗口穿透后页面收不到任何鼠标事件，无法自行判断光标是否在角色身上。因此采用反向方案：

```
前端（每 100ms）  →  上报角色包围盒 / 气泡矩形 + 心跳
Rust（每 16–33ms）→  GetCursorPos → 命中热区？
                      命中 → set_ignore_cursor_events(false)
                      未命中 / 心跳超时 500ms → set_ignore_cursor_events(true)
```

心跳超时的意义：渲染层崩溃或卡死时自动恢复穿透，不会把整个桌面锁死。详见 §10.3。

---

## 6. 数据契约

> **契约与实例的区分**：§6.3 / §6.4 的 JSON 示例描述渲染器支持的**完整分层契约**（多部件、局部关键帧），这是能力上限；当前 `stepboy` 皮肤实配为 **38 个部件 = 1 个主部件 `body` + 12 个整身姿态 + 10 个特效 + 9 个道具贴纸 + 6 个表情覆盖层**，见 §6.0 / §6.3。二者共用同一套渲染器与合成器——后续只要把新部件放进 `parts/` 并在 `skin.json` 登记，局部动作立即生效，代码零改动。

### 6.0 形象定稿与素材处理（v0.3 更新）

**结论**：默认形象为**二头身 Q 版桌宠** `stepboy`，由用户提供的插画少年二次生成而来。v0.2 的 7 头身立绘抠图保留为备用皮肤 `classic`，可随时切回。

**来源与演化**：

1. 用户提供 `assets/reference/original.jpg`（蓝天草坡 + 戴圆框眼镜的少年，白 T 恤 / 浅蓝牛仔裤 / 帆布鞋，走路姿势，带相机时间戳与「豆包AI生成」水印）。
2. v0.2：抠图去水印，得到 7 头身立绘。但比例偏写实、处于走路中途姿态，放在 240×280 的小窗口里不像桌宠。
3. v0.3：以立绘抠图为参考做**二头身 Q 版重绘**，保留识别特征，改成正面静止待机站姿、双脚完整。

**Q 版形象规格**：大头圆脸、乱翘黑发、圆框细金属眼镜、淡腮红、温和微笑；白色短袖 T 恤（胸前深色小花印花，无文字）、浅蓝宽松牛仔裤、米白帆布鞋、左手腕手表；自带一圈白色贴纸式外描边，在任意壁纸上都清晰。

**处理链路**（已完成；Desktop 原文件未改动）：

1. ImageGen 以 `classic-body.png` 为参考生成 Q 版 1024×1536。注意：`background=transparent` 实际画出的是**棋盘格图案**（alpha 全 255）而非真透明，且右下角带生成标记——因此必须后处理，不能直接使用。
2. 人像抠图 → 真透明 PNG；校验 alpha `0–255`、背景 `alpha=0`，bbox `(207, 142, 850, 1435)`。
3. 裁剪留 12 px 边 → 等比缩放到 2x 显示尺寸 `263 × 520` → 导出 WebP 37.5 KB + PNG 165.8 KB。
4. 覆盖到 `parts/body.*`，把 v0.2 立绘版备份为 `assets/reference/classic-body.*`。
5. 生成棋盘格预览 `assets/reference/preview-chibi.png`，确认角色完整、双脚齐全、无棋盘格残留。
6. 用代码绘制前 8 个叠加特效部件（SVG，后续第 8/9 步又追加了齿轮、灯泡与道具）：`zzz` 睡眠、`sweat` 忙碌 / 出错、`heart` 点击 / 投喂、`note` 打字 / 开心、`star` 得意 / 成功、`question` 疑惑、`exclaim` 惊醒 / 告警、`cloud` 沮丧 / 余额见底。
7. 用代码绘制 6 个**表情覆盖层**（SVG），在镜片内盖住原眼珠并重画：`eyes_closed` / `eyes_happy` / `eyes_wide` / `eyes_angry` / `eyes_dizzy` / `eyes_sparkle`，见 §6.3。
8. 追加 `bulb` 灯泡（思考 / 灵感）与 `gear` 齿轮（工作 / 处理中）两个语义特效，配合 §8.2 的状态动作。
9. 用代码绘制 9 个**道具贴纸**（SVG），贴在角色身前：三态笔记本（`laptop_code` / `laptop_error` / `laptop_done`）、`book`、`notebook`、`coffee`、`magnifier`、`clipboard`、`headphone`，见 §6.3。

| 产物 | 路径 | 尺寸 / 体积 | 用途 |
|---|---|---|---|
| 原图备份 | `assets/reference/original.jpg` | 175 KB | 溯源 |
| 立绘抠图母版 | `assets/reference/matted-raw.png` | 1280×1699 / 2.48 MB | 备用皮肤母版 |
| 立绘备用皮肤 | `assets/reference/classic-body.*` + `web/assets/skins/classic/parts/body.*` | 325×480 / 31.5 + 110 KB | `classic` 皮肤素材（配置待 P4） |
| Q 版生成原图 | `assets/generated/chibi-raw.png` | 1024×1536 / 1.54 MB | 含棋盘格图案，仅溯源 |
| Q 版抠图母版 | `assets/generated/chibi-matted-raw.png` | 1024×1536 / 2.04 MB | 后续拆部件 / 补姿态 |
| **Q 版主部件** | **`web/assets/skins/stepboy/parts/body.webp`** | **263×520 / 37.5 KB** | 运行时默认加载 |
| Q 版兼容备份 | `.../parts/body.png` | 263×520 / 165.8 KB | 不支持 WebP 时回退 |
| 叠加特效部件 | `.../parts/{zzz,sweat,heart,note,star,question,exclaim,cloud}.svg` | 64×64 / 各 < 1 KB | 情绪与状态可视化 |
| 表情覆盖层 | `.../parts/eyes_*.svg`（6 个） | 263×520 / 各 < 1 KB | 盖住原眼珠并替换表情，与 `body` 同坐标 |
| 思考/工作特效 | `.../parts/bulb.svg` / `gear.svg` | 64×64 / 各 < 1 KB | 灯泡=灵感，齿轮=处理中 |
| 道具贴纸 | `.../parts/laptop_*.svg` / `book.svg` / `notebook.svg` / `coffee.svg` / `magnifier.svg` / `clipboard.svg` / `headphone.svg`（9 个） | 64×64 / 各 < 1 KB | 贴在身前的实体道具，同组互斥 |
| 道具构图预览 | `assets/reference/props-preview.png` | 460×680 | 3×3 网格，人工校验不挡脸 / 不裁切 |
| 姿态对照 | `assets/reference/pose-sheet.png`、`new-poses-sheet.png`、`new-poses-transparent.png` | — | 12 张整身立绘全家福、新增 6 张生成原图与透明抠图校验 |
| 用户坐姿原图 / 旧版备份 | `assets/reference/body-sit-source.png` / `pose_sit-v09.*` | 281×542 | v0.10 替换 `pose_sit` 的源图与备份 |
| 透明预览 | `assets/reference/preview-chibi.png` | 263×520 / 145 KB | 人工校验 |

**当前皮肤包设计**（P1 落地时按此生成配置即可）：

| 项 | 值 |
|---|---|
| 皮肤 id | `stepboy`（Q 版，默认）/ `classic`（立绘备用，可切换） |
| 窗口画布 | `200 × 300`，锚点 `(100, 288)` |
| `body` 部件 | `parts/body.webp`，显示 `132 × 260`，`pivot = [0.5, 1.0]`（脚底为旋转 / 缩放中心），`z = 0` |
| 命中框 | 相对锚点 `(-66, -260, 132, 260)` |
| 叠加特效 | `zzz` / `sweat` / `heart` / `note` / `star` / `question` / `exclaim` / `cloud` / `bulb` / `gear`，`z = 3~4`，默认 `alpha = 0`，由动作 `alpha` 增量唤起 |
| 表情覆盖层 | `eyes_*`（6 个），`z = 2`，`follow = body`，默认 `alpha = 0` |
| 道具贴纸 | 9 个，`z = 1`、`group = 'prop'`、`follow = body`、显示 `64×64`、中心相对锚点 `(0, -74)`；同组互斥，同一时刻只显示一个 |
| 可用动作 | **62 个动作**：2 层常驻基底、19 种随机微动作（含眨眼、思考类与道具类）、8 种点击反馈、21 个状态 / 信号 / 物理专用动作、12 个姿态替身动作；整体变换 + 特效 + 道具 + 表情 + 台词（详见 §6.4） |
| 已支持 | **眨眼、换表情、换道具、换姿态**：6 个表情覆盖层 + 9 个互斥道具贴纸 + 6 个互斥姿态替身按动作 `alpha` 唤起，见 §6.3 |
| 暂不可用 | 单独摆手、走路摆臂等**局部肢体动作**——需要把手臂/腿拆成独立部件；当前用整身姿态替身替代（`pose_*`） |

**为什么这样更像桌宠**：二头身放大了头部与表情；正面静止站姿让「待机」成为自然基准态；双脚完整解决了 v0.2 立绘底部裁脚的问题；白色外描边保证小尺寸、任意壁纸下的可读性；10 个特效 + 9 个道具贴纸 + 6 个表情覆盖层 + 6 个姿态替身 + 56 组关键帧把状态可视化从「只有身体在动」扩展到「会换表情、手上会真的多一台电脑、整个人换个姿势」。

**表情覆盖层的实现要点**：贴片与原图同为 263×520、与 `body` 完全同坐标同尺寸，`z = 2`（在身体之上、特效之下），基础 `alpha = 0`，并声明 `follow: 'body'` 继承主部件的整体位移/旋转/缩放——否则身体一呼吸，眼睛就会漂移。闭眼位置按镜片中心 `(86, 182)` / `(171, 182)` 定位，覆盖半径 17 不触碰眼镜框。新增表情只需丢一个 SVG、在 `skin.json` 登记、动作里写 `alpha` 增量，代码零改动。

**道具贴纸的实现要点**：9 个贴纸统一 `64×64`、`z = 1`（盖住身体下半部、不遮挡脸）、中心 `(0, -74)` 位于腹部/手前；全部声明 `follow: 'body'` 跟随呼吸，并归入 `group: 'prop'` 互斥组——否则多个动作叠加时九件道具会一起冒出来。`coding` → `laptop_code`、`compile_error` → `laptop_error`、`fix_done` → `laptop_done` 是同一台电脑换屏幕内容，用来表达「写代码 → 报错 → 跑通」的状态推进。手动换贴纸走 `compositor.holdProp(id)`，设置面板「道具」下拉或 `pet_emit_signal('prop', id)` 均可；传 `null` 恢复自动。

**局部肢体动作升级路径（不改代码）**：往 `parts/` 增加 `arm_up.png` 等独立肢体部件 → `skin.json` 登记（同样可用 `follow`）→ `motions/*.json` 把 `tracks` 指向新部件。渲染器与合成器不感知部件数量。建议以 `chibi-matted-raw.png` 为参考做局部重绘，保持形象一致性。

**姿态替身（`group: 'pose'`，v0.9；v0.10 接入自动巡演；v0.11 扩充到 12 套）**：在拆出局部肢体之前，先用「整身换姿势的贴片」把动作表现力提上来，代价是每个姿态一张图，好处是渲染器与合成器零改动。12 个部件与 `body` 同 `z = 0`、同 `pivot = [0.5, 1.0]`，`follow: 'body'`（跟随呼吸位移，但不复制 alpha），并归入互斥组 `pose`；配套动作在 `event` 层让 `body` 的 `alpha` 走 `0 → -1 → 0`、替身走 `0 → +1 → 0`，即两个身体交叉淡化，不会出现双人叠影。

制作要点（`tools/sprite_prep.py`）：AI 生成的白底全身图先做**中性浅色泛洪抠图**——白 T 恤偏暖（`R-B ≈ 25`）保得住、白球鞋被深描边包住泛洪进不去，而被擦除手臂留下的中性浅色残影会被清掉；再用**头部尺寸**（头发块长宽的几何平均）做等比校正，六个姿态头围差异 < 4%，比按身高归一稳（坐/卧不会被放大）；水平以头发块中心对齐中轴（抬手不会把人推歪），垂直以内容底边贴 `body` 的鞋底线。画布高度固定、宽度按角色需要向两侧扩展，`skin.json` 里声明 `w = 画布宽 / 2`、`h = 画布高 / 2` 即保持 2x 像素密度。

| 部件 | 姿态 | 显示尺寸 | 配套动作 |
|---|---|---|---|
| `pose_greet` | 抬手招手 | 145 × 262.5 | `pose_greet`（1.7 s，轻微左右摆） |
| `pose_cheer` | 双手上举欢呼 | 173 × 262.5 | `pose_cheer`（1.3 s，蹦两下 + 星星） |
| `pose_walk` | 侧身迈步 | 131.5 × 260.5 | `pose_walk`（1.5 s，上下颠） |
| `pose_sit` | 坐在地上（**v0.10 起用用户提供的 281×542 透明坐姿图**，原 AI 生成版备份为 `assets/reference/pose_sit-v09.*`） | 140.5 × 271 | `pose_sit`（2.8 s，呼吸微动） |
| `pose_sleep` | 侧卧睡着 | 200 × 260 | `pose_sleep`（3.6 s 循环 + `zzz`） |
| `pose_stretch` | 伸懒腰 | 169 × 264 | `pose_stretch`（1.8 s，纵向拉伸） |
| `pose_coding` | 坐小桌前用笔记本电脑写代码 | 167.5 × 263 | `pose_coding`（2.6 s，打字轻晃）「写会儿代码」 |
| `pose_think` | 单手托腮思考 | 138 × 271 | `pose_think`（2.4 s，轻微歪头）「让我想想…」 |
| `pose_read` | 盘腿捧书阅读 | 139 × 260 | `pose_read`（3.0 s，呼吸微动）「翻两页」 |
| `pose_drink` | 双手捧杯喝热饮 | 137.5 × 270 | `pose_drink`（2.2 s，轻轻摇晃）「喝口热的」 |
| `pose_pout` | 双手叉腰鼓脸赌气 | 131.5 × 260 | `pose_pout`（1.8 s，左右摇）「哼，不理你」 |
| `pose_desk_sleep` | 趴小桌睡 | 155.5 × 260 | `pose_desk_sleep`（3.6 s + `zzz`）「眯一会儿」 |

> **v0.10 修正**：`classic` 立绘皮肤复用同一份 `motions/index.json`，但未登记这些替身部件；若播放 `pose_*` 动作，`body` 会淡出而没有替身补位。主循环用 `skinHasPoses()` 检测当前皮肤是否登记了 `group: 'pose'` 部件，**没有就跳过整段姿态巡演**，因此 `classic` 下不会出现人消失；设置面板「试动作」仍可手动触发（此时会短暂空窗，属已知取舍）。要给 `classic` 也做整身姿态，另补一套同角色立绘并在其 `skin.json` 登记即可。

**授权边界**：原图与 Q 版均为 AI 生成 / 衍生，已去水印，仅本地个人使用；不引入任何 CC BY-NC-SA 素材。公开分发前需按各生成平台条款分别核对。

### 6.1 皮肤包目录

```
web/assets/skins/
├── index.json                  # 皮肤列表（P1 生成）
├── stepboy/                    # 当前默认：二头身 Q 版
│   ├── manifest.json           # 元信息 + 动作索引 + 随机池（P1 生成）
│   ├── skin.json               # 部件清单、锚点、z 序、命中框（P1 生成）
│   ├── parts/
│   │   ├── body.webp           # 已落盘：Q 版主部件 263×520 透明
│   │   ├── body.png            # 已落盘：兼容备份
│   │   ├── zzz.svg             # 已落盘：睡眠
│   │   ├── sweat.svg           # 已落盘：忙碌 / 出错
│   │   ├── heart.svg           # 已落盘：点击 / 投喂
│   │   ├── note.svg            # 已落盘：打字 / 开心
│   │   ├── star.svg            # 已落盘：得意 / 成功
│   │   ├── question.svg        # 已落盘：疑惑
│   │   ├── exclaim.svg         # 已落盘：惊醒 / 告警
│   │   ├── cloud.svg           # 已落盘：沮丧 / 余额见底
│   │   ├── eyes_closed.svg     # 已落盘：闭眼 / 眨眼 / 睡眠
│   │   ├── eyes_happy.svg      # 已落盘：笑眼
│   │   ├── eyes_wide.svg       # 已落盘：惊讶
│   │   ├── eyes_angry.svg      # 已落盘：生气
│   │   ├── eyes_dizzy.svg      # 已落盘：晕
│   │   ├── eyes_sparkle.svg    # 已落盘：星星眼
│   │   ├── bulb.svg            # 已落盘：思考 / 灵感
│   │   ├── gear.svg            # 已落盘：工作 / 处理中
│   │   ├── laptop_code.svg     # 已落盘：写代码（代码屏）
│   │   ├── laptop_error.svg    # 已落盘：报错（红屏 ×）
│   │   ├── laptop_done.svg     # 已落盘：跑通（绿屏 √）
│   │   ├── book.svg            # 已落盘：阅读
│   │   ├── notebook.svg        # 已落盘：记录
│   │   ├── coffee.svg          # 已落盘：休息
│   │   ├── magnifier.svg       # 已落盘：查找
│   │   ├── clipboard.svg       # 已落盘：清单
│   │   ├── headphone.svg       # 已落盘：听歌
│   │   ├── pose_greet.webp     # 已落盘：整身立绘 · 招手
│   │   ├── pose_cheer.webp     # 已落盘：整身立绘 · 欢呼
│   │   ├── pose_walk.webp      # 已落盘：整身立绘 · 走路
│   │   ├── pose_sit.webp       # 已落盘：整身立绘 · 坐下（用户提供的坐姿图）
│   │   ├── pose_sleep.webp     # 已落盘：整身立绘 · 侧卧睡
│   │   ├── pose_stretch.webp   # 已落盘：整身立绘 · 伸懒腰
│   │   ├── pose_coding.webp    # 已落盘：整身立绘 · 用电脑写代码
│   │   ├── pose_think.webp     # 已落盘：整身立绘 · 托腮思考
│   │   ├── pose_read.webp      # 已落盘：整身立绘 · 捧书看
│   │   ├── pose_drink.webp     # 已落盘：整身立绘 · 捧杯喝
│   │   ├── pose_pout.webp      # 已落盘：整身立绘 · 叉腰赌气
│   │   ├── pose_desk_sleep.webp # 已落盘：整身立绘 · 趴桌睡
│   │   └── arm_up.png          # 可选：后续补摆手时加入
│   └── motions/ ...
└── classic/                    # 备用：v0.2 立绘素材 + 配置（复用 stepboy 的特效与道具）
```

**换皮**：新增一个目录 + 在 `index.json` 登记 → 设置面板切换 → 热重载生效，**零代码改动**。
**换素材格式**：把 `part.src` 从 `parts/body.webp` 改成 `parts/body.png`，渲染器无需改动（统一走 `Image` 加载）。
**切回立绘形象**：设置面板「皮肤」选 `classic` 即可。它复用同一画布与动作，并登记了特效与道具；**表情覆盖层未登记**（263×520 贴片坐标与立绘不匹配），因此立绘版只有整体动作与道具，不会换表情。

### 6.2 `manifest.json`

```json
{
  "id": "stepboy",
  "name": "眼镜少年（Q 版）",
  "version": 8,
  "author": "user-provided",
  "license": "AI-generated image, local personal use only",
  "skin": "skin.json",
  "motions": "motions/index.json",
  "pools": {
    "micro":  ["blink","look","stretch","sigh","yawn","headtilt","wobble","tiptoe","shake","bounce","daydream","thinking","focus","break_time","brainstorm","reading","note_taking","coffee_break","listening"],
    "poke":   ["happy","shy","startle","proud","curious","pout","giggle","wave"],
    "pose":   ["pose_greet","pose_cheer","pose_walk","pose_sit","pose_sleep","pose_stretch","pose_coding","pose_think","pose_read","pose_drink","pose_pout","pose_desk_sleep"],
    "dream":  ["再走五分钟","云好像慢下来了","先歇一会儿","……","别关窗呀","梦里也在散步"]
  },
  "defaults": {
    "idleToSleepMs": 300000,
    "microMinGapMs": 1200,
    "microMaxGapMs": 3800,
    "poseMinGapMs": 4000,
    "poseMaxGapMs": 12000
  }
}
```

`pools` 是随机池，`weight` 由各 motion 定义，心情值再乘一个系数（见 §7.4）。

### 6.3 `skin.json`

当前 `stepboy` 的**实配**（38 个部件；所有 `src` 均已落盘。为便于阅读压缩成每行一个部件，实际文件是标准格式化 JSON）：

```json
{
  "id": "stepboy",
  "canvas": { "w": 200, "h": 300, "anchor": { "x": 100, "y": 288 } },
  "hitbox": { "x": -66, "y": -260, "w": 132, "h": 260 },
  "parts": [
    { "id": "body", "src": "parts/body.webp", "z": 0, "x": 0, "y": 0, "w": 132, "h": 260, "pivot": [0.5, 1.0], "alpha": 1 },
    { "id": "eyes_closed", "src": "parts/eyes_closed.svg", "z": 2, "x": 0, "y": 0, "w": 132, "h": 260, "pivot": [0.5, 1.0], "alpha": 0, "follow": "body" },
    { "id": "eyes_happy", "src": "parts/eyes_happy.svg", "z": 2, "x": 0, "y": 0, "w": 132, "h": 260, "pivot": [0.5, 1.0], "alpha": 0, "follow": "body" },
    { "id": "eyes_wide", "src": "parts/eyes_wide.svg", "z": 2, "x": 0, "y": 0, "w": 132, "h": 260, "pivot": [0.5, 1.0], "alpha": 0, "follow": "body" },
    { "id": "eyes_angry", "src": "parts/eyes_angry.svg", "z": 2, "x": 0, "y": 0, "w": 132, "h": 260, "pivot": [0.5, 1.0], "alpha": 0, "follow": "body" },
    { "id": "eyes_dizzy", "src": "parts/eyes_dizzy.svg", "z": 2, "x": 0, "y": 0, "w": 132, "h": 260, "pivot": [0.5, 1.0], "alpha": 0, "follow": "body" },
    { "id": "eyes_sparkle", "src": "parts/eyes_sparkle.svg", "z": 2, "x": 0, "y": 0, "w": 132, "h": 260, "pivot": [0.5, 1.0], "alpha": 0, "follow": "body" },
    { "id": "zzz", "src": "parts/zzz.svg", "z": 3, "x": 58, "y": -246, "w": 32, "h": 32, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "sweat", "src": "parts/sweat.svg", "z": 3, "x": 62, "y": -214, "w": 26, "h": 26, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "heart", "src": "parts/heart.svg", "z": 3, "x": -60, "y": -230, "w": 30, "h": 30, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "note", "src": "parts/note.svg", "z": 3, "x": 64, "y": -186, "w": 28, "h": 28, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "star", "src": "parts/star.svg", "z": 3, "x": -58, "y": -196, "w": 30, "h": 30, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "question", "src": "parts/question.svg", "z": 3, "x": 60, "y": -168, "w": 28, "h": 28, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "exclaim", "src": "parts/exclaim.svg", "z": 3, "x": -62, "y": -262, "w": 24, "h": 32, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "cloud", "src": "parts/cloud.svg", "z": 4, "x": 0, "y": -266, "w": 40, "h": 24, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "bulb", "src": "parts/bulb.svg", "z": 3, "x": 0, "y": -272, "w": 30, "h": 30, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "gear", "src": "parts/gear.svg", "z": 3, "x": 66, "y": -150, "w": 26, "h": 26, "pivot": [0.5, 0.5], "alpha": 0 },
    { "id": "laptop_code", "src": "parts/laptop_code.svg", "z": 1, "x": 0, "y": -74, "w": 64, "h": 64, "pivot": [0.5, 0.5], "alpha": 0, "group": "prop", "follow": "body" },
    { "id": "laptop_error", "src": "parts/laptop_error.svg", "z": 1, "x": 0, "y": -74, "w": 64, "h": 64, "pivot": [0.5, 0.5], "alpha": 0, "group": "prop", "follow": "body" },
    { "id": "laptop_done", "src": "parts/laptop_done.svg", "z": 1, "x": 0, "y": -74, "w": 64, "h": 64, "pivot": [0.5, 0.5], "alpha": 0, "group": "prop", "follow": "body" },
    { "id": "book", "src": "parts/book.svg", "z": 1, "x": 0, "y": -74, "w": 64, "h": 64, "pivot": [0.5, 0.5], "alpha": 0, "group": "prop", "follow": "body" },
    { "id": "notebook", "src": "parts/notebook.svg", "z": 1, "x": 0, "y": -74, "w": 64, "h": 64, "pivot": [0.5, 0.5], "alpha": 0, "group": "prop", "follow": "body" },
    { "id": "coffee", "src": "parts/coffee.svg", "z": 1, "x": 0, "y": -74, "w": 64, "h": 64, "pivot": [0.5, 0.5], "alpha": 0, "group": "prop", "follow": "body" },
    { "id": "magnifier", "src": "parts/magnifier.svg", "z": 1, "x": 0, "y": -74, "w": 64, "h": 64, "pivot": [0.5, 0.5], "alpha": 0, "group": "prop", "follow": "body" },
    { "id": "clipboard", "src": "parts/clipboard.svg", "z": 1, "x": 0, "y": -74, "w": 64, "h": 64, "pivot": [0.5, 0.5], "alpha": 0, "group": "prop", "follow": "body" },
    { "id": "headphone", "src": "parts/headphone.svg", "z": 1, "x": 0, "y": -74, "w": 64, "h": 64, "pivot": [0.5, 0.5], "alpha": 0, "group": "prop", "follow": "body" },
    { "id": "pose_greet", "src": "parts/pose_greet.webp", "z": 0, "x": 0, "y": 0, "w": 145.0, "h": 262.5, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_cheer", "src": "parts/pose_cheer.webp", "z": 0, "x": 0, "y": 0, "w": 173.0, "h": 262.5, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_walk", "src": "parts/pose_walk.webp", "z": 0, "x": 0, "y": 0, "w": 131.5, "h": 260.5, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_sit", "src": "parts/pose_sit.webp", "z": 0, "x": 0, "y": 0, "w": 140.5, "h": 271.0, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_sleep", "src": "parts/pose_sleep.webp", "z": 0, "x": 0, "y": 0, "w": 200.0, "h": 260.0, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_stretch", "src": "parts/pose_stretch.webp", "z": 0, "x": 0, "y": 0, "w": 169.0, "h": 264.0, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_coding", "src": "parts/pose_coding.webp", "z": 0, "x": 0, "y": 0, "w": 167.5, "h": 263.0, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_think", "src": "parts/pose_think.webp", "z": 0, "x": 0, "y": 0, "w": 138.0, "h": 271.0, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_read", "src": "parts/pose_read.webp", "z": 0, "x": 0, "y": 0, "w": 139.0, "h": 260.0, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_drink", "src": "parts/pose_drink.webp", "z": 0, "x": 0, "y": 0, "w": 137.5, "h": 270.0, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_pout", "src": "parts/pose_pout.webp", "z": 0, "x": 0, "y": 0, "w": 131.5, "h": 260.0, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" },
    { "id": "pose_desk_sleep", "src": "parts/pose_desk_sleep.webp", "z": 0, "x": 0, "y": 0, "w": 155.5, "h": 260.0, "pivot": [0.5, 1.0], "alpha": 0, "group": "pose", "follow": "body" }
  ]
}
```

字段说明（**渲染器实现契约，勿臆测**）：

| 字段 | 说明 |
|---|---|
| `canvas.anchor` | 画布内坐标原点，取角色「脚底中心」；所有 `part.x/y` 相对它计算 |
| `part.x / y` | **pivot 点**相对锚点的偏移，**不是部件中心偏移**。`body` 的 `(0,0)` + `pivot=[0.5,1.0]` 表示脚底正好落在锚点 |
| `part.w / h` | 显示尺寸；素材按 2 倍导出（`body` 文件 263×520 → 显示 132×260） |
| `part.pivot` | 归一化旋转 / 缩放中心，`[0,0]` 左上、`[1,1]` 右下；同时决定 `x/y` 定位的是哪个点 |
| `part.z` | 层级，小值先画；特效统一 `z = 3`，浮在角色之上 |
| `part.alpha` | **基础透明度**（0–1）。主部件 `1`，特效部件 `0`；动作的 `alpha` 关键帧是**增量**，见 §7.1 |
| `part.flip` | 可选，水平镜像；当前皮肤未使用 |
| `part.follow` | 可选，指定另一个部件 id；该部件**继承**其 `dx/dy/rot/sx/sy`（不继承 alpha），用于表情覆盖层等需与主部件同步运动的贴片 |
| `part.group` | 可选，互斥组名。同组部件每帧只保留一个可见；仲裁分数 `alpha 增量 × gain × 层级权重`（base/physics=1、micro=2、event=3），同分取后出现者。手动 `holdProp` 优先级最高 |
| `hitbox` | 相对锚点的命中矩形，供热区上报与点击判定 |

绘制伪代码（消除歧义）：

```
ctx.translate(anchor.x + part.x + t.dx, anchor.y + part.y + t.dy)
ctx.rotate(t.rot * PI / 180)
ctx.scale(t.sx, t.sy)
ctx.globalAlpha = t.alpha
ctx.drawImage(img, -pivot[0] * w, -pivot[1] * h, w, h)
```

> v0.1 的多部件鱼形 `skin.json`（tail / fin / eyeL …）仅是**渲染器能力示例**，已不代表当前形象，不再作为实现依据。渲染器不限制部件数量，按上表登记任意部件即可。

### 6.4 `motions/*.json`

字段表：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `id` | string | 必填 | 全局唯一，与文件名一致 |
| `layer` | `base` / `micro` / `event` | `event` | 叠加层，`physics` 由代码生成不入 JSON |
| `duration` | number(ms) | `600` | 单次时长 |
| `loop` | bool | `false` | 是否循环（base 层通常为 true） |
| `priority` | int | `10` | 同层冲突时的优先级 |
| `interruptible` | bool | `true` | 是否可被更高优先级动作打断 |
| `weight` | number 0..1 | `1` | 随机池抽取权重 |
| `easing` | string | `easeInOutSine` | 缓动函数名（见 `easing.js`） |
| `fade` | number(ms) | `120` | 进入/退出的交叉淡化时长 |
| `tracks` | object | 必填 | `partId → 关键帧数组`；**`partId` 必须在 `skin.json` 中登记** |
| `trigger` | object | — | 可选，信号触发条件（如 `{"signal":"typing"}`） |
| `line` | string | — | 可选，动作触发时的气泡台词；**仅 event 层动作会冒气泡**，随机 micro 不冒，避免刷屏 |

关键帧字段：`t`（0..1，必填）、`dx`、`dy`、`rot`（度）、`sx`、`sy`、`alpha`。

- `dx / dy / rot` 是**增量**，各层相加。
- `sx / sy` 是**缩放倍率**，各层相乘（恒等值为 1）。
- `alpha` 是**透明度增量**，不是绝对目标值；与 `part.alpha` 相加后钳制到 `[0,1]`（见 §7.1）。特效部件基础为 0，故用 `+1` 唤起、`0` 收起。

**缺省规则**：某属性未在某帧写出时，沿用该属性上一次出现的值；全程未出现则取恒等值（`dx/dy/rot/alpha = 0`，`sx/sy = 1`）。因此只写变化点即可。

三个可直接使用的示例：

```json
// motions/breathe.json — base 层常驻，只作用于主部件
{
  "id": "breathe", "layer": "base", "loop": true,
  "duration": 3200, "easing": "easeInOutSine",
  "tracks": {
    "body": [{"t":0,"sy":1,"dy":0}, {"t":0.5,"sy":1.035,"dy":-2}, {"t":1,"sy":1,"dy":0}]
  }
}
```

```json
// motions/happy.json — 点击反馈：身体弹跳 + 唤起爱心
{
  "id": "happy", "layer": "event", "priority": 20,
  "duration": 720, "easing": "easeOutBack", "fade": 100,
  "tracks": {
    "body":  [{"t":0,"dy":0,"sy":1}, {"t":0.3,"dy":-14,"sy":1.06}, {"t":0.7,"dy":-3,"sy":0.98}, {"t":1,"dy":0,"sy":1}],
    "heart": [{"t":0,"alpha":0}, {"t":0.15,"alpha":1,"dy":-6}, {"t":0.8,"alpha":1,"dy":-22}, {"t":1,"alpha":0,"dy":-26}]
  }
}
```

```json
// motions/sleep.json — 下沉 + zzz
{
  "id": "sleep", "layer": "event", "priority": 15, "loop": true,
  "duration": 2600, "fade": 200,
  "tracks": {
    "body": [{"t":0,"dy":6,"rot":0}, {"t":0.5,"dy":8,"rot":-1.5}, {"t":1,"dy":6,"rot":0}],
    "zzz":  [{"t":0,"alpha":0,"dy":0}, {"t":0.3,"alpha":1,"dy":-8}, {"t":0.85,"alpha":1,"dy":-26}, {"t":1,"alpha":0,"dy":-30}]
  }
}
```

动作清单（与 §6.2 `pools`、§7.2 状态表严格一致，共 **50** 个；以 `motions/index.json` 为准）：

| 组 | 数量 | 动作 | 触发方式 |
|---|---|---|---|
| base 常驻 | 2 | `breathe` / `sway` | 永久循环，两层叠加让待机不呆板 |
| micro 随机 | 19 | 上述 11 个 + 4 个思考类 + `reading` / `note_taking` / `coffee_break` / `listening` | `pools.micro`，1.2–3.8 s 随机间隔 × 心情权重；4 个道具类动作保证**不接外部信号也能看到道具** |
| poke 点击 | 8 | `happy` / `shy` / `startle` / `proud` / `curious` / `pout` / `giggle` / `wave` | `pools.poke`，点击角色随机抽取，各带专属特效与台词 |
| event 专用 | 21 | 上述 16 个 + `coding` / `compile_error` / `fix_done` / `inspecting` / `checklist` | 状态机、Agent 状态、写代码三态、物理与数值分档（见 §8.2） |
| pose 整身姿态 | 12 | 上述 6 个 + `pose_coding` / `pose_think` / `pose_read` / `pose_drink` / `pose_pout` / `pose_desk_sleep` | `pools.pose`，idle 时每 4–12 s 随机换一整套立绘（首次 2.5 s），配台词；设置面板「试动作」也可逐个试播 |

```json
// motions/index.json
["breathe", "sway", "blink", "look", "stretch", "sigh", "yawn", "headtilt", "wobble", "tiptoe", "shake", "bounce", "daydream", "thinking", "focus", "break_time", "brainstorm", "reading", "note_taking", "coffee_break", "listening", "happy", "shy", "startle", "proud", "curious", "pout", "giggle", "wave", "type_tap", "work", "alert", "sleep", "struggle", "land", "wake_up", "success", "error", "balance_low", "working", "organizing", "waiting", "searching", "debugging", "celebrate", "coding", "compile_error", "fix_done", "inspecting", "checklist"]
```

> 动作之间的差异靠**幅度 + 节奏 + 道具 + 特效语义 + 台词**区分：例如 `thinking` 歪头 -7° 配灯泡与便签本、`coding` 循环打字配代码屏电脑、`compile_error` 下沉配红屏电脑与汗滴、`fix_done` 弹跳配绿屏电脑与星星。新增动作只需新建 JSON、登记进 `motions/index.json` 并把 `manifest.json` 的 `version` +1，代码零改动。

> 12 个已有动作也挂上了道具（`working`/`work`/`type_tap` → 代码屏电脑，`debugging` → 报错屏，`searching` → 放大镜，`organizing` → 清单，`waiting`/`break_time` → 咖啡，`thinking` → 便签，`focus` → 书，`success`/`celebrate` → 绿屏电脑），因此切状态时道具会跟着换。

> `blink` 已实现：它把 `eyes_closed` 覆盖层的 `alpha` 在 220 ms 内 0→1→0，配合 `follow: 'body'` 跟随呼吸。其余动作也各自挂了表情轨道（如 `happy`→笑眼、`startle`→惊讶、`sleep`→常驻闭眼），表情由 JSON 驱动，无需改代码。

### 6.5 部件素材规范

| 项 | 要求 |
|---|---|
| 格式 | WebP（带 alpha，运行时默认）/ PNG（兼容备份）；叠加小部件可用 SVG |
| 背景 | 必须真透明（`alpha=0`），不允许白底或残留背景碎块 |
| 尺寸 | 按 `skin.json` 的 `w/h` 的 2 倍导出；当前 `body` 为 263×520 → 显示 132×260 |
| 命名 | 小写 + 连字符，与 `part.id` 对应 |
| 边缘 | 抠图边缘不得残留棋盘格图案 / 生成标记；Q 版自带的白色贴纸描边是设计风格，予以保留 |
| 叠加特效 | 10 个 64×64 SVG，白描边 + 主色填充，透明底；`alpha = 0`，由动作增量唤起 |
| 整身姿态立绘 | 12 对 WebP/PNG 透明全身图，2x 导出；`z = 0`、`group = 'pose'`、`follow = 'body'`、底边 pivot `[0.5,1.0]`；动作让 `body` 与替身交叉淡化 |
| 道具贴纸 | 9 个 64×64 SVG，同规格白描边风格；`z = 1`、`group = 'prop'`、`follow = 'body'`、`alpha = 0`；中心 `(0, -74)` 不遮挡脸与眼镜，见 `props-preview.png` |
| 表情覆盖层 | 6 个 263×520 SVG，与 `body` 同坐标同尺寸，`z = 2`，`alpha = 0`，必须声明 `follow: 'body'`；覆盖圆不得触碰眼镜框 |
| 校验 | 用 `assets/reference/preview-chibi.png` 同款棋盘格预览肉眼确认 |
| 单包体积 | 运行时 WebP+SVG ≤ 500 KB（当前约 470 KB）；PNG 为 2x 母版/兼容备份（约 2.3 MB），不参与运行，发布时可剔除 |

### 6.6 「零代码」操作流程

| 想做的事 | 操作 |
|---|---|
| 加一个新动作 | 新建 `motions/xxx.json` → 在 `motions/index.json` 登记 → 热重载 |
| 调动作手感 | 改 `duration` / `easing` / 关键帧数值 → 热重载，无需重启 |
| 改气泡台词 | 改动作的 `line` 字段 → 热重载；删掉该字段即静默 |
| 加一种表情 | 往 `parts/` 丢一个同尺寸 SVG → `skin.json` 登记（`alpha: 0` + `follow`）→ 动作里写 `alpha` 增量 |
| 加一个情绪特效 | 往 `parts/` 丢一个 SVG + `skin.json` 登记，用 `alpha` 关键帧在对应动作里唤起 |
| 加一个道具贴纸 | 往 `parts/` 丢一个 64×64 SVG → `skin.json` 登记（`alpha: 0` + `group: 'prop'` + `follow: 'body'`）→ 动作里写 `alpha` 增量；同组自动互斥 |
| 加一个整身姿态 | 往 `parts/` 丢一张透明全身立绘 → `skin.json` 登记（`alpha: 0` + `group: 'pose'` + `follow: 'body'` + 底边 pivot）→ 配一个让 `body` 淡出、替身淡入的动作 → 登记进 `pools.pose` |
| 手动换贴纸 / 试动作 | 设置面板「道具」下拉锁定贴纸；「试动作」下拉可逐个试播全部 62 个动作（含 12 个整身姿态） |
| 加一个表情 / 局部动作 | 新增叠加部件（SVG/WebP）+ `skin.json` 登记，用 `alpha` 或局部 transform 关键帧控制 |
| 换整套形象 | 新皮肤目录 + `index.json` 登记 → 设置面板切换 |
| 改随机频率 | 改 `manifest.json` 的 `defaults` 与各 motion 的 `weight` |

### 6.7 落盘状态与实现对照（v0.4 修订）

P0–P3 的主体实现已按本文档完成落盘，下表为实际状态。

| 文件 / 目录 | 状态 | 说明 |
|---|---|---|
| `DESIGN.md` | ✅ | 本文件 |
| `assets/**` 原图、母版、预览 | ✅ | 不参与运行 |
| `web/assets/skins/stepboy/parts/**`（51 个素材） | ✅ | 主部件 + PNG 备份 + 10 特效 + 6 表情 + 9 道具 + 12 姿态立绘（各含 WebP/PNG） |
| `web/assets/skins/classic/parts/**`（2 个素材） | ✅ | 立绘备用 |
| `package.json` / `jsconfig.json` / `dev-server.mjs` | ✅ | 零依赖，端口 5180 |
| `web/index.html` / `src/style.css` / `src/main.js` | ✅ | P0 舞台、样式、装配与主循环 |
| `web/src/core/**`（7 个模块） | ✅ | clock / easing / motion / compositor / state-machine / physics / signals |
| `web/src/render/**` / `platform/bridge.js` | ✅ | 渲染适配器与 Tauri IPC 封装（含 Web 兜底） |
| `web/assets/skins/index.json` | ✅ | 皮肤列表（stepboy / classic） |
| `stepboy/manifest.json` / `skin.json` / `motions/*.json` | ✅ | 皮肤配置 + 62 个动作（含 12 个整身姿态） |
| `classic/manifest.json` / `skin.json` | ✅ | 复用 stepboy 动作，不重复落盘 |
| `src-tauri/**` | ✅ 源码 | 窗口 / 托盘 / 10 个 IPC 命令 / 光标穿透轮询；**未经 cargo check**（本机无 Rust 工具链） |
| `tests/*.test.mjs` | ✅ | 行为层与契约单测 |
| `README.md` | ✅ | 运行与二次开发说明 |

**验证结果**：`node --check` 全部 JS 通过；72 个 JSON 合法；`node --test` **32/32 通过**（含 12 个姿态的部件契约、交叉淡化与巡演池）；用真实皮肤数据逐个验证 12 个姿态均能「body 淡出 / 对应替身淡入 / 组内其余隐藏」；预览服务器对页面、JS、皮肤 JSON、WebP、SVG 均返回 200，皮肤目录带 `Cache-Control: no-store`。

> 唯一未验证的是 Rust 壳：本机无 rustc/cargo，首次 `cargo build` 需在有工具链与网络的环境完成（见 §4 / §13）。§12 的开放项仍按默认值实现，未改变架构。

### 6.8 皮肤加载路径与热重载（v0.4 新增）

「改文件即热更新」需要明确走哪条路径，否则发布版无法换皮：

| 模式 | 皮肤来源 | 热重载方式 |
|---|---|---|
| Web 预览 / 开发期（P0–P2） | 内置 `web/assets/skins/<id>/`，前端 `fetch` 加载 | 每 2 s 轮询 `manifest.json` 的 `version`；变更则重建 `MotionBank` 与部件缓存，**不清空状态机**；开发服务器对该路径返回 `Cache-Control: no-store` |
| Tauri 发布版（P4） | 外部目录 `skins/`（exe 同级），用户可自行增删 | Rust 命令 `pet_list_skins` / `pet_read_skin` 读取 JSON 与素材路径；内置皮肤作为 fallback |
| 手动 | 任意模式 | 托盘 / 设置面板「重载皮肤」立即重新加载 |

- **不做文件系统 watch**：避免额外依赖与权限。轮询仅在开发模式或设置面板打开时启用；发布版默认纯手动，或 10 s 一次低频轮询。
- 内置皮肤随包打入、只读；**只有外部 `skins/` 目录才支持用户自定义换皮**，这与 §2.1「换整套形象」的承诺一致。
- 外部皮肤目录是 §2.4 最小权限的明确例外：Rust 仅只读该目录，不扫描其他路径。
- `classic` 皮肤配置已落盘。为与固定 200×300 的 Tauri 窗口一致，它复用 `stepboy` 的画布与锚点，仅把立绘素材按 163×240 显示；`motions` 字段指向 `../stepboy/motions/index.json`，复用同一套 62 个动作，不重复落盘。它还登记了 10 个特效与 9 个道具（`src` 指向 `../stepboy/parts/`），但不登记表情覆盖层（贴片坐标与立绘不匹配）与整身姿态替身；主循环检测到没有 `group: 'pose'` 部件会跳过姿态巡演。

---

## 7. 行为模型

### 7.1 四层叠加

每帧对每个部件的变换是四层**相加/相乘**，而非互斥播放：

| 层 | 内容 | 生命周期 |
|---|---|---|
| 1 常驻基底 | 呼吸正弦、重心微摆、待机浮动 | 永久循环 |
| 2 随机微动作 | 轻微倾斜、伸懒腰、摇头、叹气（均为整体变换） | 泊松式随机间隔 + 权重池 |
| 3 事件动作 | 点击、拖拽、投喂、状态联动 | 状态机驱动，带优先级/打断 |
| 4 物理修正 | 弹簧惯性、重力落地回弹、指针视差 | 每帧数值积分 |

合成公式（对每个部件）：

```
dx    = Σ (layer.dx_i × gain_i)
dy    = Σ (layer.dy_i × gain_i)
rot   = Σ (layer.rot_i × gain_i)
sx    = Π (1 + (layer.sx_i − 1) × gain_i)
sy    = Π (1 + (layer.sy_i − 1) × gain_i)
alpha = clamp( part.alpha + Σ (layer.alpha_i × gain_i), 0, 1 )
```

`gain_i` 是动作进入/退出时 0→1 / 1→0 的淡化系数（默认 120 ms），保证切换不硬切。
**4 个动作 × 4 层 ≈ 几十种组合**，而不是手写几十段互斥动画。

> **v0.8 新增：互斥组仲裁**。`group` 相同的部件（当前有两组：9 个 `prop` 道具、6 个 `pose` 整身姿态）在每帧叠加完后再做一次仲裁：取 `alpha 增量 × gain × 层级权重` 最大的一个保留，其余 `alpha` 强制归 0；层级权重 `event=3 > micro=2 > base/physics=1`，保证状态动作的电脑不会被随机小动作的书本顶掉。手动 `holdProp(id)` / `holdPose(id)` 优先级高于一切自动仲裁，传 `null` 恢复；合成器还会在组内没有任何动作唤起时回落到基础可见部件，避免主体消失。该步骤在 `follow` 之后、`alpha` 钳制之前执行。

> **v0.4 修正**：`alpha` 通道是「部件基础值 + 增量求和后钳制」，**不是连乘**。主部件 `part.alpha = 1`，特效部件 `part.alpha = 0`，动作关键帧里的 `alpha` 写的是**增量**（`+1` 唤起、`0` 收起）。v0.3 及之前用连乘，基础值为 0 的特效部件将永远不可见。`dx/dy/rot` 相加、`sx/sy` 相乘的规则不变。

> 当前 `stepboy` 皮肤 = 38 个部件（1 个 `body` + 12 个整身姿态 + 10 个特效 + 9 个道具 + 6 个表情覆盖层）。四层变换作用于主部件，`alpha` 唤起特效 / 表情 / 道具 / 姿态替身；`follow: 'body'` 让贴片继承主部件变换，因此**呼吸时眼睛、道具、姿态都不漂移**。62 个动作靠「整体幅度 + 道具 + 整身姿态 + 特效语义 + 表情 + 台词」组合出可辨识差异：小动作改变换，大动作直接换一整套立绘。仅连续的摆臂 / 走路循环仍需拆独立肢体部件。

### 7.2 状态机

| 状态 | 优先级 | 可打断 | event 动作 | 进入条件 | 退出条件 |
|---|---|---|---|---|---|
| `idle` | 0 | — | 无（仅 base + micro） | 默认 | 任意事件 |
| `follow` | 5 | 是 | `look` | 指针进入角色附近 | 指针离开 |
| `typing` | 10 | 是 | `type_tap` | 页面内 keydown | 1.5 s 无输入 |
| `busy` | 12 | 是 | `work` | 外部注入的 Agent 状态（预留） | 状态回到 idle |
| `sleep` | 15 | 是 | `sleep` | 全局空闲 > `idleToSleepMs` | 任意输入 |
| `poke` | 20 | 是 | `pools.poke` 随机 | 点击角色 | 动作结束 |
| `alert` | 25 | 是 | `alert` | 构建失败 / 余额告警（可选） | 3 s 或点击 |
| `drag` | 30 | **否** | `struggle` | 按下并移动 | 松手 → `drop` |
| `drop` | 30 | **否** | `land` | 松手 | 落地 + 回弹结束 → `idle` |

**转换规则**：`to.priority >= from.priority` 且 `from.interruptible = true` 才允许打断；`drag ⇄ drop` 为专用通道，不受优先级影响。`sleep` 状态下屏蔽一切随机微动作、碎碎念与告警（对齐 dsh-pet 的「完全静默」）。

### 7.3 随机微动作与心情

- 间隔取 `microMinGapMs ~ microMaxGapMs` 之间的随机值，近似泊松过程，避免「每隔 N 秒整点眨眼」的机械感。
- `mood ∈ [0,1]` 随时间缓慢衰减，点击 / 投喂增加；只作为随机池 `weight` 的乘数，**不新增状态**，避免状态机膨胀。
- 心情低时提高 `sigh` / `stretch` 权重，高时提高 `happy` / `look` 权重（动作 id 见 §6.4 清单）。19 个 micro 动作各有 `weight`（含 `blink`、4 个思考类与 4 个道具类），保证待机表现不重复。
- **整身姿态巡演（v0.10，v0.11 扩充）**：独立计时器读 `pools.pose`（12 项），在 `idle` 且皮肤登记了 `group: 'pose'` 部件时，每 4–12 s 随机播一个姿态动作（首次 2.5 s），把站姿整只换成招手 / 欢呼 / 走路 / 坐下 / 侧卧睡 / 伸懒腰 / 用电脑写代码 / 托腮思考 / 捧书看 / 捧杯喝 / 叉腰赌气 / 趴桌睡，动作结束自动回站姿。与 micro 池分开计时、动作播放期间不重复抽、交互或睡眠期间不累计，避免叠影与刷屏；**姿态播放期间同时暂停 micro 随机动作**，因为表情覆盖层与道具贴片按站姿坐标绘制，叠到坐姿 / 趴姿立绘上会错位（v0.11 运行截图验证后修正）。

### 7.4 物理

| 输出 | 作用对象 | 算法 |
|---|---|---|
| 部件形变 | 角色整体 | 弹簧：`a = −k(x−target) − c·v`，用于拖拽挣扎、整体倾斜惯性、落地挤压 |
| 窗口位移 | 整个窗口 | 松手后 `vy += g·dt`，碰到工作区底边时 `vy = −vy·0.35` 回弹两次后静止 |

物理与动作解耦：动作 JSON 不写物理，物理也不写死在某个动作里，二者在合成器里相加。因此「抛掷」「落地」的手感可以单独调参。

---

## 8. 系统联动

### 8.1 信号源

| 信号 | 来源 | 权限 | 默认 | 映射 |
|---|---|---|---|---|
| `keydown` | 页面内 `document` | 无 | 开 | `typing` 状态 + `type_tap`，按击键节奏微抖 |
| `global_idle_ms` | Rust `GetLastInputInfo` | 只读系统计数 | 开 | 超阈值 → `sleep`；期间冒 `pools.dream` 梦话气泡（只显示、不出声） |
| `pointer_near` | 页面内 | 无 | 开 | `follow`，角色整体轻微倾斜 + 水平位移模拟视线方向（当前无独立眼睛 / 头部部件） |
| `build_state` | 用户显式指定的单个本地日志文件 | 需手动配置路径 | 关 | `busy` / `alert` |
| `balance_value` | 本地配置中的普通数字 | 无网络、无凭据 | 关 | 表情分档（见 8.3） |
| `agent_state` | 外部进程通过 `pet_emit_signal` 注入 | 需用户启用 | 关 | 状态映射（含 `coding`，见 8.2） |
| `prop` | 设置面板「道具」下拉 / `pet_emit_signal` | 无 | 开 | 手动锁定当前道具贴纸，见 §6.3 |

> 明确不采集：聊天会话记录、浏览器数据、剪贴板、文件内容（除用户显式指定的日志）、任何网络数据。

### 8.2 Agent 状态映射（已实现专属动作 + 写代码态）

| 外部状态 | 内部状态 | 动作 | 气泡模板（可在皮肤包覆盖） |
|---|---|---|---|
| thinking 思考 | `busy` | `thinking` | 「让我想想…」（歪头 -7° + 问号 + 灯泡 + 睁大眼） |
| working 工作 | `busy` | `working` | 「在翻文件啦」（循环处理 + 代码屏电脑 + 闭眼专注） |
| coding 写代码 | `busy` | `coding` | 「在写代码呢」（打字轻晃 + 代码屏电脑 + 音符 + 闭眼） |
| organizing 整理 | `busy` | `organizing` | 「收一收桌面」（上下收拾 + 星星 + 笑眼） |
| waiting 等待 | `busy` | `waiting` | 「等你一句话」（缓慢左右摇 + 音符） |
| success 成功 | 不切状态 | `celebrate` | 「太好了！」（跳 -28 px + 星星爱心 + 星星眼） |
| error 出错 | 不切状态 | `debugging` | 「哪儿不对呢」（歪头 + 报错屏电脑 + 汗滴 + 生气眼） |

**扩展状态**：`searching`（放大镜找东西）、`debugging`（报错屏排查）也已登记，走同一个 `busy` 通道。**写代码三态**：`coding` → `compile_error`（红屏 × + 汗滴）→ `fix_done`（绿屏 √ + 星星）是同一台电脑换屏幕内容，用于表达一次完整的编码过程；三者都可在设置面板「试动作」里逐个试播。

**实现**：`state-machine.js` 导出 `AGENT_ACTION_MAP`（7 项），`dispatch('signal', {state})` 据此选动作；`busy` 状态内再收到新状态会**热切换动作**（`thinking → organizing` 不重进状态机）。`success` / `error` 不切状态，直接播放一次性动作。链路：`bridge.onSignal` → `signals.inject()` → 状态机 → `playWithLine()`（含气泡）。本期不接 DSH，但接一根线就能用。

> 另有 8 个**日常语义微动作**进了 `pools.micro`：4 个思考类（`thinking` / `focus` / `break_time` / `brainstorm`）与 4 个道具类（`reading` 看书 / `note_taking` 记录 / `coffee_break` 喝咖啡 / `listening` 听歌）。它们不需要外部信号，待机时随机出现，保证用户不接 Agent 也能看到「在想事情、手上有东西」的表现。

### 8.3 数值分档（借鉴 dsh-pet 的余额表情）

`balance_value` 按 20% 一档映射为不同视觉反馈（对标 dsh-pet「钱袋满溢 → 分文不剩」的 6 档思路）。**当前皮肤没有 6 套钱袋素材**：先用 §6.3 的 10 个特效部件做 `alpha` 显隐组合 + 身体姿态分档；低于 20% 时直接播放带乌云与感叹号的 `balance_low` 动作，后续补表情部件再扩到 6 档。**只做本地数值映射，不查询任何服务商、不存凭据**；数值来源由用户在设置面板手动填写或指向本地配置文件。

### 8.4 为什么不做自动发帖

B 站「鱼灾」的争议（真人评论被淹没、AI 内容标识、平台治理）源于「Agent 主动在公共平台发言」。本项目的联动方向是**只读本地状态 → 本地动画表现**，单向、无外发，从设计上规避该类问题。

---

## 9. 性能预算与优化

| 指标 | 目标 | 说明 |
|---|---|---|
| 发布包 exe | ≤ 15 MB | Tauri 2 release 通常 3–8 MB；WebView2 用系统自带 |
| 常驻内存 | ≤ 150 MB | 需实测校准；同类 Tauri 桌宠实测区间 80–230 MB |
| 静止 CPU | < 1% | 静止降帧 + 无动画时跳过重绘 |
| 启动到可见 | < 1 s | 资源本地加载，无网络等待 |
| 单皮肤包（运行时） | ≤ 500 KB | WebP+SVG 实测约 470 KB；PNG 母版约 2.3 MB 不加载，发布可剔除 |
| 帧率 | idle 30 / 动作 60 / 隐藏 0 | 由 `clock.js` 动态切换 |

优化手段：

1. `requestAnimationFrame` + 真实 `deltaTime`，按目标帧率门控；`visibilitychange` 时取消 RAF，恢复时重置时间基准避免大 dt 跳变。
2. 静止且无微动作时跳过整帧绘制（脏矩形判定），不触发任何重绘。
3. 主部件只解码一次并缓存到离屏 canvas，避免每帧重复解码 WebP。
4. 气泡用 DOM 而非 canvas 重绘，避免每帧文字重排。
5. 热区上报 100 ms 一次、穿透轮询 16–33 ms 一次，与渲染帧解耦。
6. 皮肤资源用带 alpha 的 WebP，禁用整段透明视频。

---

## 10. 平台壳设计（Tauri 2 / Rust）

### 10.1 窗口配置（`tauri.conf.json` 要点）

```json
{
  "app": {
    "windows": [{
      "label": "pet",
      "title": "Stepboy",
      "width": 200, "height": 300,
      "transparent": true,
      "decorations": false,
      "alwaysOnTop": true,
      "resizable": false,
      "skipTaskbar": true,
      "shadow": false,
      "center": false
    }]
  },
  "build": { "devUrl": "http://127.0.0.1:5180", "frontendDist": "../web" }
}
```

`Cargo.toml` 依赖：`tauri = "2"`、`serde`/`serde_json`、Windows 目标下 `windows-sys`（`Win32_UI_WindowsAndMessaging` + `Win32_Foundation`）。

### 10.2 IPC 命令表

前端通过 `window.__TAURI_INTERNALS__.invoke(cmd, args)` 直连，不使用 `@tauri-apps/api`。参数一律用单词或 snake_case，避免 camelCase ↔ snake_case 自动转换的歧义。

| command | 参数 | 作用 |
|---|---|---|
| `pet_start_drag` | — | 调用原生 `start_dragging()` |
| `pet_move_by` | `dx`, `dy` | 相对移动窗口（物理下落 / Web 预览兜底） |
| `pet_set_hotspots` | `rects` | 上报可点击热区，同时刷新心跳 |
| `pet_get_idle` | — | 返回全局空闲毫秒（`GetLastInputInfo`） |
| `pet_get_screen` | — | 返回工作区尺寸与 DPI 缩放（落地边界、多屏） |
| `pet_set_opaque` | `on` | 透明窗口黑底时的不透明兜底开关 |
| `pet_emit_signal` | `kind`, `value` | 外部信号注入（Agent 联动预留） |
| `pet_set_autostart` | `on` | 开机自启 |
| `pet_list_skins` | — | 列出外部 `skins/` 下的皮肤 id（P4，见 §6.8） |
| `pet_read_skin` | `id` | 读取外部皮肤的 manifest / 部件路径（P4） |

`bridge.js` 在检测不到 `__TAURI_INTERNALS__` 时全部降级为本地模拟（DOM 位移、页面内空闲计时、noop），保证 Web 预览可用。

### 10.3 光标穿透算法（核心）

```
状态：Arc<Mutex<Hotspots>> { rects: Vec<Rect>, last_heartbeat: Instant }
      Arc<AtomicBool> dragging

后台线程（每 16–33 ms）：
  1. GetCursorPos → 转物理坐标
  2. 若 dragging == true            → ignore = false
     否则若 now - last_heartbeat > 500ms → ignore = true   // fail-safe
     否则若 命中任一 rect            → ignore = false
     否则                            → ignore = true
  3. 仅当状态变化时调用 set_ignore_cursor_events，避免高频 IPC 抖动
```

要点：热区由前端每 100 ms 重发（角色会走动/缩放）；心跳超时强制恢复穿透，渲染层崩溃也绝不锁死桌面；拖拽期间强制可点。

### 10.4 托盘

`TrayIconBuilder` + `Icon::Raw`（运行时构造 RGBA 图标，避免额外图标文件），菜单项：显示/隐藏、置顶开关、穿透开关、不透明兜底、开机自启、切换皮肤、退出。

### 10.5 已知坑与对策

| 坑 | 对策 |
|---|---|
| Tauri `set_ignore_cursor_events` 是整窗开关，无 `forward` 模式 | §10.3 反向热区方案 |
| WebView2 透明窗口在部分显卡 / 远程桌面 / 关闭硬件加速时出现黑底 | `pet_set_opaque` 兜底开关 + 首次启动检测提示 |
| 高 DPI 下物理/逻辑坐标混淆 | 热区统一转物理坐标；`pet_get_screen` 返回 scale factor |
| 多显示器工作区、任务栏遮挡 | 落地边界取工作区而非屏幕全高 |
| Tauri 2 小版本 API 漂移（如 `Emitter` trait 拆分） | 变更集中在 `lib.rs`，首次 `cargo build` 按报错微调 |
| 打包时把 `src-tauri` / `dev-server.mjs` 一起打进去 | `frontendDist` 只指向 `../web` |

---

## 11. 开发计划与验收

| 阶段 | 产出 | 验收标准 | 依赖 |
|---|---|---|---|
| **P0 壳** | `tauri.conf.json` / `lib.rs` / `bridge.js` / `dev-server.mjs` / `index.html` | 透明无边框置顶；托盘可退出；只有角色上能点、其余点击穿透桌面；拖拽跟手；热区心跳超时 500 ms 内自动恢复穿透；Web 预览 `node dev-server.mjs` 可打开 | Rust（桌面模式）/ 仅 Node（预览模式） |
| **P1 渲染 + 基底** | `canvas-renderer.js` / `skin.json` + `manifest.json` + `motions/*.json` / `clock.js` / `easing.js` / `motion.js` | 呼吸 + 视线倾斜连续 5 分钟无跳帧/硬切；静止 CPU < 1%；改 `skin.json` 热重载生效；透明素材边缘干净、无棋盘格残留；双脚完整可见、窗口底边与脚底对齐；10 个特效、6 个表情、9 个道具与 12 个整身姿态可独立唤起；62 个动作均可加载；眨眼时眼睛不漂移；道具同组互斥且可用面板手动锁定；待机 30 s 内至少自动出现 2 次整身姿态变化，且结束时正确回落站姿；状态各有专属动作 | P0 |
| **P2 行为层** | `compositor.js` / `state-machine.js` / `signals.js` | 新增一个 motion JSON **不改任何代码**即可触发；优先级与打断符合 §7.2 表；交叉淡化无硬切；`sleep` 完全静默 | P1 |
| **P3 物理与手感** | `physics.js` / 拖拽与抛掷动作 / 随机微动作池 | 松手后重力下落 + 两次回弹；5 分钟内至少出现 5 种随机微动作；抛掷手感可调参；落地不超过工作区底边 | P2 |
| **P4 打磨与交付** | 设置面板 / 托盘菜单 / 开机自启 / 多皮肤切换 / 外部 `skins/` 与 `pet_list_skins` `pet_read_skin` / `classic` 皮肤配置 / README | 包体 ≤ 15 MB、常驻内存 ≤ 150 MB（实测记录）；DPI 100%/150% 下无错位；多屏下不越界；文档齐备 | P3 |

### 验证策略

- **单测**：`node --test tests/*.test.mjs`（Node 内置、零依赖），只覆盖行为层纯函数与皮肤包 JSON 契约。`package.json` 的 `test` 脚本用显式文件列表，兼容 Windows cmd 不展开 glob 的情况。
- **语法**：`node --check` 遍历 `web/src/**/*.js`。
- **UI**：浏览器手动验收清单（上表「验收标准」列），不引入浏览器自动化。
- **Rust**：`cargo check`（需工具链环境）。
- **约定**：沿用项目既有策略，**不跑无关全量测试**；改动哪个模块就验哪个模块。

---

## 12. 开放项（待拍板；加粗项为建议默认值）

| # | 决策 | 选项 | 影响 |
|---|---|---|---|
| 1 | 默认形象 | ✅ **已定稿：二头身 Q 版桌宠（stepboy）**，透明素材、62 个动作、10 个特效、9 个道具贴纸、6 个表情覆盖层与 12 个整身姿态已落盘，见 §6.0；立绘版保留为 `classic` 备用 | 无需再决策；正式名称可随时改 |
| 2 | 系统联动范围 | **打字节奏 + 全局空闲 + 指针靠近（默认）** / 再加构建状态 / 再加余额分档 | 增加可选信号源与设置项，不改变架构 |
| 3 | AI 对话 | **本期不做、仅预留 `pet_emit_signal` 接口（默认）** / 现在就接 | 若现在接，需放宽 §2.4 的零网络边界并重做隐私设计 |
| 4 | 项目落点 | **`dcdr-agent/desktop-pet/` 子目录（默认）** / 另开独立仓库 | 子目录可整体搬走，不污染现有 Java 工程 |
| 5 | 宠物数量 | **单只（默认）** / 多开 | 多开需引入窗口实例管理 + 共享帧解码，复杂度显著上升 |

> 第 1 项已定稿，其余按默认值即可直接进入 P0；如需改动，改的是配置与素材，不动 §3 / §5 的框架定稿。

---

## 13. 风险登记

| 风险 | 等级 | 影响 | 缓解 |
|---|---|---|---|
| 本机无 Rust 工具链、crates.io 不可达 | 高 | 壳无法在本地编译验证 | Web 预览模式独立可验；Rust 源码集中交付，首次构建在有网环境完成并据报错微调 |
| npm registry 不可达 | 中 | 拉不到 Tauri CLI | 前端零依赖照常运行；CLI 走 `npx` 或预置二进制 |
| WebView2 透明黑底 | 中 | 部分机器显示异常 | `pet_set_opaque` 兜底 + 启动自检提示 |
| Tauri 2 API 版本漂移 | 中 | Rust 编译报错 | 锁定 `tauri = "2"`，变更点集中在 `lib.rs` |
| 内存超预算 | 中 | 不满足「轻量」 | P4 实测校准；必要时砍掉 DOM 气泡或降低静止帧率 |
| 形象授权 | 中（合规） | 公开分发需核对生成平台条款 | 原图与 Q 版均为 AI 生成 / 衍生、已去水印，仅本地个人使用；禁用第三方 CC BY-NC-SA 素材；发布前分别复核 |
| Q 版形象一致性 | 低 | 后续新增姿态可能与当前形象有偏差 | 所有衍生图以 `chibi-matted-raw.png` 为参考；新增部件先用小图核对特征再合入 |
| 第三方「大肥鱼」插件索要高权限 | 低（本项目不引入） | 隐私 | 本项目不读取会话记录、不装全局钩子、不联网 |

---

## 附录 A 术语

| 术语 | 含义 |
|---|---|
| 部件（part） | 角色的独立图层素材；当前共 26 个 = 1 个主部件 `body` + 10 个特效 + 9 个道具 + 6 个表情覆盖层 |
| 表情覆盖层 | 与原图同坐标同尺寸的透明 SVG，盖住原眼珠并重画表情；靠 `alpha` 唤起、靠 `follow` 跟随主部件运动 |
| follow | 部件字段，继承指定部件的 `dx/dy/rot/sx/sy`，不继承 `alpha` |
| 气泡台词（line） | 动作 JSON 的可选字段，仅在 event 层动作触发时显示，用于给动作加语义 |
| 道具贴纸（prop） | 贴在角色身前的实体物件 SVG（电脑 / 书 / 咖啡等），`group = 'prop'`，同一时刻只显示一个 |
| 互斥组（group） | 部件字段；同组部件每帧按「alpha 增量 × gain × 层级权重」仲裁出唯一可见者 |
| holdProp | 合成器方法，手动锁定一个道具贴纸常显；传 `null` 恢复按动作自动切换 |
| 关键帧（track） | 某部件按时间变化的一组 transform 采样点 |
| 叠加层（layer） | base / micro / event / physics 四层之一 |
| 热区（hotspot） | 前端上报的可点击矩形，用于反向实现光标穿透 |
| 交叉淡化（gain） | 动作进入/退出时 0↔1 的权重，避免硬切 |
| 皮肤包（skin pack） | 一个角色所需的全部外置资源目录 |
| pivot 点 | `part.x/y` 所定位的部件锚点，由 `part.pivot` 归一化坐标决定，非部件中心 |
| alpha 增量 | 动作关键帧对部件基础透明度的加减量，求和后钳制到 [0,1] |
| 内置 / 外部皮肤 | 内置随包只读；外部 `skins/`（exe 同级）可由用户增删，见 §6.8 |
| 姿态替身（pose） | 一整张透明全身立绘，播放时与默认 `body` 交叉淡化，实现「整只角色换姿势」；同一 `group: 'pose'` 内互斥 |
| 姿态巡演 | idle 时按 `pools.pose` 与 `poseMinGapMs/MaxGapMs` 随机播放整身姿态动作，见 §7.3 |

## 附录 B 参考

| 来源 | 用途 |
|---|---|
| `github.com/PC2005-cloud/dsh-pet` | 桌宠交互、状态联动、角色包结构的对标 |
| `github.com/MerZlin/dsh-pet-indesktop` | 独立桌面版形态与热加载角色包做法 |
| `dshpluginhub.ai/zh/plugins/dsh-pet` | 授权说明：代码 MIT、素材禁止商用、公开二创须署名 |
| 大肥鱼梗梳理（36 氪 / 极客公园等公开报道，2026-10～11） | 背景：识图乌龙、鲸鱼娘二创、DSH 开源与「鱼灾」 |
| 用户提供的形象参考图 `assets/reference/original.jpg`（2026-11-12） | 本项目桌宠形象来源；AI 生成插画，已抠图去水印 |
| ImageGen 二次生成 + matting（2026-11-12，v0.3） | 二头身 Q 版桌宠形象的生产链路与产物，见 §6.0 |
| 用户提供的坐姿透明图 `assets/reference/body-sit-source.png`（2026-11-12 13:34） | v0.10 替换 `pose_sit`；281×542 真透明，与 2x 画布尺寸完全一致 |
| ImageGen 以 `body.png` 为参考生成 6 张新姿态（2026-11-12 14:1x） | v0.11 新增 6 套整身立绘；白底生成 → `tools/sprite_prep.py` 泛洪抠图 + 头围校正 + 底边对齐，原图在 `assets/generated/poses/` |
| Tauri 2 窗口 / 托盘 / `set_ignore_cursor_events` 文档 | 平台壳能力边界 |
