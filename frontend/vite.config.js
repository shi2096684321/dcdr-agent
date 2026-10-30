import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { readFileSync } from 'node:fs'

/**
 * 顶栏展示的版本号从 package.json 注入。
 * <p>
 * 原先 {@code AppHeader.vue} 里硬编码着 "v3.1" —— 而 package.json 当时已是 3.2.0-ui。
 * 这类"两处都写、改一处不报错"的版本号必然会在某次发布时漏掉一处, 且没有任何迹象。
 * 注入之后版本号只有一个真值源, 升级只需改 package.json(以及同步 pom.xml)。
 */
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8'))

export default defineConfig({
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version)
  },
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
