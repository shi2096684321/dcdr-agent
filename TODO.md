# TODO / 待优化项

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

- ✅ 刷新页面后 token 使用量消失：`SessionManager.getMessages` 主路径补充 `setTokenUsage(msg.getTokenUsage())`。
- ✅ `application.yml` 的 chat 场景 prompt 含未填充的 `{user_question}` / `{search_context}` 占位符：移除占位符，system 消息保持静态，用户问题与搜索结果由 history（user 消息 + tool 结果）自然提供。
- ✅ agentic 多轮工具调用循环失效：修正每轮 tool_calls 快照与 history 回填逻辑。
- ✅ 工具轮噪声（空气泡）显示在会话记录中：后端过滤 tool 消息、前端聚合为可折叠的「🔧 已调用 N 个工具」。
