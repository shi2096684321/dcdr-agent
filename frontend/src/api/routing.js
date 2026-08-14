import { api, unwrap } from './http'

// ===== V3.0-alpha F-30 智能路由记录 =====

// 路由记录列表 (分页; 管理员可传 userId=all 查全部)
export const getRoutingLogs = (params = {}) =>
  unwrap(api.get('/api/routing/logs', { params: { page: 0, size: 20, ...params } }))
