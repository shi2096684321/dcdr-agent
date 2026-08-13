import { api, unwrap } from './http'

// ===== V2.3 自定义 Agent (F-24) =====

// Agent 列表: 自己创建的 + 公开的 + 预设模板
export const getAgents = () => unwrap(api.get('/api/agents'))

// 预设模板库
export const getAgentTemplates = () => unwrap(api.get('/api/agents/templates'))

// Agent 详情
export const getAgent = (id) => unwrap(api.get(`/api/agents/${id}`))

// 创建 Agent
export const createAgent = (data) => unwrap(api.post('/api/agents', data))

// 更新 Agent
export const updateAgent = (id, data) => unwrap(api.put(`/api/agents/${id}`, data))

// 删除 Agent
export const deleteAgent = (id) => api.delete(`/api/agents/${id}`).then((r) => r.data)

// 克隆模板/公开 Agent 为自己的副本
export const cloneAgent = (id) => unwrap(api.post(`/api/agents/${id}/clone`))
