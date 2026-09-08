# dcdr-agent

A production-grade Agent orchestration platform based on Spring Boot + Java 17 + LangChain4j, featuring intelligent routing, visual DAG workflow orchestration, fault-tolerant execution, knowledge base RAG, multimodal generation, and full observability (tracing / metrics / audit).

Current version: **v3.0.0** (Release Notes: [`docs/RELEASE-v3.0.0.md`](docs/RELEASE-v3.0.0.md)).

> Version note: v3.0.0 unifies the v3.0.0-alpha milestone (intelligent routing F-30 / trigger layer F-31 / execution fault tolerance F-33) with visual DAG orchestration (F-32), template market (F-35), cost & quota (F-39), production hardening (F-40 partial), and the LangChain4j framework migration of the capability layer. See [`docs/07-V3.0规划文档.md`](docs/07-V3.0规划文档.md).

## ✨ Features

- **Streaming conversation**: SSE real-time output, reasoning (CoT) content displayed separately; supports message editing / retry / truncation and conversation interruption (stop).
- **Intelligent routing (F-30)**: LLM-based intent recognition routes a chat message to workflow / Agent / plain conversation automatically; low-confidence results fall back to conversation; routing logs are persisted and queryable.
- **Visual DAG orchestration (F-32)**: drag-and-drop canvas with routing nodes, replacing form-only workflow editing.
- **Fault-tolerant execution (F-33)**: workflow state persisted (`workflow_executions`), retries (fixed / linear / exponential backoff), resumable from the last completed step, and human-approval nodes (`approve` / `cancel`).
- **Trigger layer (F-31)**: trigger types `manual / api / webhook / event / schedule / chat`; Webhook endpoints with token auth and optional signature verification.
- **Governance & observability (F-34 / F-40)**: LLM call tracing (Micrometer Tracing + Zipkin), monitoring view, Actuator health, Prometheus metrics, rate limiting and model-call circuit breaker, audit logs, log rotation (100MB/file, 30 days / 2GB cap).
- **Cost & quota (F-39)**: per-conversation token usage tracking (CostTracker) and user-level quota checks (429 when exceeded).
- **Template market (F-35)**: Agent / workflow templates with one-click reuse.
- **Context protection**: context length estimation and over-length alerts; session-level System Prompt customization; streaming messages are session-scoped (switching sessions never mixes outputs).
- **Knowledge base RAG**: KB management (user-level / platform-level visibility), document upload → chunking → embedding → vector retrieval (pgvector); Agentic RAG with forced-retrieval mode and citation cards.
- **Multimodal**: in-conversation text-to-image (ChatImageService), image recognition and visual model auto-routing Q&A.
- **Creation mode**: text-to-image / text-to-video project management, async generation tasks, batch generation and parameter reuse.
- **Tool calling (Function Calling)**: built-in `calculator` / `time` / `weather` / `image_generation` / `web_search` / `knowledge_base_search`, unified tool framework (registered as native `ToolSpecification` at startup), streaming result return and persistence.
- **Multi-model adaptation**: LangChain4j-based providers with OpenAI-compatible, Qwen, DeepSeek, Zhipu built in; auto-routed by UI mode / model type.

## 🧱 Software Architecture

The project adopts a front-end / back-end separation architecture: the back-end is Java (Spring Boot), and the front-end is Vue 3 + Vite + Element Plus.

##### Front-end directory structure

The front-end source code is located in the `frontend/` directory, organized following the layering of "views / components / logic / interfaces", with the `@` alias pointing to `frontend/src`.

```
frontend/
├── public/                      # static assets (not bundled, output as-is by Vite)
│   └── dcdr-logo.png            # favicon referenced directly by index.html
├── src/
│   ├── api/                     # backend interface encapsulation layer
│   │   ├── http.js              #   axios instance + response unwrap utility
│   │   ├── stream.js            #   SSE streaming read + AbortController + idle timeout + stopChat
│   │   ├── chat.js              #   conversation: session/message CRUD, streamChat, streamChatImage
│   │   ├── image.js             #   creation: project CRUD, image task submit/poll/history
│   │   ├── system.js            #   system: model list, thread pool status, file upload
│   │   └── index.js             #   barrel aggregation export
│   ├── assets/                  # static assets requiring import bundling
│   │   └── dcdr-logo.png        # logo referenced by <img :src="..."> in components
│   ├── components/              # pure presentational / reusable components (no business state)
│   │   ├── AppHeader.vue        #   top bar: logo + mode switch + model select
│   │   ├── MessageBubble.vue    #   single message bubble: image/video/reasoning/edit/actions
│   │   ├── ImagePreviewModal.vue#   image fullscreen preview modal (v-model:url)
│   │   └── GenerationDetailDialog.vue  # creation image detail dialog (with param reuse)
│   ├── composables/             # Vue composables (reusable logic)
│   │   └── useMarkdown.js        #   shared markdown-it instance + renderMarkdown
│   ├── stores/                  # Pinia state (auth.js auth / model.js global model list)
│   ├── styles/                  # global styles
│   │   ├── base.css              #   base reset
│   │   ├── gradients.css        #   .gradient-bg/green/orange utility classes
│   │   └── index.css            #   entry, imported by main.js
│   ├── utils/                    # general utilities
│   │   └── format.js             #   formatDuration / formatDate / styleLabel
│   ├── views/                   # page-level components (one mode/route each)
│   │   ├── ChatView.vue          #   chat mode: sidebar + message list + streaming + input
│   │   ├── CreativeView.vue      #   creation mode: project sidebar + control panel + preview
│   │   ├── KnowledgeBaseView.vue #   knowledge base management (docs / retrieval test)
│   │   ├── AgentView.vue         #   custom agent CRUD
│   │   ├── WorkflowView.vue      #   workflow definition & execution history
│   │   ├── DagEditorView.vue     #   visual DAG canvas (v3.0)
│   │   ├── TemplateMarketView.vue#   agent/workflow template market (v3.0)
│   │   ├── ScheduledTaskView.vue #   scheduled tasks
│   │   ├── RoutingView.vue       #   routing logs (v3.0)
│   │   ├── MonitorView.vue       #   monitoring dashboard (v3.0)
│   │   ├── AdminView.vue         #   admin console (admin only)
│   │   └── AuthView.vue          #   login / register
│   ├── App.vue                   # root component: layout + <AppHeader> + view orchestration
│   └── main.js                   # entry: register Pinia / ElementPlus, import styles
├── index.html
├── vite.config.js               # Vite config (with @ alias and /api proxy to 8080)
└── package.json
```

Layering conventions:
- `views/` holds page-level components with business state and orchestration; `components/` holds pure presentational components without business logic.
- `api/` is split by business domain (chat/image/system); common capabilities (http/stream) are extracted separately, and `index.js` aggregates exports for backward compatibility.
- Global reusable styles go in `styles/`; component-specific styles use `<style scoped>` isolation.

##### Back-end directory structure

The back-end source code is under `src/main/`, with package root `com.dcdr.agent`, using the layering "controller → orchestration/service → model & tools → repository".

```
src/main/
├── java/com/dcdr/agent/
│   ├── JavaAgentApplication.java   # Spring Boot bootstrap class
│   ├── agent/                      # agent core layer
│   │   ├── AgentOrchestrator.java  #   orchestrator: conversation flow / tool calling / SSE streaming
│   │   ├── ActiveChatRegistry.java #   active session registry, supports stop signal dispatch
│   │   ├── ModelRouter.java        #   model router: select provider by uiMode/type
│   │   ├── ImageResolver.java      #   image URL → accessible address resolution
│   │   ├── creative/               #   creation mode engine
│   │   │   ├── CreativeEngine.java #     async image/video generation orchestration (thread pool + task state)
│   │   │   ├── ImageGenerationWorker.java  # single image generation worker
│   │   │   └── VideoGenerationWorker.java  # video generation worker
│   │   ├── image/
│   │   │   └── ChatImageService.java  # in-conversation text-to-image (via SSE)
│   │   ├── memory/                 # context memory
│   │   │   ├── ChatMemory.java        #     memory interface
│   │   │   ├── InMemoryChatMemory.java #  in-memory implementation
│   │   │   ├── Message.java           #     message carrier
│   │   │   └── TokenEstimator.java    #     token estimation (context alert)
│   │   └── session/                # session/project management
│   │       ├── SessionManager.java
│   │       └── ProjectManager.java
│   ├── common/                     # common components
│   │   ├── Result.java             #   unified response wrapper (code/data/message)
│   │   ├── constant/               # enum constants
│   │   │   ├── MessageType.java
│   │   │   ├── ModelType.java      #     CHAT / IMAGE / VIDEO
│   │   │   └── UIMode.java         #     chat / creative
│   │   ├── exception/              # exception handling
│   │   │   ├── BusinessException.java
│   │   │   └── GlobalExceptionHandler.java  # global exception → Result
│   │   └── util/                   # storage utilities
│   │       ├── GeneratedImageStorage.java   # generated image persistence
│   │       └── ReferImageStorage.java       # reference image storage
│   ├── config/                     # Spring config
│   │   ├── AgentProperties.java    #   agent config items
│   │   ├── AsyncConfig.java        #   async thread pool (image generation)
│   │   ├── CorsConfig.java         #   CORS
│   │   ├── ModelAutoConfiguration.java    # model provider assembly
│   │   ├── OpenApiConfig.java      #   OpenAPI/Swagger docs
│   │   ├── ToolAutoConfiguration.java     # tool assembly
│   │   └── ToolsProperties.java    #   tool switch config
│   ├── controller/                 # REST controllers (for frontend /api/*)
│   │   ├── ChatController.java     #   /api/chat/* (streaming conversation / text-to-image / stop)
│   │   ├── SessionController.java  #   /api/sessions + /api/sessions/{id}/messages
│   │   ├── ProjectController.java  #   /api/projects
│   │   ├── GenerationController.java   # /api/generations
│   │   ├── ImageController.java    #   /api/images/generate
│   │   ├── VideoController.java     #   /api/videos/generate (async text-to-video task)
│   │   ├── FileController.java     #   /api/files/upload
│   │   ├── ModelController.java    #   /api/models
│   │   └── SystemController.java   #   /api/system/thread-pool
│   ├── dto/                        # data transfer objects (request/response)
│   │   ├── ChatRequest.java / ChatResponse.java / ChatChunk.java  # conversation
│   │   ├── ChatImageRequest.java   #   in-conversation text-to-image request
│   │   ├── ImageRequest.java / ImageResponse.java / VideoRequest.java
│   │   ├── SessionDTO.java / MessageDTO.java / ProjectDTO.java / GenerationDTO.java
│   │   ├── ModelInfo.java          #   model info (for frontend selector)
│   │   └── ThreadPoolStatsDTO.java
│   ├── entity/                     # JPA persistent entities
│   │   ├── SessionEntity.java      #   session
│   │   ├── MessageEntity.java      #   message (with seq, supports edit/retry truncation)
│   │   ├── ProjectEntity.java      #   creation project
│   │   └── GenerationEntity.java   #   image generation record
│   ├── model/                      # model Provider (SPI style)
│   │   ├── provider/               #   interface definitions
│   │   │   ├── ChatModelProvider.java
│   │   │   ├── ImageModelProvider.java
│   │   │   └── VideoModelProvider.java
│   │   ├── registry/
│   │   │   └── ModelRegistry.java  #   query provider by name
│   │   ├── openai/                 #   OpenAI-compatible
│   │   │   ├── OpenAiChatProvider.java
│   │   │   ├── OpenAiCompatibleChatProvider.java  # third-party compat
│   │   │   └── OpenAiImageProvider.java
│   │   ├── qwen/                   #   Qwen
│   │   │   ├── QwenChatProvider.java
│   │   │   └── QwenImageProvider.java
│   │   ├── deepseek/
│   │   │   └── DeepseekChatProvider.java
│   │   └── zhipu/                  #   Zhipu
│   │       ├── ZhipuChatProvider.java
│   │       └── ZhipuImageProvider.java
│   ├── repository/                 # Spring Data JPA repositories
│   │   ├── SessionRepository.java
│   │   ├── MessageRepository.java
│   │   ├── ProjectRepository.java
│   │   └── GenerationRepository.java
│   └── tool/                       # tools callable by the model (Function Calling)
│       ├── Tool.java               #   tool interface
│       ├── AbstractTool.java       #   abstract base class
│       ├── ToolRegistry.java       #   registry + enable-by-LLM selection
│       ├── CalculatorTool.java
│       ├── ImageGenerationTool.java#   text-to-image (creation/in-conversation)
│       ├── TimeTool.java
│       └── WeatherTool.java
└── resources/
    ├── application.yml             # main config (model key, datasource, port, etc.)
    └── application-dev.yml        # dev environment overrides
```

Layering conventions:
- `controller/` only does parameter validation and protocol conversion; business orchestration sinks into `agent/`.
- `model/provider/` defines SPI; vendor implementations live in independent sub-packages, uniformly registered by `ModelRegistry`; adding a model only requires a new sub-package + assembly config.
- `entity/` and `dto/` are separated: entities target the database, DTOs target the frontend protocol, avoiding bidirectional coupling.
- `tool/` implements pluggability via interface + abstract class + registry; the LLM calls selectively via function calling.

## 🚀 Installation & Running

### Requirements

- JDK 17
- Maven 3.9+
- Node.js 18+ (front-end build)

### Back-end

```bash
# in project root

# if using web search (MCP/Tavily), set the API Key first (child process inherits this env var):
#   Windows (PowerShell):  $env:TAVILY_API_KEY='tvly-your-key'
#   Linux / macOS:         export TAVILY_API_KEY='tvly-your-key'

mvn spring-boot:run
```

The back-end listens on `http://localhost:8080` by default; Swagger docs at `http://localhost:8080/swagger-ui.html`.

### Front-end

```bash
cd frontend
npm install
npm run dev
```

The front-end dev server proxies `/api` to the back-end 8080 port via Vite.

## ⚙️ Configuration

Main configuration is in `src/main/resources/application.yml`:

- **Model Key**: configure `api-key` for each chat / image / video provider under `agent.models`; supports environment variables (e.g. `OPENAI_API_KEY`, `ZHIPU_API_KEY`, `DASHSCOPE_API_KEY`).
- **Weather tool**: `agent-tools.weather` defaults to demo mode (returns simulated data when `api-key` is `demo-key`). Configure a real Amap key:
  ```yaml
  agent-tools:
    weather:
      enabled: true
      api-key: ${WEATHER_API_KEY:demo-key}
  ```
- **Datasource**: defaults to H2 file DB `./data/database/agent` for development; for production use PostgreSQL (+pgvector for RAG) via `spring.datasource`, and run `docs/sql/v3.0_schema.sql` (adds `routing_logs` / `webhook_endpoints` / `workflow_executions`).
- **V3 switches**: routing / retry / webhook base URL are under `agent.v3.*`; each capability can be toggled independently (with routing disabled, chat behaves exactly as v2.3).
- **Web search (MCP)**: this project acts as an MCP Host, connecting to an external MCP Server via `mcp.servers` for capability — **no built-in search implementation**. The sample configures the official Tavily Server (`@tavily/mcp-server`), requiring env var `TAVILY_API_KEY` first (see run steps above). The tool name is mapped to `web_search` via `aliases`; the orchestration layer and front-end need no changes.

## 🛠 Built-in Tools

| Tool | Description | Notes |
|---|---|---|
| `calculator` | mathematical expression evaluation (arithmetic) | local execution, out-of-the-box |
| `time` | current time and date query | local execution, out-of-the-box |
| `weather` | city weather query | demo mode by default, returns real data after key configured |
| `image_generation` | text-to-image | reuses in-conversation image model |
| `video_generation` | text-to-video | creation mode video sub-mode, async task orchestration |
| `web_search` | web search (MCP) | via MCP to external Server, requires `TAVILY_API_KEY`; de-dup interception of repeated calls within the same conversation turn |
| `knowledge_base_search` | knowledge base retrieval (RAG) | searches user-selected KBs (merged with platform KBs) on the LLM's decision (Agentic RAG) |

Adding a tool: implement the `Tool` interface and annotate with `@Component`; it auto-registers in `ToolAutoConfiguration`. To expose it in streaming conversation, override `supportsStreaming()` to return `true`.

## 📚 Documentation

- [`docs/01-需求文档.md`](docs/01-需求文档.md)
- [`docs/02-设计说明书.md`](docs/02-设计说明书.md)
- [`docs/07-V3.0规划文档.md`](docs/07-V3.0规划文档.md) (intelligent routing / DAG / fault tolerance / hardening)
- [`docs/RELEASE-v3.0.0.md`](docs/RELEASE-v3.0.0.md) (current release)
- [`docs/RELEASE-v3.0.0-alpha-self-impl.md`](docs/RELEASE-v3.0.0-alpha-self-impl.md) (v3.0 alpha milestone)
- [`docs/03-V2.0规划文档.md`](docs/03-V2.0规划文档.md) (knowledge base / auth / multi-agent)
- [`docs/04-V1.1规划文档.md`](docs/04-V1.1规划文档.md) (voice STT/TTS / video generation)
- [`docs/05-语言对话模块（未来可扩展项）.md`](docs/05-语言对话模块（未来可扩展项）.md) (pure conversation / voice, future)
- [`docs/RELEASE-v1.0.0.md`](docs/RELEASE-v1.0.0.md) and other historical release notes under `docs/`

## 🤝 Contributing

1. Fork the repository
2. Create a `feat_xxx` branch
3. Commit your code (Conventional Commits recommended)
4. Create a Pull Request

## 📄 License

See the repository LICENSE file (if any) for project license.
