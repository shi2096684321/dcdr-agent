import { api, unwrap } from './http'
import { readSseStream, stopChat } from './stream'

// ===== 会话管理 =====
/**
 * 会话列表。
 * <p>
 * V3.5 起后端默认只返回用户自己开的对话(user) —— 定时任务 / 工作流自动执行产生的会话
 * 会被排除, 改由「自动化 → 任务对话」用 source=automation 单独拉取。
 *
 * @param {string} [source] user(默认) | schedule | workflow | automation | all
 */
export const getSessions = (source) =>
  unwrap(api.get('/api/sessions', source ? { params: { source } } : undefined))
export const createSession = (data) => unwrap(api.post('/api/sessions', data))
export const deleteSession = (id) => api.delete(`/api/sessions/${id}`).then(r => r.data)
export const renameSession = (id, title) => api.put(`/api/sessions/${id}/title`, { title }).then(r => r.data)
// 更新会话角色设定(用户人设/偏好): 由平台包装后转述给模型, 传空字符串清除。
// V3.1 起不再叫 system-prompt —— 用户设置的不是 System Prompt, 标准做法见 docs/14。
export const updateSessionPersona = (id, persona) =>
  api.patch(`/api/sessions/${id}/persona`, { persona }).then(r => r.data)
export const getSession = (id) => unwrap(api.get(`/api/sessions/${id}`))

// ===== 消息历史 =====
export const getMessages = (id) => unwrap(api.get(`/api/sessions/${id}/messages`))
export const deleteMessages = (id, fromSeq) =>
  api.delete(`/api/sessions/${id}/messages`, { params: { fromSeq } }).then(r => r.data)

// ===== 上下文占用 (V3.4) =====
/**
 * 会话最近一轮的上下文占用快照。
 * <p>
 * 走会话接口而不是对话接口: 占用是"某个会话的历史累积", 没有会话就没有可展示的值。
 * 返回 available=false 表示该会话还没对话过(界面显示空态, 不要画成 0%)。
 */
export const getContextUsage = (sessionId) =>
  unwrap(api.get(`/api/sessions/${sessionId}/context-usage`))

/** 总上下文设置(生效值 / 用户设置值 / 部署默认 / 允许范围) */
export const getContextLimit = () => unwrap(api.get('/api/chat/context-limit'))

/** 设置总上下文; limit 传 null 表示恢复默认(清除个人设置) */
export const updateContextLimit = (limit) =>
  unwrap(api.put('/api/chat/context-limit', { limit }))

// ===== 历史摘要 (V3.4) =====
/**
 * 会话当前的上下文压缩摘要。
 * <p>摘要决定模型"记得什么", 因此必须可读可改: 模型漏记关键约束时, 用户直接补一句,
 * 比重新开一轮对话便宜得多。available=false 表示该会话还没压缩过。
 */
export const getSessionSummary = (sessionId) =>
  unwrap(api.get(`/api/sessions/${sessionId}/summary`))

/** 改写摘要正文(不动"已压缩到第几条"的边界, 那是压缩策略的职责) */
export const updateSessionSummary = (sessionId, text) =>
  unwrap(api.put(`/api/sessions/${sessionId}/summary`, { text }))

/** 清除摘要: 下一轮若仍超预算, 会从完整原文重新压缩 */
export const clearSessionSummary = (sessionId) =>
  api.delete(`/api/sessions/${sessionId}/summary`).then(r => r.data)

// ===== 对话流式接口 =====
/** 对话流式 (SSE): /api/chat/stream */
export const streamChat = (request, onChunk, onDone, onError) =>
  readSseStream('/api/chat/stream', request, onChunk, onDone, onError)

/** 对话内文生图 (SSE): /api/chat/image */
export const streamChatImage = (request, onChunk, onDone, onError) =>
  readSseStream('/api/chat/image', request, onChunk, onDone, onError)

/** 中断当前会话(停止生成) */
export { stopChat }
