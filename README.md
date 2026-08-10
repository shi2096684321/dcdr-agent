# dcdr-agent

基于 Spring Boot + Vue 3 的开源智能体（Agent）平台，支持流式对话、文生图创作，以及对多厂商大模型的统一编排与工具调用（Function Calling）。

当前版本：**v1.1.0**（发行说明见 [`docs/RELEASE-v1.1.0.md`](docs/RELEASE-v1.1.0.md)）。

> 版本说明：v1.1.0 新增文生视频、联网搜索（web_search）；语音 STT/TTS 为未来可扩展项，详见 [`docs/05-语言对话模块（未来可扩展项）.md`](docs/05-语言对话模块（未来可扩展项）.md)。

## ✨ 特性

- **流式对话**：SSE 实时输出，推理（CoT）内容单独展示；支持消息编辑 / 重试 / 截断与对话过程中断（stop）。
- **上下文保护**：上下文长度估算与超长提醒，避免超出模型上下文窗口。
- **多模态**：对话内文生图（ChatImageService）、图片识别与视觉模型自动路由问答。
- **创作模式**：文生图项目化管理、异步生成任务、批量生成与参数套用。
- **文生视频**：创作模式下的视频子模式，异步任务编排与状态轮询。
- **联网搜索**：内置 `web_search` 工具（经 MCP 连接外部 Server），本轮对话内对重复调用做去重拦截。
- **工具调用（Function Calling）**：内置 `calculator` / `time` / `weather` / `image_generation` / `web_search`，统一工具框架，结果流式回传并落库。
- **多模型适配**：SPI 风格 Provider 抽象，已内置 OpenAI 兼容系、通义千问、DeepSeek、智谱（Zhipu），按 UI 模式 / 模型类型自动路由。

## 🧱 软件架构

项目采用前后端分离架构：后端为 Java（Spring Boot），前端为 Vue 3 + Vite + Element Plus。

##### 前端目录结构

前端源码位于 `frontend/` 目录，遵循「视图 / 组件 / 逻辑 / 接口」分层组织，路径别名 `@` 指向 `frontend/src`。

```
frontend/
├── public/                      # 静态资源(不经过打包, 直接由 Vite 原样输出)
│   └── dcdr-logo.png            # index.html 的 favicon 直接引用
├── src/
│   ├── api/                     # 后端接口封装层
│   │   ├── http.js              #   axios 实例 + 响应数据解包工具(unwrap)
│   │   ├── stream.js            #   SSE 流式读取 + AbortController + 空闲超时 + stopChat 中断
│   │   ├── chat.js              #   对话相关: 会话/消息 CRUD、streamChat、streamChatImage
│   │   ├── image.js             #   创作相关: 项目 CRUD、图片生成任务提交/轮询/历史
│   │   ├── system.js            #   系统级: 模型列表、线程池状态、文件上传
│   │   └── index.js             #   barrel 聚合导出, 兼容 `import { xxx } from '@/api'`
│   ├── assets/                  # 需要 import 打包的静态资源
│   │   └── dcdr-logo.png        #   组件内 <img :src="..."> 引用的 logo
│   ├── components/              # 纯展示 / 可复用组件(不含业务状态)
│   │   ├── AppHeader.vue        #   顶部栏: logo + 模式切换 + 模型选择
│   │   ├── MessageBubble.vue    #   单条消息气泡: 图片/视频/推理/编辑态/操作按钮
│   │   ├── ImagePreviewModal.vue#   图片全屏预览弹窗(v-model:url)
│   │   └── GenerationDetailDialog.vue  # 创作模式图片详情弹窗(含参数套用)
│   ├── composables/             # Vue 组合式函数(可复用逻辑)
│   │   └── useMarkdown.js        #   共享 markdown-it 实例 + renderMarkdown
│   ├── stores/                  # Pinia 状态管理(已注册, 预留)
│   ├── styles/                  # 全局样式
│   │   ├── base.css              #   基础重置
│   │   ├── gradients.css        #   .gradient-bg/green/orange 工具类
│   │   └── index.css            #   入口, 由 main.js 引入
│   ├── utils/                    # 通用工具函数
│   │   └── format.js             #   formatDuration / formatDate / styleLabel
│   ├── views/                   # 页面级组件(对应一个模式/路由)
│   │   ├── ChatView.vue          #   对话模式: 侧边栏 + 消息列表 + 流式输出 + 输入区
│   │   └── CreativeView.vue      #   创作模式: 项目侧边栏 + 控制面板 + 预览区
│   ├── App.vue                   # 根组件: 布局 + <AppHeader> + 视图编排
│   └── main.js                   # 入口: 注册 Pinia / ElementPlus, 引入 styles
├── index.html
├── vite.config.js               # Vite 配置(含 @ 别名与 /api 代理到 8080)
└── package.json
```

分层约定：
- `views/` 放页面级组件，持有业务状态与编排逻辑；`components/` 放无业务的纯展示组件。
- `api/` 按业务域拆分(chat/image/system)，公共能力(http/stream)单独抽出；`index.js` 聚合导出保证向后兼容。
- 全局可复用样式放 `styles/`，组件内专属样式用 `<style scoped>` 隔离。

##### 后端目录结构

后端源码位于 `src/main/` 下，包根为 `com.dcdr.agent`，采用「控制器 → 编排/服务 → 模型 & 工具 → 仓储」分层。

```
src/main/
├── java/com/dcdr/agent/
│   ├── JavaAgentApplication.java   # Spring Boot 启动类
│   ├── agent/                      # 智能体核心层
│   │   ├── AgentOrchestrator.java  #   编排器: 对话流转 / 工具调用 / SSE 流式输出
│   │   ├── ActiveChatRegistry.java #   活跃会话注册表, 支持中断(stop)信号下发
│   │   ├── ModelRouter.java        #   模型路由: 按 uiMode/type 选择 provider
│   │   ├── ImageResolver.java      #   图片 URL → 可访问地址 解析
│   │   ├── creative/               #   创作模式引擎
│   │   │   ├── CreativeEngine.java #     异步图片/视频生成编排(线程池 + 任务状态)
│   │   │   ├── ImageGenerationWorker.java  # 单张图片生成 worker
│   │   │   └── VideoGenerationWorker.java  # 视频生成 worker
│   │   ├── image/
│   │   │   └── ChatImageService.java  # 对话内文生图(走 SSE)
│   │   ├── memory/                 #   上下文记忆
│   │   │   ├── ChatMemory.java        #     记忆接口
│   │   │   ├── InMemoryChatMemory.java #  内存实现
│   │   │   ├── Message.java           #     消息载体
│   │   │   └── TokenEstimator.java    #     token 估算(上下文告警用)
│   │   └── session/                #   会话/项目管理
│   │       ├── SessionManager.java
│   │       └── ProjectManager.java
│   ├── common/                     # 公共组件
│   │   ├── Result.java             #   统一响应封装(code/data/message)
│   │   ├── constant/               #   枚举常量
│   │   │   ├── MessageType.java
│   │   │   ├── ModelType.java      #     CHAT / IMAGE / VIDEO
│   │   │   └── UIMode.java         #     chat / creative
│   │   ├── exception/              #   异常处理
│   │   │   ├── BusinessException.java
│   │   │   └── GlobalExceptionHandler.java  # 全局异常 → Result
│   │   └── util/                   #   存储工具
│   │       ├── GeneratedImageStorage.java   # 生成图落盘
│   │       └── ReferImageStorage.java       # 参考图存储
│   ├── config/                     # Spring 配置
│   │   ├── AgentProperties.java    #   agent 相关配置项
│   │   ├── AsyncConfig.java        #   异步线程池(图片生成)
│   │   ├── CorsConfig.java         #   跨域
│   │   ├── ModelAutoConfiguration.java    # 模型 provider 装配
│   │   ├── OpenApiConfig.java      #   OpenAPI/Swagger 文档
│   │   ├── ToolAutoConfiguration.java     # 工具装配
│   │   └── ToolsProperties.java    #   工具开关配置
│   ├── controller/                 # REST 控制器(对前端 /api/*)
│   │   ├── ChatController.java     #   /api/chat/* (流式对话 / 文生图 / stop)
│   │   ├── SessionController.java  #   /api/sessions + /api/sessions/{id}/messages
│   │   ├── ProjectController.java  #   /api/projects
│   │   ├── GenerationController.java   # /api/generations
│   │   ├── ImageController.java    #   /api/images/generate
│   │   ├── VideoController.java     #   /api/videos/generate (文生视频异步任务)
│   │   ├── FileController.java     #   /api/files/upload
│   │   ├── ModelController.java    #   /api/models
│   │   └── SystemController.java   #   /api/system/thread-pool
│   ├── dto/                        # 数据传输对象(请求/响应)
│   │   ├── ChatRequest.java / ChatResponse.java / ChatChunk.java  # 对话
│   │   ├── ChatImageRequest.java   #   对话内文生图请求
│   │   ├── ImageRequest.java / ImageResponse.java / VideoRequest.java
│   │   ├── SessionDTO.java / MessageDTO.java / ProjectDTO.java / GenerationDTO.java
│   │   ├── ModelInfo.java          #   模型信息(前端选择器用)
│   │   └── ThreadPoolStatsDTO.java
│   ├── entity/                     # JPA 持久化实体
│   │   ├── SessionEntity.java      #   会话
│   │   ├── MessageEntity.java      #   消息(含 seq, 支持编辑/重试截断)
│   │   ├── ProjectEntity.java      #   创作项目
│   │   └── GenerationEntity.java   #   图片生成记录
│   ├── model/                      # 模型 Provider(SPI 风格)
│   │   ├── provider/               #   接口定义
│   │   │   ├── ChatModelProvider.java
│   │   │   ├── ImageModelProvider.java
│   │   │   └── VideoModelProvider.java
│   │   ├── registry/
│   │   │   └── ModelRegistry.java  #   按名称查询 provider
│   │   ├── openai/                 #   OpenAI 兼容系
│   │   │   ├── OpenAiChatProvider.java
│   │   │   ├── OpenAiCompatibleChatProvider.java  # 兼容第三方
│   │   │   └── OpenAiImageProvider.java
│   │   ├── qwen/                   #   通义千问
│   │   │   ├── QwenChatProvider.java
│   │   │   └── QwenImageProvider.java
│   │   ├── deepseek/
│   │   │   └── DeepseekChatProvider.java
│   │   └── zhipu/                  #   智谱
│   │       ├── ZhipuChatProvider.java
│   │       └── ZhipuImageProvider.java
│   ├── repository/                 # Spring Data JPA 仓储
│   │   ├── SessionRepository.java
│   │   ├── MessageRepository.java
│   │   ├── ProjectRepository.java
│   │   └── GenerationRepository.java
│   └── tool/                       # 模型可调用的工具(Function Calling)
│       ├── Tool.java               #   工具接口
│       ├── AbstractTool.java       #   抽象基类
│       ├── ToolRegistry.java       #   注册表 + 按 LLM 选择启用
│       ├── CalculatorTool.java
│       ├── ImageGenerationTool.java#   文生图(创作/对话内绘图)
│       ├── TimeTool.java
│       └── WeatherTool.java
└── resources/
    ├── application.yml             # 主配置(模型 key、数据源、端口等)
    └── application-dev.yml         # 开发环境覆盖
```

分层约定：
- `controller/` 只做参数校验与协议转换，业务编排下沉到 `agent/`。
- `model/provider/` 定义 SPI，各厂商实现放独立子包，`ModelRegistry` 统一注册，新增模型只需加一个子包 + 装配配置。
- `entity/` 与 `dto/` 分离：实体面向数据库，DTO 面向前端协议，避免双向耦合。
- `tool/` 以接口 + 抽象类 + 注册表实现可插拔，LLM 通过 function calling 选择性调用。

## 🚀 安装与运行

### 环境要求

- JDK 17
- Maven 3.9+
- Node.js 18+（前端构建）

### 后端

```bash
# 在项目根目录

# 如使用联网搜索(MCP/Tavily), 先设置 API Key(子进程会继承此环境变量):
#   Windows (PowerShell):  $env:TAVILY_API_KEY='tvly-你的key'
#   Linux / macOS:         export TAVILY_API_KEY='tvly-你的key'

mvn spring-boot:run
```

后端默认监听 `http://localhost:8080`，Swagger 文档见 `http://localhost:8080/swagger-ui.html`。

### 前端

```bash
cd frontend
npm install
npm run dev
```

前端开发服务器通过 Vite 代理将 `/api` 转发到后端 8080 端口。

## ⚙️ 配置说明

主要配置位于 `src/main/resources/application.yml`：

- **模型 Key**：在 `agent.models` 下为各 chat / image / video provider 配置 `api-key`；支持环境变量（如 `OPENAI_API_KEY`、`ZHIPU_API_KEY`、`DASHSCOPE_API_KEY`）。
- **天气工具**：`agent-tools.weather` 默认演示模式（`api-key` 为 `demo-key` 时返回模拟数据）。配置真实高德 Key：
  ```yaml
  agent-tools:
    weather:
      enabled: true
      api-key: ${WEATHER_API_KEY:demo-key}
  ```
- **数据源**：默认 H2 文件库 `./data/database/agent`，可通过 `spring.datasource` 切换为 MySQL 等。
- **联网搜索（MCP）**：本项目作为 MCP Host，通过 `mcp.servers` 连接外部 MCP Server 获取能力，**不内置搜索实现**。示例已配 Tavily 官方 Server（`@tavily/mcp-server`），需先设置环境变量 `TAVILY_API_KEY`（见下方运行步骤）。工具名经 `aliases` 映射为 `web_search`，编排层与前端无需改动。

## 🛠 内置工具

| 工具 | 说明 | 备注 |
|---|---|---|
| `calculator` | 数学表达式计算（四则运算） | 本地执行，开箱即用 |
| `time` | 当前时间与日期查询 | 本地执行，开箱即用 |
| `weather` | 城市天气查询 | 默认演示模式，配置 Key 后返回真实数据 |
| `image_generation` | 文生图 | 复用对话内生图模型 |
| `video_generation` | 文生视频 | 创作模式视频子模式，异步任务编排 |
| `web_search` | 联网搜索（MCP） | 经 MCP 连接外部 Server，需配置 `TAVILY_API_KEY`；本轮对话内对重复调用做去重拦截 |

新增工具：实现 `Tool` 接口并标注 `@Component`，在 `ToolAutoConfiguration` 中会自动注册；如需在流式对话中暴露，重写 `supportsStreaming()` 返回 `true`。

## 📚 文档

- [`docs/01-需求文档.md`](docs/01-需求文档.md)
- [`docs/02-设计说明书.md`](docs/02-设计说明书.md)
- [`docs/03-V2.0规划文档.md`](docs/03-V2.0规划文档.md)（知识库 / 认证 / 多 Agent）
- [`docs/04-V1.1规划文档.md`](docs/04-V1.1规划文档.md)（语音 STT/TTS / 视频生成）
- [`docs/05-语言对话模块（未来可扩展项）.md`](docs/05-语言对话模块（未来可扩展项）.md)（纯对话交互 / 语音，未来扩展）
- [`docs/06-V1.1发布说明（Release Notes）.md`](docs/06-V1.1发布说明（Release Notes）.md)
- [`docs/RELEASE-v1.0.0.md`](docs/RELEASE-v1.0.0.md)
- [`docs/RELEASE-v1.1.0.md`](docs/RELEASE-v1.1.0.md)

## 🤝 参与贡献

1. Fork 本仓库
2. 新建 `feat_xxx` 分支
3. 提交代码（建议遵循 Conventional Commits）
4. 新建 Pull Request

## 📄 许可证

本项目许可证详见仓库 LICENSE 文件（如有）。
