import { api, unwrap } from './http'

// ===== V3.0-alpha F-31 Webhook 触发层 =====

// Webhook 列表 (当前用户)
export const getWebhooks = () => unwrap(api.get('/api/webhooks'))

// Webhook 详情
export const getWebhook = (id) => unwrap(api.get(`/api/webhooks/${id}`))

// 创建 Webhook (绑定工作流)
export const createWebhook = (data) => unwrap(api.post('/api/webhooks', data))

// 更新 Webhook
export const updateWebhook = (id, data) => unwrap(api.put(`/api/webhooks/${id}`, data))

// 删除 Webhook
export const deleteWebhook = (id) => api.delete(`/api/webhooks/${id}`).then((r) => r.data)
