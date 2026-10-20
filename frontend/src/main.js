import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 暗色变量：靠 <html class="dark"> 生效，具体色值在 theme.css 里被覆盖成我们的令牌
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import '@/styles/index.css'
import { useThemeStore } from '@/stores/theme'

const app = createApp(App)
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

const pinia = createPinia()
app.use(pinia)
app.use(ElementPlus)

// 主题要在挂载前初始化一次：index.html 的内联脚本已设好首屏，
// 这里补上"跟随系统"的监听，并保证 Pinia 内的状态与 DOM 一致
useThemeStore(pinia).init()

app.mount('#app')
