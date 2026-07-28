import { api, unwrap } from './http'

// ===== 图片生成项目 =====
export const getProjects = () => unwrap(api.get('/api/projects'))
export const createProject = (data) => unwrap(api.post('/api/projects', data))
export const deleteProject = (id) => api.delete(`/api/projects/${id}`).then(r => r.data)
export const renameProject = (id, name) => api.put(`/api/projects/${id}`, { name }).then(r => r.data)

// ===== 生成任务 =====
export const generateImage = (data) => unwrap(api.post('/api/images/generate', data))
export const getGeneration = (id) => unwrap(api.get(`/api/generations/${id}`))
export const getGenerations = (projectId) => unwrap(api.get(`/api/projects/${projectId}/generations`))
