import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    allowedHosts: ['.ts.net'],
    proxy: {
      '/api': {
        // 默认打 8080; 需要临时连另一份后端(比如验证新代码的 8099 实例)时用
        // VITE_API_TARGET=http://localhost:8099 npm run dev
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
