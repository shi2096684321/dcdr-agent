import { api, unwrap } from './http'

// ===== V2.3 定时任务 (F-26) =====

// 定时任务列表 (当前用户, 含最近运行状态)
export const getScheduledTasks = () => unwrap(api.get('/api/scheduled-tasks'))

// 任务详情
export const getScheduledTask = (id) => unwrap(api.get(`/api/scheduled-tasks/${id}`))

// 创建任务 (关联 Agent + cron + 输入)
export const createScheduledTask = (data) => unwrap(api.post('/api/scheduled-tasks', data))

// 更新任务 (更新 cron 会重新调度)
export const updateScheduledTask = (id, data) =>
  unwrap(api.put(`/api/scheduled-tasks/${id}`, data))

// 删除任务 (取消调度)
export const deleteScheduledTask = (id) =>
  api.delete(`/api/scheduled-tasks/${id}`).then((r) => r.data)

// 手动触发一次执行 (同步阻塞调用 LLM)
export const triggerScheduledTask = (id, inputMessage) =>
  unwrap(api.post(`/api/scheduled-tasks/${id}/trigger`, { inputMessage }))
