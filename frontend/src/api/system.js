import { api, unwrap } from './http'

// 模型列表
export const getModels = () => unwrap(api.get('/api/models'))

// 生成线程池运行态
export const getThreadPoolStats = () => unwrap(api.get('/api/system/thread-pool'))

// 文件上传
export const uploadFile = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return unwrap(api.post('/api/files/upload', fd))
}
