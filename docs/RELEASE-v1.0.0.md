# dcdr-agent v1.0.0

首个正式版本。一个基于 Spring Boot + Vue 3 的开源智能体（Agent）平台，支持流式对话、文生图创作，以及对多厂商大模型的统一编排与工具调用（Function Calling）。

## ✨ 核心特性

**对话能力**
- 流式对话输出（SSE），支持推理（CoT）内容单独展示
- 消息编辑 / 重试 / 截断，以及对话过程中断（stop）信号下发
- 上下文长度估算与超长提醒，避免超出模型上下文窗口
- 对话内文生图，并支持图片识别与视觉模型问答

**创作能力（文生图）**
- 项目化管理：创作项目 / 生成任务 / 历史记录
- 图片生成任务异步提交与轮询，支持参数套用与重生成
- 批量生成图片，线程池状态可视化

**工具调用（Function Calling）**
- 内置 4 个开箱即用工具，v1.0.0 起全部启用并可在对话中正常调用：
  - `calculator` 数学表达式计算
  - `time` 当前时间与日期查询
  - `weather` 城市天气查询（默认演示模式，配置 API Key 后返回真实数据）
  - `image_generation` 文生图
- 统一的工具注册表与接口（Tool / AbstractTool / ToolRegistry），新工具即插即用
- 工具结果流式回传并在前端实时展示，落库消息中保留结果，刷新不丢失

**多模型适配**
- SPI 风格 Provider 抽象，已内置 OpenAI 兼容系、通义千问、DeepSeek、智谱（Zhipu）
- 按 UI 模式 / 模型类型（chat / image / video）自动路由，新增模型只需新增一个子包

**工程化**
- 前后端分离，后端 Spring Boot 3.3.5（Java 17）+ JPA 持久化，前端 Vue 3 + Vite + Element Plus
- 统一响应封装（Result）与全局异常处理、CORS、Swagger / OpenAPI 文档

## 📦 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3.5 · Java 17 · Spring Data JPA · H2 / 关系型数据库 |
| 前端 | Vue 3 · Vite · Element Plus · Pinia |
| 协议 | SSE 流式 · REST · Function Calling |

## 🚀 快速开始

```bash
# 后端（Java 17）
mvn spring-boot:run

# 前端
cd frontend
npm install
npm run dev
```

在 `application.yml` 中配置模型 API Key；如需真实天气数据，设置 `agent-tools.weather.api-key`（环境变量 `WEATHER_API_KEY`）。

## 📝 说明

v1.0.0 聚焦「对话 + 创作 + 工具」三大能力闭环，工具体系已在对话中全量可用。V2.0 规划（多 Agent 协作、知识库检索等）见 `docs/03-V2.0规划文档.md`。
