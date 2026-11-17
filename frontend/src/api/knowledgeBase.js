import { api, unwrap } from './http'

// ===== 知识库 CRUD (F-21-001) =====
export const getKnowledgeBases = () => unwrap(api.get('/api/knowledge-bases'))
// 可选的切分策略(fixed / recursive / sentence / markdown)
export const getSplitterOptions = () => unwrap(api.get('/api/knowledge-bases/splitter-options'))
// 切分预览: 只切文本不入库, 用于调参时观察切分效果
export const previewSplit = (payload) => unwrap(api.post('/api/knowledge-bases/split-preview', payload))
// 查看文档已入库的分块(真实内容, 不含向量)
export const listChunks = (kbId, docId, page = 0, size = 20) =>
  unwrap(api.get(`/api/knowledge-bases/${kbId}/documents/${docId}/chunks`, { params: { page, size } }))
export const createKnowledgeBase = (data) => unwrap(api.post('/api/knowledge-bases', data))
export const getKnowledgeBase = (id) => unwrap(api.get(`/api/knowledge-bases/${id}`))
export const updateKnowledgeBase = (id, data) => unwrap(api.put(`/api/knowledge-bases/${id}`, data))
export const deleteKnowledgeBase = (id) => api.delete(`/api/knowledge-bases/${id}`).then(r => r.data)

// ===== 文档管理 (F-21-002) =====
export const listDocuments = (kbId) => unwrap(api.get(`/api/knowledge-bases/${kbId}/documents`))

// 上传文档: multipart/form-data, 后端 FilePart 接收
export const uploadDocument = (kbId, file) => {
  const fd = new FormData()
  fd.append('file', file)
  return unwrap(api.post(`/api/knowledge-bases/${kbId}/documents`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }))
}

export const deleteDocument = (kbId, docId) =>
  api.delete(`/api/knowledge-bases/${kbId}/documents/${docId}`).then(r => r.data)

export const reprocessDocument = (kbId, docId) =>
  unwrap(api.post(`/api/knowledge-bases/${kbId}/documents/${docId}/reprocess`))

// ===== 检索测试 (F-21-003) =====
export const searchKnowledgeBase = (kbId, query, topK) =>
  unwrap(api.post(`/api/knowledge-bases/${kbId}/search`, { query, topK }))

// 多知识库合并检索测试 (V3.2): 一次查询跨多个知识库检索
export const searchKnowledgeBases = (kbIds, query, topK) =>
  unwrap(api.post('/api/knowledge-bases/search', { kbIds, query, topK }))

// ===== 对话导出 (F-27) =====
// 直接拼 URL 触发浏览器下载, 避免 axios 处理 blob
export const buildExportUrl = (sessionId, format = 'markdown') =>
  `/api/sessions/${sessionId}/export?format=${encodeURIComponent(format)}`
