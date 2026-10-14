import { api, unwrap } from './http'

// ===== V3.1 长期记忆 =====

// 记忆列表（includeInactive=true 时一并返回归档/已删除条目）
export const getMemories = (includeInactive = false) =>
  unwrap(api.get('/api/memories', { params: { includeInactive } }))

// 记忆概览（活跃总数 + 用户级/Agent 级分布 + 当前召回策略说明 + 自动记忆开关三态）
export const getMemorySummary = () => unwrap(api.get('/api/memories/summary'))

// 设置「AI 自动记忆」用户级开关。
// enabled 传 true/false = 明确表态；传 null = 恢复默认（不再表态，重新跟随部署配置）。
// 返回更新后的概览，调用方可直接替换本地的 summary。
export const setAutoExtract = (enabled) =>
  unwrap(api.put('/api/memories/auto-extract', { enabled }))

// 新增记忆
export const createMemory = (data) => unwrap(api.post('/api/memories', data))

// 更新记忆（仅提交的字段生效）
export const updateMemory = (id, data) => unwrap(api.put(`/api/memories/${id}`, data))

// 删除单条记忆（软删除，删除后不再参与召回）
export const deleteMemory = (id) => unwrap(api.delete(`/api/memories/${id}`))

// 清空全部记忆，返回被清空的条数
export const clearMemories = () => unwrap(api.delete('/api/memories'))

// 归档单条记忆（保留数据、不再参与召回；与删除的差别是用户意图「还有效，先不用」）
export const archiveMemory = (id) => unwrap(api.post(`/api/memories/${id}/archive`))

// 恢复记忆为活跃（归档与已删除都能恢复 —— 软删除的撤销入口）
export const restoreMemory = (id) => unwrap(api.post(`/api/memories/${id}/restore`))
