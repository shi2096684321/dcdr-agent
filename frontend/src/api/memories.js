import { api, unwrap } from './http'

// ===== V3.1 长期记忆 =====

// 记忆列表（includeInactive=true 时一并返回归档/已删除条目）
export const getMemories = (includeInactive = false) =>
  unwrap(api.get('/api/memories', { params: { includeInactive } }))

// 记忆概览（活跃总数 + 用户级/Agent 级分布 + 当前召回策略说明）
export const getMemorySummary = () => unwrap(api.get('/api/memories/summary'))

// 新增记忆
export const createMemory = (data) => unwrap(api.post('/api/memories', data))

// 更新记忆（仅提交的字段生效）
export const updateMemory = (id, data) => unwrap(api.put(`/api/memories/${id}`, data))

// 删除单条记忆（软删除，删除后不再参与召回）
export const deleteMemory = (id) => unwrap(api.delete(`/api/memories/${id}`))

// 清空全部记忆，返回被清空的条数
export const clearMemories = () => unwrap(api.delete('/api/memories'))
