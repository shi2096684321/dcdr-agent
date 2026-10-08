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

// LLM 服务调用与熔断概览 (F-40.2/F-40.4, 内置面板, 免 Prometheus/Grafana)
export const getLlmMonitor = () => unwrap(api.get('/api/monitor/llm'))

// ===== V3.1 F-44.2 成本分析报表 =====

// 成本报表 (params: { from, to, granularity: day|week|month, topN, scope })
export const getCostReport = (params = {}) =>
  unwrap(api.get('/api/monitor/cost/report', { params: { granularity: 'day', topN: 10, ...params } }))

// 成本报表导出 CSV (params: { ...同上, dimension: trend|model|user|source })
// 注意: 不能用 unwrap —— 响应体是二进制流; 返回 Blob 由调用方触发下载
export const exportCostCsv = (params = {}) =>
  api
    .get('/api/monitor/cost/export', {
      params: { granularity: 'day', topN: 10, dimension: 'trend', ...params },
      responseType: 'blob'
    })
    .then((r) => r.data)
