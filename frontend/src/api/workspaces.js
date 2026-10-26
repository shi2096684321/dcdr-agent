import { api, unwrap } from './http'

// ===== V3.3 F-49 工作区 =====
// 说明: 绑定在「会话」而不是「用户」上 —— 同一用户不同会话可以操作不同目录,
// 避免"选过一次之后所有会话都能读写"的权限隐式扩散。

export const listWorkspaces = () => unwrap(api.get('/api/workspaces'))

export const createWorkspace = (data) => unwrap(api.post('/api/workspaces', data))

export const updateWorkspace = (id, data) => unwrap(api.patch(`/api/workspaces/${id}`, data))

// 只删记录, 不动磁盘文件 —— 前端文案必须写清楚, 否则用户不敢点
export const deleteWorkspace = (id) => api.delete(`/api/workspaces/${id}`).then((r) => r.data)

// 目录枚举: 默认关闭, 仅管理员; 参数 path 为空时从用户主目录开始
export const browseDirs = (path) =>
  unwrap(api.get('/api/workspaces/browse', { params: path ? { path } : {} }))

export const bindWorkspace = (sessionId, workspaceId) =>
  unwrap(api.put(`/api/sessions/${sessionId}/workspace`, { workspaceId }))

export const unbindWorkspace = (sessionId) =>
  api.delete(`/api/sessions/${sessionId}/workspace`).then((r) => r.data)

// 可撤回点: 会话内的用户消息列表
export const getCheckpoints = (sessionId) => unwrap(api.get(`/api/sessions/${sessionId}/checkpoints`))

// 撤回: scope=FILES 只回滚文件; ALL 同时删除该消息及其之后的消息
export const rewindSession = (sessionId, messageId, scope = 'ALL') =>
  unwrap(api.post(`/api/sessions/${sessionId}/rewind`, { messageId: String(messageId), scope }))
