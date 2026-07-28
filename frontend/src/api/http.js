import axios from 'axios'

// 全局 axios 实例: baseURL 走 vite proxy 转发到后端 8080
export const api = axios.create({ baseURL: '/' })

// 统一提取后端响应 data 字段, 失败时抛出原始错误供调用方处理
export const unwrap = (p) => p.then(r => r.data.data)
