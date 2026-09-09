import { api, unwrap } from './http'

// V3.1 用户自定义模型配置 (OpenAI 兼容 LLM)
// 列表 (apiKey 恒为 null)
export const getUserModels = () => unwrap(api.get('/api/user-models'))

// 新增 (apiKey 必填, 加密落库)
export const createUserModel = (data) => unwrap(api.post('/api/user-models', data))

// 更新 (apiKey 留空表示保留原值)
export const updateUserModel = (id, data) => unwrap(api.put(`/api/user-models/${id}`, data))

// 删除
export const deleteUserModel = (id) => unwrap(api.delete(`/api/user-models/${id}`))