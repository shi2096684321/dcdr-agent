import { defineStore } from 'pinia'
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
        this.models = await getModels()
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
    }
  }
})
