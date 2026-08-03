import { api, unwrap } from './http'

// ===== 视频生成任务 (V1.1) =====
// 后端采用异步任务 + 轮询模式: 提交后立即返回 pending 记录, 前端凭 id 轮询状态
export const generateVideo = (data) => unwrap(api.post('/api/videos/generate', data))
export const getVideoStatus = (id) => unwrap(api.get(`/api/videos/${id}/status`))
