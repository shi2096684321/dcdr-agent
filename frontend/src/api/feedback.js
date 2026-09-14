import { api, unwrap } from './http'

// ===== V3.1 F-48 回答反馈 (点赞/点踩收集) =====

/**
 * 点踩原因标签清单 (code + 展示名)
 * 由后端下发以保证前后端口径一致, 勿在前端硬编码标签文案。
 */
export const getFeedbackReasonTags = () => unwrap(api.get('/api/feedback/reason-tags'))

/**
 * 提交/更新对某条回答的反馈。
 * 以 (sessionId, messageSeq) 定位回答; 同一用户重复提交会覆盖原评价。
 * @param {{sessionId: string, messageSeq: number, rating: 'up'|'down', reasonTags?: string[], comment?: string}} data
 */
export const submitFeedback = (data) => unwrap(api.post('/api/feedback', data))

/** 撤销对某条回答的反馈 (幂等) */
export const withdrawFeedback = (sessionId, messageSeq) =>
  unwrap(api.delete('/api/feedback', { params: { sessionId, messageSeq } }))

/**
 * 反馈概览 (好评率/原因分布/按模型/按天/最近差评)
 * @param {{days?: number, scope?: 'global'}} params scope=global 仅管理员生效
 */
export const getFeedbackStats = (params) => unwrap(api.get('/api/feedback/stats', { params }))

/**
 * 反馈明细分页
 * @param {{rating?: 'up'|'down', page?: number, size?: number, scope?: 'global'}} params
 */
export const listFeedback = (params) => unwrap(api.get('/api/feedback/list', { params }))
