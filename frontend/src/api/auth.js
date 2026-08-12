import { api, unwrap } from './http'

// ===== V2.2 用户认证 API =====

// 用户注册
export const register = (username, password, email) =>
  unwrap(api.post('/api/auth/register', { username, password, email }))

// 用户登录
export const login = (username, password) =>
  unwrap(api.post('/api/auth/login', { username, password }))

// 刷新 Token
export const refreshToken = (refreshToken) =>
  unwrap(api.post('/api/auth/refresh', { refreshToken }))

// 获取当前登录用户
export const getCurrentUser = () => unwrap(api.get('/api/auth/me'))

// 修改密码
export const changePassword = (oldPassword, newPassword) =>
  unwrap(api.put('/api/auth/password', { oldPassword, newPassword }))

// 登出(后端无状态, 仅前端清理 Token)
export const logout = () => unwrap(api.post('/api/auth/logout'))

// ===== 管理后台 API (仅 admin) =====

export const adminListUsers = (page = 0, size = 20) =>
  unwrap(api.get('/api/admin/users', { params: { page, size } }))

export const adminGetUser = (id) => unwrap(api.get(`/api/admin/users/${id}`))

export const adminUpdateUser = (id, data) =>
  unwrap(api.put(`/api/admin/users/${id}`, data))

export const adminResetPassword = (id, newPassword) =>
  unwrap(api.post(`/api/admin/users/${id}/reset-password`, { newPassword }))

export const adminGetStats = () => unwrap(api.get('/api/admin/stats'))

export const adminQueryAuditLogs = (params) =>
  unwrap(api.get('/api/admin/audit-logs', { params }))
