import { api } from './http'

// 流式空闲超时(ms): 超过该时长仍无任何数据(既不增量也不结束), 视为连接异常,
// 主动中止并复位发送按钮, 防止后端因 DB 锁/异常导致 SSE 连接无法关闭、按钮卡在"取消"。
// 与后端 STREAM_TIMEOUT(120s) 对齐 —— 正常长思考时后端会先吐错误块并关闭, 不会误触发。
const STREAM_IDLE_TIMEOUT_MS = 120000

// 按 sessionId 记录进行中的 fetch 读取控制器, 供中断时本地中止
const chatControllers = {}

/**
 * 通用 SSE 读取: POST JSON 到 endpoint, 按 "data:" 行解析 JSON chunk 回调。
 * 内置空闲超时与 AbortController, abort 走 onDone 而非 onError 以复位按钮。
 * @param {string} endpoint  请求地址
 * @param {object} request   请求体(含 sessionId 等)
 * @param {(chunk)=>void} onChunk
 * @param {()=>void} onDone
 * @param {(e)=>void} onError
 */
export async function readSseStream(endpoint, request, onChunk, onDone, onError) {
  const controller = new AbortController()
  if (request.sessionId) chatControllers[request.sessionId] = controller
  let idleTimer = null
  const resetIdle = () => {
    if (idleTimer) clearTimeout(idleTimer)
    idleTimer = setTimeout(() => controller.abort(), STREAM_IDLE_TIMEOUT_MS)
  }
  resetIdle()
  try {
    const resp = await fetch(endpoint, {
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
          try { const chunk = JSON.parse(line.slice(5).trim()); onChunk(chunk) } catch (e) { /* 忽略无法解析的行 */ }
        }
      }
    }
    if (buffer.startsWith('data:')) {
      try { onChunk(JSON.parse(buffer.slice(5).trim())) } catch (e) { /* 忽略尾部残行 */ }
    }
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
