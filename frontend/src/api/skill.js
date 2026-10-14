import { api, unwrap } from './http'

// ===== V3.1 技能管理 (/api/skills) =====
// 技能 = 按需加载的领域知识包(指令 + 参考文本), 纯文本、不含可执行内容。

// 可见技能列表(自己的 + 公开的; admin 为全部)
export const getSkills = () => unwrap(api.get('/api/skills'))

// 技能详情(含指令正文与参考资源内容)
export const getSkill = (id) => unwrap(api.get(`/api/skills/${id}`))

export const createSkill = (data) => unwrap(api.post('/api/skills', data))

export const updateSkill = (id, data) => unwrap(api.put(`/api/skills/${id}`, data))

export const deleteSkill = (id) => unwrap(api.delete(`/api/skills/${id}`))

// 启用/停用(停用后不再进入模型可见的技能目录)
export const setSkillEnabled = (id, enabled) =>
  unwrap(api.post(`/api/skills/${id}/enabled`, null, { params: { enabled } }))

// 导入技能包(同名默认拒绝; overwrite=true 才覆盖)
export const importSkillPackage = (skill, overwrite = false) =>
  unwrap(api.post('/api/skills/import', { skill, overwrite }))

// 导出技能包(可直接分享/入库的 JSON)
export const exportSkillPackage = (id) => unwrap(api.get(`/api/skills/${id}/export`))

// ===== V3.1 技能市场 (/api/skill-market) =====
// 外部依赖(SkillHub), 默认关闭。status 不受开关限制, 用于区分"未启用"与"上游异常"。

export const getSkillMarketStatus = () => unwrap(api.get('/api/skill-market/status'))

export const getSkillMarketCategories = () => unwrap(api.get('/api/skill-market/categories'))

// 可选排序方式(后端静态枚举, 不依赖上游; 由后端下发避免两边标签漂移)
export const getSkillMarketSorts = () => unwrap(api.get('/api/skill-market/sorts'))

// 市场列表 (params: { page, pageSize, keyword, category }); page 从 0 开始
export const getSkillMarketSkills = (params = {}) =>
  unwrap(api.get('/api/skill-market/skills', { params: { page: 0, pageSize: 12, ...params } }))

export const searchSkillMarket = (q, limit = 20) =>
  unwrap(api.get('/api/skill-market/search', { params: { q, limit } }))

export const getSkillMarketDetail = (slug, namespace) =>
  unwrap(api.get(`/api/skill-market/skills/${encodeURIComponent(slug)}`, {
    params: namespace ? { namespace } : {}
  }))

// 引入市场技能: 产出"元数据骨架 + 待补全正文", 且以停用状态入库
export const installSkillFromMarket = (data) => unwrap(api.post('/api/skill-market/install', data))
