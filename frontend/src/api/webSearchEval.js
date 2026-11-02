import { api, unwrap } from './http'

// ===== 联网搜索排序评测 =====
// 评测是"跑真实搜索、算排序指标"的动作, 一次 20 条 × N 个源, 20 秒左右且会真实计费,
// 所以 run 是异步接口: 提交后返回状态, 由调用方轮询 overview 的 state 看进度。

/** 概览: 可用源(含是否就绪) + 当前/最近一次运行状态 */
export const getEvalOverview = () => unwrap(api.get('/api/eval/web-search/overview'))

/** 历史报告列表(按时间倒序, 只含关键指标) */
export const listEvalReports = () => unwrap(api.get('/api/eval/web-search/reports'))

/** 单份报告详情(含逐条命中结果) */
export const getEvalReport = (file) =>
  unwrap(api.get(`/api/eval/web-search/reports/${encodeURIComponent(file)}`))

/** 触发一次评测; provider: both | searxng | deepseek */
export const runEval = (provider = 'both') =>
  unwrap(api.post('/api/eval/web-search/run', null, { params: { provider } }))
