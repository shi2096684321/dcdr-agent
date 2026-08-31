import { api, unwrap } from './http'
import { readSseStream, stopChat } from './stream'

// ===== 会话管理 =====
export const getSessions = () => unwrap(api.get('/api/sessions'))
export const createSession = (data) => unwrap(api.post('/api/sessions', data))
export const deleteSession = (id) => api.delete(`/api/sessions/${id}`).then(r => r.data)
export const renameSession = (id, title) => api.put(`/api/sessions/${id}/title`, { title }).then(r => r.data)
// 更新会话级 System Prompt(用户定制人设/约束): 传空字符串清除
export const updateSystemPrompt = (id, systemPrompt) =>
  api.patch(`/api/sessions/${id}/system-prompt`, { systemPrompt }).then(r => r.data)
export const getSession = (id) => unwrap(api.get(`/api/sessions/${id}`))

// ===== 消息历史 =====
export const getMessages = (id) => unwrap(api.get(`/api/sessions/${id}/messages`))
export const deleteMessages = (id, fromSeq) =>
  api.delete(`/api/sessions/${id}/messages`, { params: { fromSeq } }).then(r => r.data)

// ===== 对话流式接口 =====
/** 对话流式 (SSE): /api/chat/stream */
export const streamChat = (request, onChunk, onDone, onError) =>
  readSseStream('/api/chat/stream', request, onChunk, onDone, onError)

/** 对话内文生图 (SSE): /api/chat/image */
export const streamChatImage = (request, onChunk, onDone, onError) =>
  readSseStream('/api/chat/image', request, onChunk, onDone, onError)

/** 中断当前会话(停止生成) */
export { stopChat }
