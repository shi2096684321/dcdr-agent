<template>
  <div class="app-container">
    <!-- 装饰层：辉光 + 网格。纯视觉，不接任何事件 -->
    <div class="app-bg" aria-hidden="true">
      <div class="app-bg__glow app-bg__glow--cyan" />
      <div class="app-bg__glow app-bg__glow--violet" />
      <div class="app-bg__glow app-bg__glow--mint" />
      <div class="app-bg__grid" />
    </div>

    <!-- V2.2: 认证状态未确认时显示加载占位, 避免子组件在 Token 校验完成前发起 API 请求 -->
    <div v-if="!authReady" class="auth-loading">
      <div class="auth-loading-spinner" />
      <p>正在加载…</p>
    </div>
    <AuthView v-else-if="!authStore.isLoggedIn && showAuth" @success="onAuthSuccess" />
    <template v-else>
      <AppHeader
        v-model:current-model="currentModel"
        v-model:web-search="webSearch"
        :models="models"
        @show-auth="showAuth = true"
        @open-command="commandOpen = true"
        @update:mode="mode = $event"
      />

      <div class="app-body">
        <IconRail v-model="mode" :items="NAV_ITEMS" />

        <main class="app-main">
          <ChatView
            v-if="mode === 'chat'"
            :current-model="currentModel"
            v-model:web-search="webSearch"
            v-model:thinking-enabled="thinkingEnabled"
            :models="models"
          />
          <CreativeView
            v-else-if="mode === 'creative'"
            :current-model="currentModel"
            :image-models="imageModels"
            :video-models="videoModels"
            @update:current-model="currentModel = $event"
          />
          <KnowledgeBaseView v-else-if="mode === 'knowledge'" />
          <!-- 三个工作台: 各自把同类页面收在一个入口下, 内部二级切换 -->
          <ExpertHubView v-else-if="mode === 'expert'" />
          <AutoHubView v-else-if="mode === 'auto'" />
          <OpsHubView v-else-if="mode === 'ops'" />
          <MemoryView v-else-if="mode === 'memory'" />
          <AdminView v-else-if="mode === 'admin' && authStore.isAdmin" />
        </main>
      </div>
    </template>

    <CommandPalette
      v-model="commandOpen"
      :nav-items="NAV_ITEMS"
      :is-admin="authStore.isAdmin"
      @select="onCommandSelect"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useModelStore } from '@/stores/model'
import { useThemeStore } from '@/stores/theme'
import { NAV_ITEMS, NAV_SHORTCUTS } from '@/constants/nav'
import AppHeader from '@/components/AppHeader.vue'
import IconRail from '@/components/IconRail.vue'
import CommandPalette from '@/components/CommandPalette.vue'
import ChatView from '@/views/ChatView.vue'
import CreativeView from '@/views/CreativeView.vue'
import KnowledgeBaseView from '@/views/KnowledgeBaseView.vue'
// 三个工作台容器: 内部再挂各自的二级页(专家/技能/市场、工作流/DAG/定时、
// 监控/调用链/路由/检索健康), 故那些视图不在 App 层引用
import ExpertHubView from '@/views/ExpertHubView.vue'
import AutoHubView from '@/views/AutoHubView.vue'
import OpsHubView from '@/views/OpsHubView.vue'
import MemoryView from '@/views/MemoryView.vue'
import AdminView from '@/views/AdminView.vue'
import AuthView from '@/views/AuthView.vue'

const authStore = useAuthStore()
const modelStore = useModelStore()
const themeStore = useThemeStore()

const mode = ref('chat')
const models = computed(() => modelStore.models)
const currentModel = ref('')
const webSearch = ref(false)
// 深度思考开关(仅思考型模型生效): false 时后端传厂商参数真关闭, 省思考 token 与首字延迟
const thinkingEnabled = ref(true)
// 控制 AuthView 显示: 启动时若已有 Token 则尝试拉取用户, 失败时再显示登录页
const showAuth = ref(false)
// V2.2: 认证状态确认标志, false 时显示加载占位, 阻止子组件 onMounted 发起 API 请求
const authReady = ref(false)
// ⌘K 全局命令面板
const commandOpen = ref(false)

const imageModels = computed(() => modelStore.imageModels)
const videoModels = computed(() => modelStore.videoModels)

async function loadModels() {
  try {
    await modelStore.loadModels()
    currentModel.value = modelStore.defaultChatModelName()
  } catch (e) {
    // 失败也需可见, 否则表现为"登录后没加载模型"
    console.error('[App] 加载模型列表失败:', e)
  }
}

function onAuthSuccess() {
  showAuth.value = false
  authReady.value = true
  mode.value = 'chat'
  loadModels()
}

function onCommandSelect(cmd) {
  if (cmd.type === 'nav') {
    mode.value = cmd.value
    return
  }
  if (cmd.type === 'theme') {
    themeStore.setMode(cmd.value)
    return
  }
  if (cmd.type === 'action' && cmd.value === 'toggle-theme') {
    themeStore.toggle()
  }
}

/**
 * 全局快捷键：
 *   ⌘K / Ctrl+K 命令面板
 *   ⌘1…⌘7 切换一级入口（与图标轨道顺序一致）
 */
function onKeydown(e) {
  const meta = e.metaKey || e.ctrlKey
  if (!meta) return

  const key = e.key.toLowerCase()
  if (key === 'k') {
    e.preventDefault()
    commandOpen.value = !commandOpen.value
    return
  }

  const target = NAV_SHORTCUTS[e.key]
  if (target && authStore.isLoggedIn) {
    e.preventDefault()
    mode.value = target
  }
}

// V2.2: 监听 401 事件需在 onMounted 之前注册, 避免子组件请求先返回 401 时错过事件
function handleUnauthorized() {
  showAuth.value = true
  authReady.value = true
  mode.value = 'chat'
  ElMessage.warning('登录已过期, 请重新登录')
}

window.addEventListener('auth:unauthorized', handleUnauthorized)
window.addEventListener('keydown', onKeydown)

onMounted(async () => {
  // V2.2: 启动时校验已有 Token, 有效则恢复登录态, 无效则显示登录页
  if (authStore.accessToken) {
    const user = await authStore.fetchCurrentUser()
    if (!user) {
      showAuth.value = true
      authReady.value = true
      return
    }
  } else {
    showAuth.value = true
    authReady.value = true
    return
  }
  authReady.value = true
  await loadModels()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('auth:unauthorized', handleUnauthorized)
})

// 双保险: 登录态变为已登录时(无论通过 emit 还是刷新恢复)都确保拉取模型列表
watch(
  () => authStore.isLoggedIn,
  (loggedIn, was) => {
    if (loggedIn && !was) loadModels()
  }
)
</script>

<style scoped>
.app-container {
  position: relative;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--dcdr-bg);
  color: var(--dcdr-text-1);
  overflow: hidden;
}

/* ---------- 装饰层 ---------- */
.app-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  z-index: 0;
}

.app-bg__glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  /* 深色下辉光是"光"，浅色下它只能是"极淡的冷色阴影"，否则整屏发灰。
     强度由令牌控制 —— 不要在这里写 :global(:root[data-theme='light']) 覆盖：
     那个写法在 scoped 样式里会被错误解析，把 opacity 落到 <html> 上，
     导致整个页面（含正文）被压到一成不透明度。 */
  opacity: var(--dcdr-glow-opacity);
}


.app-bg__glow--cyan {
  top: -46%;
  left: 24%;
  width: 1100px;
  height: 1000px;
  background: radial-gradient(circle, rgba(46, 167, 255, 0.35) 0%, rgba(93, 82, 255, 0.14) 48%, transparent 72%);
}

.app-bg__glow--violet {
  bottom: -34%;
  left: -18%;
  width: 920px;
  height: 820px;
  background: radial-gradient(circle, rgba(147, 129, 255, 0.26) 0%, rgba(70, 46, 160, 0.1) 50%, transparent 74%);
}

.app-bg__glow--mint {
  right: -12%;
  bottom: -18%;
  width: 720px;
  height: 620px;
  background: radial-gradient(circle, rgba(19, 221, 196, 0.18) 0%, transparent 70%);
}

.app-bg__grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, var(--dcdr-grid) 1px, transparent 1px),
    linear-gradient(to bottom, var(--dcdr-grid) 1px, transparent 1px);
  background-size: 80px 80px;
  -webkit-mask-image: radial-gradient(ellipse 90% 72% at 50% 0%, #000 18%, transparent 76%);
  mask-image: radial-gradient(ellipse 90% 72% at 50% 0%, #000 18%, transparent 76%);
}

/* ---------- 结构 ---------- */
.app-body {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}

.app-main {
  flex: 1;
  display: flex;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

/* ---------- 认证加载 ---------- */
.auth-loading {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: var(--dcdr-text-2);
  font-size: 13px;
}

.auth-loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--dcdr-border);
  border-top-color: var(--dcdr-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  box-shadow: var(--dcdr-glow-accent);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
