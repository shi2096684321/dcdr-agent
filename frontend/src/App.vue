<template>
  <div class="app-container">
    <!-- V2.2: 认证状态未确认时显示加载占位, 避免子组件在 Token 校验完成前发起 API 请求 -->
    <div v-if="!authReady" class="auth-loading">
      <div class="auth-loading-spinner"></div>
      <p>正在加载...</p>
    </div>
    <!-- V2.2: 未登录时显示登录/注册页 -->
    <AuthView v-else-if="!authStore.isLoggedIn && showAuth" @success="onAuthSuccess" />
    <template v-else>
      <AppHeader
        v-model:current-model="currentModel"
        v-model:mode="mode"
        v-model:web-search="webSearch"
        :models="models"
        @show-auth="showAuth = true"
      />
      <main class="app-main">
        <ChatView v-if="mode==='chat'" :current-model="currentModel" v-model:web-search="webSearch" v-model:thinking-enabled="thinkingEnabled" :models="models" />
        <CreativeView v-else-if="mode==='creative'" :current-model="currentModel" :image-models="imageModels" :video-models="videoModels" @update:current-model="currentModel = $event" />
        <KnowledgeBaseView v-else-if="mode==='knowledge'" />
        <AgentView v-else-if="mode==='agent'" />
        <MemoryView v-else-if="mode==='memory'" />
        <WorkflowView v-else-if="mode==='workflow'" @open-dag="onOpenDag" />
        <DagEditorView v-else-if="mode==='dag'" :initial-workflow-id="dagTargetId" />
        <TemplateMarketView v-else-if="mode==='market'" />
        <ScheduledTaskView v-else-if="mode==='schedule'" />
        <RoutingView v-else-if="mode==='routing'" />
        <LlmCallLogView v-else-if="mode==='llm-logs'" />
        <MonitorView v-else-if="mode==='monitor'" />
        <!-- 检索健康度: 含用户原始提问, 仅管理员; 与顶栏入口、后端校验形成三层一致 -->
        <RetrievalHealthView v-else-if="mode==='retrieval-health' && authStore.isAdmin" />
        <AdminView v-else-if="mode==='admin' && authStore.isAdmin" />
      </main>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useModelStore } from '@/stores/model'
import AppHeader from '@/components/AppHeader.vue'
import ChatView from '@/views/ChatView.vue'
import CreativeView from '@/views/CreativeView.vue'
import KnowledgeBaseView from '@/views/KnowledgeBaseView.vue'
import AgentView from '@/views/AgentView.vue'
import MemoryView from '@/views/MemoryView.vue'
import WorkflowView from '@/views/WorkflowView.vue'
import DagEditorView from '@/views/DagEditorView.vue'
import TemplateMarketView from '@/views/TemplateMarketView.vue'
import ScheduledTaskView from '@/views/ScheduledTaskView.vue'
import RoutingView from '@/views/RoutingView.vue'
import LlmCallLogView from '@/views/LlmCallLogView.vue'
import MonitorView from '@/views/MonitorView.vue'
import RetrievalHealthView from '@/views/RetrievalHealthView.vue'
import AdminView from '@/views/AdminView.vue'
import AuthView from '@/views/AuthView.vue'

const authStore = useAuthStore()
const modelStore = useModelStore()
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

const imageModels = computed(() => modelStore.imageModels)
const videoModels = computed(() => modelStore.videoModels)

// 从工作流页跳转到 DAG 编辑器时携带目标工作流 ID, 供 DagEditorView 打开后自动选中
const dagTargetId = ref('')
function onOpenDag(id) {
  dagTargetId.value = id || ''
  mode.value = 'dag'
}

async function loadModels() {
  try {
    await modelStore.loadModels()
    // defaultChatModelName() 返回模型 name 字符串, 直接赋值
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

// V2.2: 监听 401 事件需在 onMounted 之前注册, 避免子组件请求先返回 401 时错过事件
function handleUnauthorized() {
  showAuth.value = true
  authReady.value = true
  mode.value = 'chat'
  ElMessage.warning('登录已过期, 请重新登录')
}
window.addEventListener('auth:unauthorized', handleUnauthorized)

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

// 双保险: 登录态变为已登录时(无论通过 emit 还是刷新恢复)都确保拉取模型列表
watch(() => authStore.isLoggedIn, (loggedIn, was) => {
  if (loggedIn && !was) loadModels()
})
</script>

<style scoped>
.app-container { height: 100vh; display: flex; flex-direction: column; }
.app-main { flex: 1; display: flex; overflow: hidden; }
.auth-loading {
  flex: 1; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 16px;
  color: #86909c;
}
.auth-loading-spinner {
  width: 32px; height: 32px;
  border: 3px solid #e5e6eb; border-top-color: #4080ff;
  border-radius: 50%; animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
