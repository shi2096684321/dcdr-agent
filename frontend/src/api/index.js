import axios from 'axios'

const api = axios.create({ baseURL: '/' })

export const getModels = () => api.get('/api/models').then(r => r.data.data)
export const getSessions = () => api.get('/api/sessions').then(r => r.data.data)
export const createSession = (data) => api.post('/api/sessions', data).then(r => r.data.data)
export const deleteSession = (id) => api.delete(`/api/sessions/${id}`).then(r => r.data)
export const renameSession = (id, title) => api.put(`/api/sessions/${id}/title`, { title }).then(r => r.data)
export const getMessages = (id) => api.get(`/api/sessions/${id}/messages`).then(r => r.data.data)
export const generateImage = (data) => api.post('/api/images/generate', data).then(r => r.data.data)
export const getProjects = () => api.get('/api/projects').then(r => r.data.data)
export const createProject = (data) => api.post('/api/projects', data).then(r => r.data.data)
export const deleteProject = (id) => api.delete(`/api/projects/${id}`).then(r => r.data)
export const getGenerations = (id) => api.get(`/api/projects/${id}/generations`).then(r => r.data.data)
export const uploadFile = (file) => { const fd = new FormData(); fd.append('file', file); return api.post('/api/files/upload', fd).then(r => r.data.data) }

export async function streamChat(request, onChunk, onDone, onError) {
  try {
    const resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request)
    })
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop()
      for (const line of lines) {
        if (line.startsWith('data:')) {
          try { const chunk = JSON.parse(line.slice(5).trim()); onChunk(chunk) } catch(e) {}
        }
      }
    }
    if (buffer.startsWith('data:')) { try { onChunk(JSON.parse(buffer.slice(5).trim())) } catch(e) {} }
    onDone()
  } catch (e) { onError(e) }
}
