import { api, unwrap } from './http'

// ===== 联网健康度 (联网搜索 / 抓取的可观测性, 仅管理员) =====
//
// 回答"线上联网效果有没有在漂移" —— 与固定评测互补:
//   评测(WebExtractFixtureTest 存档页面 / 搜索金标) → 看"代码改动"是否让效果变差
//   本接口                                          → 看"真实流量"是否在漂移
//
// 与检索健康度的差别: 检索退化是"命中了但不太相关"(看相似度);
// 联网退化是"一条都没拿到", 且它在对话界面里完全看不出来(模型会拿空上下文继续作答)。
//
// 后端: WebHealthController#overview, 数据来自 llm_call_logs.steps_json 的 web 步骤。
// 权限: 仅管理员; 普通用户调用会返回 403。

/**
 * 联网健康度总览
 * @param {{days?: number}} params days = 统计窗口天数 (1~180, 默认 30)
 */
export const getWebHealth = (params = {}) =>
  unwrap(api.get('/api/web-health/overview', { params: { days: 30, ...params } }))

/**
 * 联网用量与成本
 *
 * 与健康度是两件事: 健康度看**效果**(空结果率/抓取成功率), 这里看**量**。
 * 按 provider 拆分是必须的 —— DeepSeek 按次计费、自建 SearXNG 只有服务器成本,
 * 混成一个总数算出来的钱是错的。
 *
 * estimatedCost 为 null 表示未配置 `agent-tools.web.pricing`, 此时界面应显示"未配置单价",
 * 而不是 0 元 —— 假数字比没数字更危险。
 *
 * @param {{days?: number}} params days = 统计窗口天数 (1~180, 默认 30)
 */
export const getWebUsage = (params = {}) =>
  unwrap(api.get('/api/web-health/usage', { params: { days: 30, ...params } }))
