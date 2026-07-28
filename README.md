# dcdr-agent

#### 介绍
{**以下是 Gitee 平台说明，您可以替换此简介**
Gitee 是 OSCHINA 推出的基于 Git 的代码托管平台（同时支持 SVN）。专为开发者提供稳定、高效、安全的云端软件开发协作平台
无论是个人、团队、或是企业，都能够用 Gitee 实现代码托管、项目管理、协作开发。企业项目请看 [https://gitee.com/enterprises](https://gitee.com/enterprises)}

#### 软件架构

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
│   │   └── useMarkdown.js       #   共享 markdown-it 实例 + renderMarkdown
│   ├── stores/                  # Pinia 状态管理(已注册, 预留, 暂未使用)
│   ├── styles/                  # 全局样式
│   │   ├── base.css             #   基础重置
│   │   ├── gradients.css        #   .gradient-bg/green/orange 工具类
│   │   └── index.css            #   入口, 由 main.js 引入
│   ├── utils/                   # 通用工具函数
│   │   └── format.js             #   formatDuration / formatDate / styleLabel
│   ├── views/                   # 页面级组件(对应一个模式/路由)
│   │   ├── ChatView.vue         #   对话模式: 侧边栏 + 消息列表 + 流式输出 + 输入区
│   │   └── CreativeView.vue     #   创作模式: 项目侧边栏 + 控制面板 + 预览区
│   ├── App.vue                  # 根组件: 布局 + <AppHeader> + 视图编排
│   └── main.js                  # 入口: 注册 Pinia / ElementPlus, 引入 styles
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
│   │   │   ├── CreativeEngine.java #     异步图片生成编排(线程池 + 任务状态)
│   │   │   └── ImageGenerationWorker.java  # 单张图片生成 worker
│   │   ├── image/
│   │   │   └── ChatImageService.java  # 对话内文生图(走 SSE)
│   │   ├── memory/                 #   上下文记忆
│   │   │   ├── ChatMemory.java     #     记忆接口
│   │   │   ├── InMemoryChatMemory.java #  内存实现
│   │   │   ├── Message.java        #     消息载体
│   │   │   └── TokenEstimator.java #     token 估算(上下文告警用)
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
│   │   ├── ModelAutoConfiguration.java  # 模型 provider 装配
│   │   ├── OpenApiConfig.java       #   OpenAPI/Swagger 文档
│   │   ├── ToolAutoConfiguration.java   # 工具装配
│   │   └── ToolsProperties.java      #   工具开关配置
│   ├── controller/                 # REST 控制器(对前端 /api/*)
│   │   ├── ChatController.java     #   /api/chat/* (流式对话 / 文生图 / stop)
│   │   ├── SessionController.java  #   /api/sessions + /api/sessions/{id}/messages
│   │   ├── ProjectController.java  #   /api/projects
│   │   ├── GenerationController.java   # /api/generations
│   │   ├── ImageController.java    #   /api/images/generate
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
│   │   └── GenerationEntity.java  #   图片生成记录
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
    └── application-dev.yml          # 开发环境覆盖
```

分层约定：
- `controller/` 只做参数校验与协议转换，业务编排下沉到 `agent/`。
- `model/provider/` 定义 SPI，各厂商实现放独立子包，`ModelRegistry` 统一注册，新增模型只需加一个子包 + 装配配置。
- `entity/` 与 `dto/` 分离：实体面向数据库，DTO 面向前端协议，避免双向耦合。
- `tool/` 以接口 + 抽象类 + 注册表实现可插拔，LLM 通过 function calling 选择性调用。

#### 安装教程

1.  xxxx
2.  xxxx
3.  xxxx

#### 使用说明

1.  xxxx
2.  xxxx
3.  xxxx

#### 参与贡献

1.  Fork 本仓库
2.  新建 Feat_xxx 分支
3.  提交代码
4.  新建 Pull Request


#### 特技

1.  使用 Readme\_XXX.md 来支持不同的语言，例如 Readme\_en.md, Readme\_zh.md
2.  Gitee 官方博客 [blog.gitee.com](https://blog.gitee.com)
3.  你可以 [https://gitee.com/explore](https://gitee.com/explore) 这个地址来了解 Gitee 上的优秀开源项目
4.  [GVP](https://gitee.com/gvp) 全称是 Gitee 最有价值开源项目，是综合评定出的优秀开源项目
5.  Gitee 官方提供的使用手册 [https://gitee.com/help](https://gitee.com/help)
6.  Gitee 封面人物是一档用来展示 Gitee 会员风采的栏目 [https://gitee.com/gitee-stars/](https://gitee.com/gitee-stars/)
