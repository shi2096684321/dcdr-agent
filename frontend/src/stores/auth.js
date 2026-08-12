import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'

const TOKEN_KEY = 'dcdr_access_token'
const REFRESH_KEY = 'dcdr_refresh_token'
const USER_KEY = 'dcdr_user_info'

/**
 * V2.2 认证状态管理。
 * <p>
 * Token 持久化到 localStorage, 刷新页面不丢失; 用户信息同步缓存以便 UI 立即渲染。
 */
export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(localStorage.getItem(TOKEN_KEY) || '')
  const refreshToken = ref(localStorage.getItem(REFRESH_KEY) || '')
  const user = ref(JSON.parse(localStorage.getItem(USER_KEY) || 'null'))
  const loading = ref(false)

  const isLoggedIn = computed(() => !!accessToken.value)
  const isAdmin = computed(() => user.value?.role === 'admin')
  const username = computed(() => user.value?.username || '')

  /** 设置 Token 与用户信息(登录/注册/刷新时调用) */
  function setAuth(authResp) {
    accessToken.value = authResp.accessToken || ''
    refreshToken.value = authResp.refreshToken || ''
    user.value = authResp.user || null
    localStorage.setItem(TOKEN_KEY, accessToken.value)
    localStorage.setItem(REFRESH_KEY, refreshToken.value)
    localStorage.setItem(USER_KEY, JSON.stringify(user.value))
  }

  /** 清除本地认证状态(登出/401 时调用) */
  function clearAuth() {
    accessToken.value = ''
    refreshToken.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(USER_KEY)
  }

  /** 登录 */
  async function login(name, password) {
    loading.value = true
    try {
      const resp = await authApi.login(name, password)
      setAuth(resp)
      return resp
    } finally {
      loading.value = false
    }
  }

  /** 注册 */
  async function register(name, password, email) {
    loading.value = true
    try {
      const resp = await authApi.register(name, password, email)
      setAuth(resp)
      return resp
    } finally {
      loading.value = false
    }
  }

  /** 登出: 调后端(无状态, 仅记录) + 清本地 */
  async function logout() {
    try { await authApi.logout() } catch (e) { /* 忽略 */ }
    clearAuth()
  }

  /** 拉取最新用户信息(页面初始化时校验 Token 有效性) */
  async function fetchCurrentUser() {
    if (!accessToken.value) return null
    try {
      const u = await authApi.getCurrentUser()
      user.value = u
      localStorage.setItem(USER_KEY, JSON.stringify(u))
      return u
    } catch (e) {
      // Token 失效, 清理本地状态
      clearAuth()
      return null
    }
  }

  return {
    accessToken, refreshToken, user, loading,
    isLoggedIn, isAdmin, username,
    setAuth, clearAuth, login, register, logout, fetchCurrentUser
  }
})
