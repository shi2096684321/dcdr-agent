import axios from 'axios'

// 全局 axios 实例: baseURL 走 vite proxy 转发到后端 8080
export const api = axios.create({ baseURL: '/' })

// V2.2 请求拦截器: 自动注入 Authorization 头
api.interceptors.request.use((config) => {
  // 避免循环依赖: 直接读 localStorage 而非 import store
  const token = localStorage.getItem('dcdr_access_token')
  if (token && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// V2.2 响应拦截器: 401/419 清理本地 Token, 触发跳转登录页
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const code = error?.response?.data?.code
    if (code === 401 || code === 419 || error?.response?.status === 401) {
      // Token 失效或过期: 清理本地状态(避免循环依赖, 直接清 localStorage)
      localStorage.removeItem('dcdr_access_token')
      localStorage.removeItem('dcdr_refresh_token')
      localStorage.removeItem('dcdr_user_info')
      // 派发自定义事件, 由 App.vue 监听并跳转登录页(避免在此处 import router)
      window.dispatchEvent(new CustomEvent('auth:unauthorized'))
    }
    return Promise.reject(error)
  }
)

// 统一提取后端响应 data 字段。
// 注意: 后端业务异常(如 409 邮箱已被注册)通过 @ResponseStatus(OK) 以 HTTP 200 返回,
// 仅 code 非 200, 故此处需判断 code, 非成功时主动 reject, 让调用方 catch 能弹出 message。
class ApiError extends Error {
  constructor(payload) {
    super(payload?.message || '请求失败')
    this.name = 'ApiError'
    this.payload = payload // 完整后端响应 { code, message, data, error }
    this.code = payload?.code
  }
}

export const unwrap = (p) =>
  p.then((r) => {
    const body = r.data
    if (body && body.code !== undefined && body.code !== 200) {
      return Promise.reject(new ApiError(body))
    }
    return body?.data
  })

// 取后端响应的完整 data(含 code/message/data, 用于需要判断错误码的场景)
export const raw = (p) =>
  p.then((r) => {
    const body = r.data
    if (body && body.code !== undefined && body.code !== 200) {
      return Promise.reject(new ApiError(body))
    }
    return body
  })

// 从任意错误中提取可展示给用户的提示信息
export const extractMessage = (e) => {
  if (e instanceof ApiError) return e.message
  // 网络/HTTP 层错误(axios): 尝试读取 response.data.message
  if (e?.response?.data?.message) return e.response.data.message
  return e?.message || '操作失败'
}
