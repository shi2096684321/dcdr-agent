import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { readFileSync } from 'node:fs'

/**
 * 顶栏展示的版本号从 package.json 注入(唯一真值源)。
 * <p>
 * 原先 {@code AppHeader.vue} 里硬编码着 "v3.1" —— 而 package.json 当时已是 3.2.0-ui,
 * 于是界面长期显示着两个大版本之前的号。注入之后升级只需改 package.json。
 */
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8'))

/**
 * 版本号的虚拟模块。
 * <p>
 * <b>为什么不用 {@code define} 注入</b>: {@code define} 由 vite:define 插件处理,
 * 它按文件扩展名过滤, <b>不覆盖 .vue 的 {@code <script setup>}</b> —— 实测 dev 下
 * {@code __APP_VERSION__} 会原样留在代码里, 浏览器直接抛
 * {@code ReferenceError: __APP_VERSION__ is not defined}, 组件挂载失败。
 * 更麻烦的是 build 时因为走 esbuild 压缩又恰好能替换, 表现为
 * 「构建成功、开发白屏」, 很难联想到是版本号注入的锅。
 * <p>
 * 虚拟模块在 dev 与 build 走的是同一条 import 解析路径, 行为一致。
 */
const appVersionPlugin = {
  name: 'app-version',
  resolveId(id) {
    if (id === 'virtual:app-version') {
      return '\0virtual:app-version'
    }
  },
  load(id) {
    if (id === '\0virtual:app-version') {
      return `export default ${JSON.stringify(pkg.version)}`
    }
  }
}

export default defineConfig({
  plugins: [vue(), appVersionPlugin],
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
