import { api, unwrap } from './http'

// ===== V3.0-beta F-34 / F-39 监控与成本 =====

// 工作流执行监控总览 (params: { days, scope })
export const getWorkflowMonitor = (params = {}) =>
  unwrap(api.get('/api/monitor/workflows', { params: { days: 30, ...params } }))

// LLM token 成本统计 (params: { days, scope })
export const getCostStats = (params = {}) =>
  unwrap(api.get('/api/monitor/cost', { params: { days: 30, ...params } }))

// 我的配额使用情况
export const getQuotaUsage = () => unwrap(api.get('/api/monitor/quota'))
