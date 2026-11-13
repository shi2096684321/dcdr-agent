# Stepboy 桌面宠物

轻量、配置驱动的 Windows 桌宠。设计定稿见 [`DESIGN.md`](./DESIGN.md) v0.11，本文件是运行与二次开发说明。

## 快速开始

项目**零构建、零运行时依赖**：`web/` 目录本身就是 Tauri 的 `frontendDist`，浏览器直接以原生 ESM 加载。

### 1. Web 预览（只需 Node 22，无需 Rust）

```bash
npm run preview    # 起服务并自动打开浏览器（推荐）
npm run dev        # 只起服务，手动开 http://localhost:5180（给 tauri:dev 当前置用）
```

Windows 上也可以**直接双击项目根目录的 `预览桌宠.bat`**，效果等同 `npm run preview`；关掉那个黑窗口即停止服务。

> **端口为什么是 5180 而不是 5173**：本项目放在 `dcdr-agent` 仓库下，那边的 `frontend/` 是 Vite 项目、默认同样占 5173。两边同开会撞车，而且 Windows 上 `localhost` 优先解析成 IPv6 `[::1]`——Vite 只监听 IPv6、本服务曾经只监听 IPv4，结果就是浏览器打开 `localhost:5173` 命中你的前端项目而不是桌宠，两边都"看起来正常"却互相串台。所以桌宠固定用 5180，并且**同时绑定 `127.0.0.1` 和 `::1`**，两个回环地址都能访问（不暴露到局域网）。要换端口：`PORT=5181 npm run dev`，同时改 `src-tauri/tauri.conf.json` 的 `devUrl`。

预览模式下窗口透明/置顶/穿透/托盘不可用，但动画、状态机、拖拽、皮肤热重载、设置面板都能验证。右键角色可打开设置面板。

### 2. Tauri 桌面版

前置：Rust 工具链、`@tauri-apps/cli@2`、WebView2（Win10/11 一般自带）、**MSVC 链接器 + Windows SDK**。

```bash
npm run tauri:dev      # 开发：先起 dev-server（5180），再由 Tauri 加载
npm run pack           # 打包：前端内嵌进 exe，输出 dist/Stepboy.exe（推荐，见 §4）
```

> `npm run tauri:build`（走 `npx @tauri-apps/cli@2`）也能出包，但需要联网现拉 CLI，且会自己处理特征开关。本机验证过的是 `npm run pack`，它直接用 cargo，不依赖网络。

#### 本机编译环境（未安装 Visual Studio）

本机原本没有任何 VS 组件，直接用 `winget` 装 VS Build Tools 会因微软 CDN 单连接限速（约 60 KB/s）耗时数小时。因此改为**按需手动落盘**：从 VS channel manifest 解析出精确依赖（15 个包 / 256 个文件 / 536 MB），用多线程分片下载后解压，装到 `C:\BuildTools`，全程不需要管理员权限也不需要 VS 安装器。

| 位置 | 内容 |
|---|---|
| `C:\BuildTools\VC\Tools\MSVC\14.44.35207\` | `cl.exe` / `link.exe` / `lib.exe` / CRT 头与库（x64） |
| `C:\BuildTools\SDK\lib\x64\` | Windows API 导入库 331 个 + `ucrt.lib` / `msvcrt.lib` / `libucrt.lib` / `legacy_stdio_definitions.lib` |
| `C:\BuildTools\SDK\nuget\c\Include\10.0.19041.0\` | SDK 头文件（`um` / `shared` / `ucrt` / `winrt`） |
| `C:\BuildTools\SDK\bin\` | `rc.exe`（资源编译器 / 来自 `Microsoft.Windows.SDK.BuildTools` NuGet 包） |

项目根目录的 [`.cargo/config.toml`](./.cargo/config.toml) 把上述路径接进 cargo：指定 `linker`、三条 `/LIBPATH`、`INCLUDE` / `LIB` 环境变量，并开启 `+crt-static`（静态链接 CRT，exe 不依赖 VC 运行库）。

编译时把工具链目录放到 PATH 最前（**必须**，否则 Git 自带的 coreutils `link.exe` 会被误用）：

```bash
export PATH="/c/BuildTools/SDK/bin:/c/BuildTools/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64:$PATH"
cd src-tauri

# 开发版：走 devUrl，需要先起 dev-server，改前端即时生效
cargo build --offline

# 正式版：前端内嵌进 exe，双击即可运行，不依赖任何服务
cargo build --release --features custom-protocol --offline
```

产物分别在 `src-tauri/target/{debug,release}/stepboy-pet.exe`。release 版在体积优化配置下是 **5.5 MB**（未优化时 12 MB；`[profile.release]` 里 `opt-level="z"` + `lto` + `codegen-units=1` + `strip` + `panic="abort"`）。

> **必须带 `--features custom-protocol`**，这是 Tauri 的打包标记。漏了它 `cargo build --release` 也会按 dev 模式去连 `devUrl`，双击后只会看到「无法访问此页面 / 127.0.0.1」。`npm run tauri:build` 由 CLI 自动加，手工用 cargo 时要自己写。
>
> **磁盘**：`target/` 编译缓存约 3 GB（release；`opt-level=z` + LTO 会完整重编依赖树，首次约 15 分钟，之后增量 10 秒内）。E 盘空间紧张时用 `CARGO_TARGET_DIR=C:/cargo-build-stepboy` 把缓存放 C 盘。


> **如果以后装了正式的 VS Build Tools**，直接删掉 `.cargo/config.toml` 即可回到默认行为。
>
> **四个坑**（都已绕开，记录备查）：
> 1. VS manifest 里的 `size` 字段**不可信**（含签名区，比真实文件大几 KB~1 MB），必须用 `sha256` 校验。
> 2. 微软 CDN **单连接限速约 60 KB/s，但多连接可叠加**（16 并发实测 2 MB/s，10 倍以上）。
> 3. `cl.exe` 缺 `clui.dll` 会报 `C1510: Cannot load language resource`，需单独补 `CRT.Headers` / `Res.base.enu` 包。
> 4. `tauri-winres` 找不到 `RC.EXE` 会 panic，NuGet 的 `Microsoft.Windows.SDK.BuildTools` 里带官方 x64 `rc.exe`。


### 3. 测试与语法检查

```bash
npm test                                  # 显式运行两个测试文件，bash / cmd 均可
node --test tests/*.test.mjs              # bash 下也可直接按文档写法运行
node --check web/src/main.js              # 逐个文件语法检查
```

单测只覆盖行为层纯函数与皮肤包 JSON 契约，不引入测试框架、不跑浏览器自动化。

### 4. 一键打包

```bash
npm run pack          # 构建 release 并把 exe 输出到 dist/
npm run pack -- --out "D:\桌面宠物"   # 输出到自定义目录
```

脚本 `scripts/pack.mjs` 自动处理三件容易出错的事：把 MSVC / `rc.exe` 加到 PATH 最前、带上 `--features custom-protocol`、把编译缓存指向 `C:\cargo-build-stepboy`（避免撑爆项目所在盘）。

**注意**：exe 约 5.5 MB。脚本会先检查目标目录可用空间，**放得下就直接输出 `dist/Stepboy.exe`（当前就是这个分支）**；放不下才降级为「exe 存到 `%LOCALAPPDATA%\Stepboy\` + `dist/` 放个 `启动桌宠.bat` 入口」。降级只是为了在磁盘写满时也能交付，正常情况看不到。

## 在 IDEA 里开发

### 打开项目

1. IDEA 装 **Rust 插件**（Settings → Plugins → 搜 Rust）。
2. `File → Open` 选 **`src-tauri/Cargo.toml`**，选择 *Open as Project*（或 Attach to 现有项目）。这样 Rust 侧有补全、跳转、Cargo 面板。
3. `web/` 目录用 IDEA 自己的 JS 支持即可——它是原生 ESM，**没有构建步骤**，改完文件刷新浏览器就生效。

### 三类改动的开发方式

| 改什么 | 怎么跑 | 是否要编译 |
|---|---|---|
| 动作 / 皮肤 / 台词（JSON） | `npm run dev` 开预览，改完刷新页面 | 不用 |
| 行为逻辑 / 渲染（`web/src/**`） | 同上，浏览器 F12 断点调试 | 不用 |
| 窗口 / 托盘 / 穿透（`src-tauri/**`） | 见下方 Run Configuration | 要 |

### 跑桌面版（Rust 改动）

Rust 侧依赖 MSVC 与 `rc.exe` 在 PATH 里，`.cargo/config.toml` 只负责 linker 和 INCLUDE/LIB，**不负责 PATH**。所以要在 Run Configuration 里设环境变量：

- `Run → Edit Configurations → + → Cargo`
- Command：`run`
- Working directory：`src-tauri`
- Environment variables 加上（分号分隔，不要换行）：

```
PATH=C:\BuildTools\SDK\bin;C:\BuildTools\VC\Tools\MSVC\14.44.35207\bin\Hostx64\x64;%PATH%
CARGO_TARGET_DIR=C:\cargo-build-stepboy
```

前提是 dev-server 已启动（`npm run dev`），否则窗口会空白——调试版走 `devUrl`。

### 出产物（打一个能双击的 exe）

开发时跑的是 `devUrl` 模式（窗口去连 `127.0.0.1:5180`），**改完直接用这个窗口拷给别人是打不开的**。要出真产物，核心只有一件事：**带 `--features custom-protocol` 编译 release，让前端资源内嵌进 exe**。

IDEA 里有三条路，按省事程度排序：

#### 路线 A：IDEA 自带 Terminal（推荐）

窗口底部 `Terminal`（`Alt+F12`），项目根目录执行：

```bash
npm run pack
```

完事。不用 `npm install`（项目没有任何 npm 依赖，`package.json` 里只有 scripts），装过 Node 就能跑。脚本内部会：把 MSVC / `rc.exe` 塞到 PATH 最前 → `cargo build --release --features custom-protocol` → 把产物拷到 `dist/Stepboy.exe`。

#### 路线 B：做成 IDEA 里的 Run Configuration（想点绿三角就跑）

`Run → Edit Configurations → + → Cargo`：

| 字段 | 值 |
|---|---|
| Command | `build` |
| Working directory | `src-tauri` |
| Additional arguments | `--release --features custom-protocol --offline` |
| Environment variables | `PATH=C:\BuildTools\SDK\bin;C:\BuildTools\VC\Tools\MSVC\14.44.35207\bin\Hostx64\x64;%PATH%` `CARGO_TARGET_DIR=C:\cargo-build-stepboy` |

点运行后，产物在：**`C:\cargo-build-stepboy\release\stepboy-pet.exe`**（因为设了 `CARGO_TARGET_DIR`）。想改名成 `Stepboy.exe` 就手动复制一份改名——改名不影响运行，Tauri 不校验文件名。

> 不想写 `CARGO_TARGET_DIR` 也行，那产物就在 `src-tauri/target/release/stepboy-pet.exe`，代价是 E 盘要多占 3 GB 编译缓存。

#### 路线 C：IDEA 里点 Node 脚本

`Run → Edit Configurations → + → Node.js`，JavaScript file 选 `scripts/pack.mjs`，working directory 填项目根。等价于路线 A，好处是能把「停 dev-server → 打包 → 起 exe」串成一条。

#### 三条路的产物对照

| 走法 | 产物位置 | 是否含前端 | 能否直接双击 |
|---|---|---|---|
| `npm run tauri:dev` / IDEA 里 Cargo `run` | `src-tauri/target/debug/stepboy-pet.exe` | ❌ 要 dev-server | ❌ 会白屏 |
| `cargo build --release`（**漏了 features**） | `.../release/stepboy-pet.exe` | ❌ 要 dev-server | ❌ 显示「无法访问此页面」 |
| 路线 A / B / C（**带 features**） | `dist/Stepboy.exe` 或 `CARGO_TARGET_DIR/release/stepboy-pet.exe` | ✅ 已内嵌 | ✅ |

#### 交付给别人时

只需要 **`dist/Stepboy.exe` 这一个文件**，不用带 `web/`、`assets/`、`node_modules`。目标机器要求：Win10 1803+ / Win11（自带 WebView2；老系统装一次 [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/)）。

首次启动若被 SmartScreen 拦（未签名程序的默认行为）：`更多信息 → 仍要运行`。要彻底消掉得买代码签名证书，个人项目一般不做。

> **改完前端一定要重新打包**。`assets/skins/**` 和 `web/**` 是在编译期被内嵌的，不是运行时读盘——所以 exe 里跑的是打包那一刻的快照。想让皮肤可外部替换，见文末「皮肤扩展」。


## 目录

```
desktop-pet/
├── DESIGN.md                    开发设计文档（v0.11）
├── package.json / jsconfig.json
├── dev-server.mjs               零依赖静态服务器（皮肤包 / src / index.html 均 no-store）
├── web/                         Tauri frontendDist
│   ├── index.html / src/style.css
│   ├── src/main.js              装配与主循环
│   ├── src/core/                clock easing motion compositor state-machine physics signals
│   ├── src/render/              renderer.js（接口约定）/ canvas-renderer.js
│   ├── src/platform/bridge.js   Tauri IPC 封装 + Web 兜底
│   └── assets/skins/
│       ├── index.json
│       ├── stepboy/             默认：二头身 Q 版（manifest / skin / 62 个动作 / 51 个素材，含 12 张整身姿态立绘）
│       └── classic/             备用：原插画立绘（复用动作 / 特效 / 道具；无姿态替身，故不参与姿态巡演）
├── src-tauri/                   Rust 壳：窗口、托盘、10 个 IPC 命令、光标穿透轮询
│   └── icons/
│       ├── icon.ico             多尺寸图标（256/128/64/48/32/24/16），构建产物
│       └── source/              SVG 源：logo-full.svg（大尺寸）/ logo-small.svg（16·24 专用）
├── assets/                      原图、抠图母版、生成原图、透明预览（不参与运行）
├── tools/
│   ├── sprite_prep.py           贴纸抠图 / 头围校正 / 底边对齐
│   ├── dewhite.py               立绘去白边（白底抠图残留）
│   └── make-icon.py             SVG → 多尺寸 ico
└── tests/                       行为层与契约单测
```

## 换皮与加动作（零代码）

| 想做的事 | 操作 |
|---|---|
| 调动作手感 | 改 `motions/*.json` 的 `duration` / `easing` / 关键帧，保存后把 `manifest.json` 的 `version` +1 |
| 加一个动作 | 新建 `motions/xxx.json` → 加进 `motions/index.json` → `version` +1 |
| 加一个情绪特效 | 往 `parts/` 丢一个 64×64 SVG → `skin.json` 登记（`alpha: 0`）→ 动作里用 `alpha` 增量唤起 |
| 改气泡台词 | 改动作的 `line` 字段（仅 event 层动作会冒气泡，micro 层故意静默） |
| 加一种表情 | 往 `parts/` 丢一个 263×520 的同坐标 SVG → `skin.json` 登记（`alpha: 0` + `follow: 'body'`）→ 动作里写 `alpha` 增量 |
| 加一个道具贴纸 | 往 `parts/` 丢一个 64×64 SVG → `skin.json` 登记（`alpha: 0` + `group: 'prop'` + `follow: 'body'`）→ 动作里写 `alpha` 增量；同组自动互斥 |
| 加一个整身姿态 | 往 `parts/` 丢一张透明全身立绘 → `skin.json` 登记（`alpha: 0` + `group: 'pose'` + `follow: 'body'` + 底边 pivot）→ 配一个让 `body` 淡出、替身淡入的动作 → 登记进 `pools.pose` |
| 改姿态频率 | 改 `manifest.json` 的 `poseMinGapMs` / `poseMaxGapMs`（默认 4–12 s），把 `pools.pose` 里不想要的姿态删掉即可 |
| 手动换贴纸 / 试动作 | 右键打开设置面板：「道具」下拉可锁定任意贴纸（选「自动」恢复按状态切换），「试动作」下拉可逐个试播全部 62 个动作（含 12 个整身姿态） |
| 接 Agent 状态 | 通过 `pet_emit_signal` 传 `thinking` / `working` / `coding` / `organizing` / `waiting` 等，映射见 `state-machine.js` 的 `AGENT_ACTION_MAP` |
| 换整套形象 | 新皮肤目录 + `skins/index.json` 登记 → 设置面板切换 |
| **调整桌宠大小** | 右键设置面板「大小」下拉（50%–200%，默认 60%），立即生效并记住；原理见「尺寸与缩放」 |
| **嫌晃 / 想更活泼** | 改 `breathe.json`（呼吸幅度 `sy` / `dy`）和 `sway.json`（重心摆动 `rot` / `dx`）。`sway` 里**相邻两帧数值相同 = 那段站住不动**，靠这个控制「摆一下停一会儿」的节奏；微动作密度改 `manifest.json` 的 `microMinGapMs` / `microMaxGapMs`，`wobble.json` 管左右摇摆的幅度 |
| 立即生效 | 设置面板「重载皮肤」；开发模式下每 2 s 自动轮询 `version` |

> 发布版的内置皮肤随包只读。要让用户自己增删皮肤，把皮肤目录放在 exe 同级的 `skins/`，由 `pet_list_skins` / `pet_read_skin` 读取（DESIGN.md §6.8）。

**关键契约**：`part.x/y` 定位的是 **pivot 点**（不是部件中心）；动作的 `alpha` 是**增量**，最终 `alpha = clamp(part.alpha + Σ 增量, 0, 1)`，所以特效 / 表情 / 道具 / 姿态替身基础值必须是 0；`follow` 让贴片继承主部件的位移/旋转/缩放（不继承 alpha），避免呼吸时漂移；`group` 相同的部件每帧只保留一个可见（仲裁按 `alpha 增量 × gain × 层级权重`），所以 9 件道具、12 个姿态不会一起冒出来。整身姿态动作让 `body` 的 alpha 走 `0 → -1 → 0`、替身走 `0 → +1 → 0`，即两个身体交叉淡化。详见 `DESIGN.md` §6.3 / §6.0 / §7.1。

## 交互

- 待机 → 2 层常驻（呼吸 + 重心微摆）+ 19 种随机微动作（含眨眼、思考类，以及**看书 / 记录 / 喝咖啡 / 听歌**等道具类动作），1.2–3.8 秒换一次
- **整身姿态巡演** → 每 4–12 秒（首次 2.5 秒）从 **12 套整身立绘**里随机换：招手 / 欢呼 / 走路 / 坐下 / 侧卧睡 / 伸懒腰 / **用电脑写代码** / 托腮思考 / 捧书看 / 捧杯喝 / 叉腰赌气 / 趴桌睡，**整只角色换一张完整立绘**，配台词，动作结束自动回站姿（播放期间暂停随机微动作，避免贴片叠到坐姿上错位）；也可在「试动作」里逐个试播
- 点击角色 → 8 种 `poke` 动作随机，各带专属**表情**、特效与台词（笑眼 / 惊讶 / 生气 / 星星眼等）
- 按住拖动 → 挣扎动画，松手后重力下落 + 两次回弹
- 打字 → `type_tap` + 代码屏电脑 + 音符；指针靠近 → 视线倾斜；唤醒 → `wake_up` + 感叹号
- Agent 状态（外部信号注入）→ `thinking` 歪头配灯泡与便签 / `working` 循环处理配电脑 / **`coding` 写代码配代码屏电脑** / `organizing` 清单收拾 / `waiting` 咖啡等待 / `success` 跳起来撒星星 / `error` 报错屏排查；另有 `searching` 放大镜、`debugging` 排查扩展态
- **写代码三态**：`coding`（代码屏）→ `compile_error`（红屏 × + 汗滴）→ `fix_done`（绿屏 √ + 星星），同一台电脑换贴纸表达一次编码过程；在设置面板「试动作」里可逐个试播
- 余额过低 → `balance_low`（乌云 + 感叹号）
- 空闲 5 分钟 → 睡眠，完全静默，只冒梦话气泡
- 右键 → 设置面板（换皮肤、**锁定道具贴纸**、**试播任意动作**、**调整大小**、透明兜底、开机自启、重载）
- 托盘 → 显示/隐藏、置顶、穿透、不透明兜底、开机自启、切换皮肤、退出

## 边界与限制

- **Windows only**；macOS / Linux 未适配。
- **不联网、不读凭据、不读会话记录、不自动发帖**；`balance_value` 只是本地配置里的普通数字。
- **表情是可切换的**：角色底图是单张位图，眼睛原本画死在像素里；现用 6 个同尺寸透明 SVG 覆盖层在镜片内盖住原眼珠并重画（闭眼 / 笑眼 / 惊讶 / 生气 / 晕 / 星星眼），靠 `alpha` 唤起、`follow: 'body'` 跟随呼吸，所以能眨眼、能换表情。`assets/reference/faces-preview.png` 是六种表情对照图。
- **道具贴纸是独立的第三类部件**：9 个 64×64 SVG，锚在 `(100, 214)`（胸口到腹部），`z = 1` 不挡脸，同属 `group: 'prop'` 互斥组，同一帧只显示一个。包括三态笔记本电脑、书、便签本、咖啡、放大镜、清单板、耳机。`assets/reference/props-preview.png` 是九宫格构图预览。
- **道具只在 event 层使用**。它贴的是站姿的躯干中线，视觉上更像"浮在肚子上"而不是"拿在手里"；所以 micro 层（随机触发的 `listening` / `reading` / `focus` / `note_taking` / `thinking` / `break_time` / `coffee_break`）已经把道具去掉了——那些动作本身没有台词，突然冒出一个耳机没有任何上下文。这些动作仍保留身体动作、表情和特效（音符、灯泡、齿轮、Zzz）。`book` / `notebook` / `headphone` 三个贴纸作为备用素材留在 `skin.json` 里，但暂时没有动作引用。真正需要"在看书/在写代码"的表现，用整身姿态立绘（`pose_read` / `pose_coding`）效果远好于站姿贴道具。
- **整身姿态替身是第四类部件**：12 张完整透明立绘同属 `group: 'pose'`，与 `body` 同 `z = 0`、底边 pivot、`follow: 'body'`，播放时交叉淡化实现「整只角色换姿势」。你 13:34 提供的坐姿图（281×542 真透明）已替换 `pose_sit`，原版备份在 `assets/reference/pose_sit-v09.*`。本轮又按同一画风补了 6 张：用电脑写代码、托腮思考、捧书看、捧杯喝、叉腰赌气、趴桌睡（自带电脑/书/杯子/桌子，不再另贴小图标）。`assets/reference/pose-sheet.png` 是姿态对照图，`new-poses-transparent.png` 是新增 6 张的透明抠图校验。
- **整身姿态替身**已覆盖招手 / 走路 / 伸懒腰等大动作，但它们是整张图切换，不是局部肢体动画。真正连续的摆臂、走路循环仍需拆出独立手臂/腿部件（同样可用 `follow`）。
- `classic` 立绘皮肤没有登记姿态替身，主循环会自动跳过姿态巡演，不会出现「body 淡出却没人补位」的空窗；在该皮肤下手动试播 `pose_*` 仍会有短暂空窗，属已知取舍。
- Tauri 的 `set_ignore_cursor_events` 是整窗开关，没有 Electron 的 `forward` 模式。本项目用「前端每 100 ms 上报热区 + Rust 每 25 ms 轮询光标」实现像素热区级穿透，**心跳超时 500 ms 自动恢复穿透**，渲染层崩溃也不会锁死桌面。
- **Rust 工具链已就绪**（rustup + MSVC/SDK，见 §本机编译环境）。`cargo build --release --features custom-protocol` 已完整构建通过，产物 5.5 MB，实机启动验证过：窗口正常、角色完整渲染、WebView2 挂载、无 panic。首次全量编译约 15 分钟（LTO），增量 10 秒内。
- 透明窗口在部分显卡 / 远程桌面 / 关闭硬件加速时可能黑底，用托盘的「不透明兜底」开关。

## 尺寸与缩放

右键角色 → 设置面板 → **大小**：50% / 60% / 70% / 80% / 90% / 100% / 125% / 150% / 200%，切换立即生效，写在 `localStorage['stepboy.scale']`，下次启动沿用。**默认 60%**（200×300 的窗口在 1080p 上偏大，收两档）。

对应窗口尺寸：50% → 100×150，60% → 120×180，80% → 160×240，100% → 200×300，200% → 400×600。

> 改默认值时老用户会跟随，但**不会覆盖主动选择**：`localStorage['stepboy.scale.default']` 记着上次发布时的默认值，只有当前比例仍等于它才更新（说明用户没动过）。

> 位图密度的下限被夹在 1：缩小到 50% 时如果密度也跟着降到 0.5，位图会比原图还小、细节丢失；保持密度 ≥1 等效于超采样，缩下去反而更清晰。上限 4（scale 2 × 2 倍屏），再高离屏缓存的内存不划算。

实现上**逻辑坐标恒为 200×300 不变**，缩放只改三处：

| 改什么 | 以 150% 为例 |
|---|---|
| 位图密度 `canvas.width/height` | 200×300 → 300×450（再乘屏幕 DPR） |
| CSS 尺寸（舞台与画布） | 200×300 → 300×450 |
| 窗口尺寸（`pet_set_size` IPC） | 200×300 → 300×450 |

所以动作、物理、贴纸坐标、皮肤 JSON 全都**不用动**——它们写的都是逻辑坐标。

有两个地方必须跟着乘比例，漏了就会出现「看着放大了但点不到」：

1. `renderer.hitTest()` 收到的是 CSS 像素，要先除以比例再和 skin 里的坐标比较
2. 上报给 Rust 的热区要乘以比例，因为 Rust 是把屏幕光标反算成「窗口内逻辑坐标」的

窗口放大方向是**往上长**（底边不动，脚不动），并会夹在屏幕边界内，不会把角色顶出桌面。

两个随缩放变化的细节：

- **气泡**跟着缩（`--ui-scale` CSS 变量驱动），但字号设了 10px 下限，缩到 50% 也不至于看不清
- **设置面板不缩放**——它固定 184px 宽，缩到 90% 以下窗口就装不下了。所以打开面板时会**临时把窗口撑到至少 216×350**，关闭时再缩回按比例的真实尺寸。面板的热区按实际 `getBoundingClientRect()` 上报，否则撑大后边缘点不到

想改档位就编辑 `index.html` 的 `#size-select` 选项和 `main.js` 里的 `SCALE_MIN/SCALE_MAX/SCALE_DEFAULT`。

## 图标

### 设计逻辑

图标不是把立绘缩小，而是抽取角色**最强的三个识别符号**：蓬松黑发（含一撮呆毛）、细圆框眼镜、大眼微笑。底色沿用旧图标的蓝色（角色牛仔裤、手表也是浅蓝，不违和），圆角方块保证在深色和浅色任务栏上都立得住。

一个关键取舍：**16px 只用 256 格像素**，完整版的呆毛、刘海锯齿、腮红、耳朵在那个尺寸里全糊成一团，脸的面积也太小。所以做了两套图案：

| 尺寸 | 用哪套 | 差别 |
|---|---|---|
| 256 / 128 / 64 / 48 / 32 | `logo-full.svg` | 有呆毛、耳朵、腮红、发丝高光，刘海锯齿更密 |
| 24 / 16 | `logo-small.svg` | 去掉呆毛和所有细节，脸放大约 15%，眼睛填满镜片，白 T 加大 |

Windows 会自己从 exe 的图标资源里挑最匹配的帧，所以这两套会自动各就各位：托盘和任务栏走 32px 帧，资源管理器小图标视图走 16/24 帧。

### 重新生成

```bash
npm run icon        # 等价于 python tools/make-icon.py --preview
```

脚本会：用 Edge 的 headless 模式把两张 SVG 各渲染成 256px 透明母版 → 校验中心不透明、四角透明 → 降采样出 7 个尺寸 → 手工组装 ICO（因为 Pillow 只能对单张图统一缩放，没法让 16/24 用另一套图案）→ 输出 `src-tauri/icons/icon.ico`，同时刷新 `source/icon-preview.png` 对照图。

**打包会自动处理重新嵌入**。`npm run pack` 在构建前会比较文件时间：`web/` 比 exe 新就 touch `src/lib.rs`，`icons/icon.ico` 比 exe 新就 touch `build.rs`。两种资源走的是不同路径：

| 资源 | 嵌入时机 | 触发重编要动 |
|---|---|---|
| `web/**`（含皮肤 JSON、动作） | `src/lib.rs` 里 `generate_context!` 宏展开时 | `touch src-tauri/src/lib.rs` |
| `icons/icon.ico` | `build.rs` 里 `tauri-winres` 编译进 Win32 资源段 | `touch src-tauri/build.rs` |

> **手工构建时必须自己 touch**。`tauri-build` 只对配置文件和 capabilities 发 `rerun-if-changed`，不监听 `frontendDist` 目录也不监听 ico——不 touch 的话 cargo 一路显示 `Finished`（几秒），包里却还是旧资源，exe 时间戳都不变。这个坑实测踩过两次：一次是换图标后 exe 大小没变，一次是改了动画参数但画面没反应。判断方法：改完资源后构建如果只花几秒，基本就是没重编。

## 立绘去白边

立绘由白底图抠出，边缘会留一圈「实心白 + 数层柔化白」的描边，放在桌面壁纸上就是一圈发亮的白边。

```bash
python tools/dewhite.py               # 处理全部立绘
python tools/dewhite.py --dry-run     # 只看会剥多少，不改文件
python tools/dewhite.py --file pose_x.webp --layers 7
```

原理是**从外向内逐层剥离**：凡是从透明区摸得到的偏白像素就置透明，内侧随之暴露、继续剥，碰到角色的深色描边自然停止。角色有描边包住，剥不进主体；白 T 恤、眼白这些被描边围住的白色也不受影响。

两个实测出来的坑：

- **别用「反混合」**：白边是 alpha=255 的实心白，反混合对它无效；而简单腐蚀会把角色削瘦、啃掉发丝。
- **`--layers` 要按图调**：画面里有白色道具时（`pose_desk_sleep` 的咖啡杯），道具和「白边」在像素上都是「贴着透明区的白」，剥太多会把道具一起剥掉。实测咖啡杯用 7 层刚好（白边干净、杯子还在），默认 18 层会剥没。
- **保存必须 `alpha_quality=100`**：WebP 有损压缩会在「透明/不透明」交界重新生成半透明噪点，刚剥干净的白边又被压回来。

## 素材与授权

形象来自用户提供的 AI 生成插画，经二头身 Q 版重绘、抠图去水印，仅用于本地个人桌宠。不引入 DeepSeek Logo、鲸鱼娘、溟月或任何 CC BY-NC-SA 素材。公开发布或商用前，需按各生成平台条款分别核对权利。

- 原图：`assets/reference/original.jpg`
- Q 版生成原图 / 抠图母版：`assets/generated/`
- 棋盘格透明预览：`assets/reference/preview-chibi.png`
- 道具构图预览：`assets/reference/props-preview.png`
- 十二姿态对照 / 用户坐姿原图：`assets/reference/pose-sheet.png`、`assets/reference/body-sit-source.png`
- 本轮新增 6 张生成原图 / 抠图母版 / 透明校验：`assets/generated/poses/`、`assets/reference/new-poses-sheet.png`、`assets/reference/new-poses-transparent.png`
- 运行时只加载 `*.webp` + `*.svg`（约 470 KB）；`*.png` 是 2x 母版与兼容备份（约 2.3 MB），不参与运行，发布时可剔除
