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

// 测试推送 (V3.3): 立即向该任务的推送渠道发一条测试消息, 不触发任务执行
export const testScheduledTaskNotify = (id) =>
  unwrap(api.post(`/api/scheduled-tasks/${id}/notify-test`))

// 预览消息模板 (V3.3): 用示例数据渲染成功/失败两份消息, 不发送
export const previewScheduledTaskNotify = (id, channel) =>
  unwrap(api.get(`/api/scheduled-tasks/${id}/notify-preview`, { params: { channel } }))

// 执行历史列表 (V3.3): 倒序, 仅摘要
export const getScheduledTaskRuns = (id, limit = 20) =>
  unwrap(api.get(`/api/scheduled-tasks/${id}/runs`, { params: { limit } }))

// 执行历史详情: 含完整正文
export const getScheduledTaskRun = (id, runId) =>
  unwrap(api.get(`/api/scheduled-tasks/${id}/runs/${runId}`))
