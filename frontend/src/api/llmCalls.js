import { api, unwrap } from './http'

// ===== LLM 调用链日志(独立查看, 不进对话内容)=====

// 调用链日志列表 (分页; 管理员可传 userId=all 查全部)
// 注: 列表只返回步骤元数据, 不含 systemPrompt / messages 两个重字段 —— 需要完整原文走详情接口
export const getLlmCallLogs = (params = {}) =>
  unwrap(api.get('/api/llm-calls/logs', { params: { page: 0, size: 20, ...params } }))

// 调用链日志详情: 返回单条日志的完整步骤明细(含实际提交的 System Prompt 与完整消息列表, 不截断)
export const getLlmCallLogDetail = (id) => unwrap(api.get(`/api/llm-calls/logs/${id}`))
