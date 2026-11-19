import { defineStore, acceptHMRUpdate } from 'pinia'
import { getModels } from '@/api'

/**
 * 全局模型列表 store (F-规范)。
 * 模型列表属于跨页面共享数据, 统一在此加载与缓存, 避免各视图重复请求。
 */
export const useModelStore = defineStore('model', {
  state: () => ({
    models: [],
    loaded: false,
    loading: false
  }),
  getters: {
    chatModels: (s) => s.models.filter(m => m.type === 'CHAT'),
    imageModels: (s) => s.models.filter(m => m.type === 'IMAGE'),
    videoModels: (s) => s.models.filter(m => m.type === 'VIDEO')
  },
  actions: {
    /**
     * 加载模型列表。幂等: 已加载过或正在加载时直接复用, 不重复请求。
     * @param {boolean} force 是否强制刷新
     */
    async loadModels(force = false) {
      if (this.loaded || this.loading) {
        if (!force) return
      }
      this.loading = true
      try {
        const list = await getModels()
        // 防御: 接口异常返回 null 时, 任何 `models.find(...)` 都会把整页渲染打断
        this.models = Array.isArray(list) ? list : []
        this.loaded = true
      } finally {
        this.loading = false
      }
    },
    /** 默认对话模型名 (优先 isDefault, 否则取第一个对话模型) */
    defaultChatModelName() {
      const def = this.models.find(m => m.type === 'CHAT' && m.isDefault)
        || this.chatModels[0]
      return def ? def.name : ''
    },
    /**
     * 模型标识 → 人话展示名。
     * <p>
     * 落库/日志/Agent 绑定里存的都是稳定标识 name, 自定义模型形如 {@code custom:<uuid>} ——
     * 直接渲染就是一串 id, 谁看都不知道是哪条配置。这里统一翻译成用户自己起的名字。
     * <p>
     * 列表里查不到时(模型已删、或列表还没加载)**也绝不裸露 UUID**: 退化成「自定义模型 · 前 8 位」,
     * 至少能看出这是个自定义模型、并能在多条里区分开。
     */
    labelOf(name) {
      if (!name) return ''
      const hit = (this.models || []).find(m => m.name === name)
      if (hit) return hit.label || hit.model || name
      const s = String(name)
      if (s.startsWith('custom:')) return `自定义模型 · ${s.slice('custom:'.length, 'custom:'.length + 8)}`
      return s
    }
  }
})

/**
 * 让 store 的 actions 也能被 HMR 热替换。
 * <p>
 * 不加这段时, dev server 下改 store 只会在下一次 defineStore 时生效, <b>已挂载页面里的 store 实例
 * 仍然只有旧 actions</b> —— 模板一旦开始调用新 action(如 labelOf)就会 render 抛错,
 * 表现为"页面在、但整块内容空白/无数据", 只有手动硬刷新才能恢复。
 */
if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useModelStore, import.meta.hot))
}
