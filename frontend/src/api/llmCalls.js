import { api, unwrap } from './http'

// ===== LLM 调用链日志(独立查看, 不进对话内容)=====

// 调用链日志列表 (分页; 管理员可传 userId=all 查全部)
export const getLlmCallLogs = (params = {}) =>
  unwrap(api.get('/api/llm-calls/logs', { params: { page: 0, size: 20, ...params } }))