import { api, unwrap } from './http'

// ===== V2.3 工作流编排 (F-25) =====

// 工作流列表 (当前用户)
export const getWorkflows = () => unwrap(api.get('/api/workflows'))

// 工作流详情 (含 steps)
export const getWorkflow = (id) => unwrap(api.get(`/api/workflows/${id}`))

// 创建工作流
export const createWorkflow = (data) => unwrap(api.post('/api/workflows', data))

// 更新工作流
export const updateWorkflow = (id, data) => unwrap(api.put(`/api/workflows/${id}`, data))

// 删除工作流
export const deleteWorkflow = (id) => api.delete(`/api/workflows/${id}`).then((r) => r.data)

// 执行工作流 (同步返回各步结果; 后端在 boundedElastic 线程池执行 LLM 调用)
export const executeWorkflow = (workflowId, userInput) =>
  unwrap(api.post('/api/workflows/execute', { workflowId, userInput }))
