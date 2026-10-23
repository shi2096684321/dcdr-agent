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

// V3.2 AI 生成专家草稿: 一句话需求 → 未落库的配置, 前端回填表单后仍走 createAgent
export const generateAgent = (brief, model) =>
  unwrap(api.post('/api/agents/generate', { brief, model: model || null }))

// 更新 Agent
export const updateAgent = (id, data) => unwrap(api.put(`/api/agents/${id}`, data))

// 删除 Agent
export const deleteAgent = (id) => api.delete(`/api/agents/${id}`).then((r) => r.data)

// 克隆模板/公开 Agent 为自己的副本
export const cloneAgent = (id) => unwrap(api.post(`/api/agents/${id}/clone`))

// ===== V3.1 F-44.1 版本管理 =====

// 版本历史 (按版本号倒序)
export const getAgentVersions = (id) => unwrap(api.get(`/api/agents/${id}/versions`))

// 回滚到指定版本 (回滚动作本身会生成新版本, 历史不被覆盖)
export const rollbackAgentVersion = (id, version) =>
  unwrap(api.post(`/api/agents/${id}/versions/${version}/rollback`))

// 版本对比 (结构化 diff: 字段级差异; 列表字段忽略顺序)
export const diffAgentVersions = (id, left, right) =>
  unwrap(api.get(`/api/agents/${id}/versions/${left}/diff/${right}`))
