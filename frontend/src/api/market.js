import { api, unwrap } from './http'

// ===== V3.0-beta F-35 模板市场 =====

// 市场模板列表 (params: { type, category, keyword, sort, page, size })
export const getMarketTemplates = (params = {}) =>
  unwrap(api.get('/api/market/templates', { params: { page: 0, size: 12, ...params } }))

// 我分享的模板
export const getMyShares = () => unwrap(api.get('/api/market/my-shares'))

// 分享 Agent / 工作流到市场
export const shareToMarket = (data) => unwrap(api.post('/api/market/share', data))

// 克隆市场模板
export const cloneMarketTemplate = (id) =>
  unwrap(api.post(`/api/market/templates/${id}/clone`))

// 评分 (1~5)
export const rateMarketTemplate = (id, rating) =>
  unwrap(api.post(`/api/market/templates/${id}/rate`, { rating }))

// 下架分享
export const unshareMarketTemplate = (id) =>
  api.delete(`/api/market/templates/${id}`).then((r) => r.data)
