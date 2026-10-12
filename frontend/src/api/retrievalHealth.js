import { api, unwrap } from './http'

// ===== 检索健康度 (RAG 可观测性, 仅管理员) =====
//
// 回答"线上文档持续增长后, 检索效果有没有漂移" —— 与固定评测集互补:
//   评测集(RagRetrievalEvalIT / RagRetrievalEvalLiveIT) → 看"代码改动"是否让效果变差
//   本接口                                            → 看"真实流量"是否在漂移
//
// 后端: RetrievalHealthController#overview, 数据来自 llm_call_logs.steps_json 的 rag 步骤。
// 权限: 仅管理员; 普通用户调用会返回 403。

/**
 * 检索健康度总览
 * @param {{days?: number}} params days = 统计窗口天数 (1~180, 默认 30)
 */
export const getRetrievalHealth = (params = {}) =>
  unwrap(api.get('/api/retrieval-health/overview', { params: { days: 30, ...params } }))
