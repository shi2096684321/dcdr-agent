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

// ===== V3.0-alpha F-33 执行容错与人工介入 =====

// 执行记录列表 (分页; 支持关键词搜索 + 状态/工作流/触发方式筛选)
// params: { page, size, status, workflowId, triggerType, keyword }
export const getWorkflowExecutions = (params = {}) =>
  unwrap(api.get('/api/workflows/executions', {
    params: { page: 0, size: 10, ...params }
  }))

// 执行详情 (含各步状态/输出/错误)
export const getWorkflowExecution = (id) => unwrap(api.get(`/api/workflows/executions/${id}`))

// 断点续跑 (从 failed 步骤继续执行)
export const resumeWorkflowExecution = (id) =>
  unwrap(api.post(`/api/workflows/executions/${id}/resume`))

// 人工审批通过 (从 paused 审批节点继续执行)
export const approveWorkflowExecution = (id, comment) =>
  unwrap(api.post(`/api/workflows/executions/${id}/approve`, { comment }))

// 取消执行
export const cancelWorkflowExecution = (id) =>
  unwrap(api.post(`/api/workflows/executions/${id}/cancel`))

