import axios from 'axios'

const api = axios.create({ baseURL: '/' })

export const getModels = () => api.get('/api/models').then(r => r.data.data)
export const getSessions = () => api.get('/api/sessions').then(r => r.data.data)
export const createSession = (data) => api.post('/api/sessions', data).then(r => r.data.data)
export const deleteSession = (id) => api.delete(`/api/sessions/${id}`).then(r => r.data)
export const renameSession = (id, title) => api.put(`/api/sessions/${id}/title`, { title }).then(r => r.data)
export const getMessages = (id) => api.get(`/api/sessions/${id}/messages`).then(r => r.data.data)
export const deleteMessages = (id, fromSeq) => api.delete(`/api/sessions/${id}/messages`, { params: { fromSeq } }).then(r => r.data)
export const generateImage = (data) => api.post('/api/images/generate', data).then(r => r.data.data)
export const getGeneration = (id) => api.get(`/api/generations/${id}`).then(r => r.data.data)
export const getProjects = () => api.get('/api/projects').then(r => r.data.data)
export const createProject = (data) => api.post('/api/projects', data).then(r => r.data.data)
export const deleteProject = (id) => api.delete(`/api/projects/${id}`).then(r => r.data)
export const renameProject = (id, name) => api.put(`/api/projects/${id}`, { name }).then(r => r.data)
export const getGenerations = (id) => api.get(`/api/projects/${id}/generations`).then(r => r.data.data)
export const getThreadPoolStats = () => api.get('/api/system/thread-pool').then(r => r.data.data)
export const uploadFile = (file) => { const fd = new FormData(); fd.append('file', file); return api.post('/api/files/upload', fd).then(r => r.data.data) }

// 按 sessionId 记录进行中的 fetch 读取控制器, 供中断时本地中止
const chatControllers = {}

// 流式空闲超时(ms): 超过该时长仍无任何数据(既不增量也不结束), 视为连接异常,
// 主动中止并复位发送按钮, 防止后端因 DB 锁/异常导致 SSE 连接无法关闭、按钮卡在"取消"。
// 与后端 STREAM_TIMEOUT(120s) 对齐 —— 正常长思考时后端会先吐错误块并关闭, 不会误触发。
const STREAM_IDLE_TIMEOUT_MS = 120000

export async function streamChat(request, onChunk, onDone, onError) {
  const controller = new AbortController()
  if (request.sessionId) chatControllers[request.sessionId] = controller
  let idleTimer = null
  const resetIdle = () => {
    if (idleTimer) clearTimeout(idleTimer)
    idleTimer = setTimeout(() => controller.abort(), STREAM_IDLE_TIMEOUT_MS)
  }
  resetIdle()
  try {
    const resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal: controller.signal
    })
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      resetIdle()
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop()
      for (const line of lines) {
        if (line.startsWith('data:')) {
          try { const chunk = JSON.parse(line.slice(5).trim()); onChunk(chunk) } catch(e) {}
        }
      }
    }
    if (buffer.startsWith('data:')) { try { onChunk(JSON.parse(buffer.slice(5).trim())) } catch(e) {} }
    onDone()
  } catch (e) {
    // 用户主动中断(abort)或空闲超时自动中止, 均不走错误, 走正常结束复位按钮
    if (e && e.name === 'AbortError') { onDone(); return }
    onError(e)
  } finally {
    if (idleTimer) clearTimeout(idleTimer)
    if (request.sessionId) delete chatControllers[request.sessionId]
  }
}

/**
 * 对话内文生图 (SSE): 与 streamChat 相同的流读取逻辑, 仅端点不同
 */
export async function streamChatImage(request, onChunk, onDone, onError) {
  const controller = new AbortController()
  if (request.sessionId) chatControllers[request.sessionId] = controller
  let idleTimer = null
  const resetIdle = () => {
    if (idleTimer) clearTimeout(idleTimer)
    idleTimer = setTimeout(() => controller.abort(), STREAM_IDLE_TIMEOUT_MS)
  }
  resetIdle()
  try {
    const resp = await fetch('/api/chat/image', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal: controller.signal
    })
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      resetIdle()
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop()
      for (const line of lines) {
        if (line.startsWith('data:')) {
          try { const chunk = JSON.parse(line.slice(5).trim()); onChunk(chunk) } catch (e) {}
        }
      }
    }
    if (buffer.startsWith('data:')) {
      try { onChunk(JSON.parse(buffer.slice(5).trim())) } catch (e) {}
    }
    onDone()
  } catch (e) {
    if (e && e.name === 'AbortError') { onDone(); return }
    onError(e)
  } finally {
    if (idleTimer) clearTimeout(idleTimer)
    if (request.sessionId) delete chatControllers[request.sessionId]
  }
}

/**
 * 中断会话(停止生成)
 * 1. 本地立即中止 SSE 读取(关闭连接, 后端 onDispose 会自动取消上游模型调用)
 * 2. 通知后端主动取消模型调用并下发 interrupted 信号(即使连接已异常也能兜底)
 */
export async function stopChat(sessionId) {
  if (!sessionId) return
  if (chatControllers[sessionId]) {
    chatControllers[sessionId].abort()
    delete chatControllers[sessionId]
  }
  try {
    await api.post(`/api/chat/stop?sessionId=${encodeURIComponent(sessionId)}`)
  } catch (e) { /* 忽略: 后端可能已随连接关闭自动取消 */ }
}
