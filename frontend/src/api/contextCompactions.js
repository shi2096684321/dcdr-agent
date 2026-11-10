import { api, unwrap } from './http'

// ===== 上下文压缩记录 (V3.5) =====
//
// 一次压缩/降级/作废/清除/改写一条, 支撑两个入口:
//   - 运维台「压缩记录」页: 全局按会话/事件/用户筛选
//   - 对话右侧上下文面板「压缩记录」抽屉: 固定带 sessionId, 只看当前会话
//
// params: page / size / sessionId / eventType / userId(仅管理员可传 all)
export const getContextCompactions = (params = {}) =>
  unwrap(api.get('/api/context-compactions', { params: { page: 0, size: 20, ...params } }))
