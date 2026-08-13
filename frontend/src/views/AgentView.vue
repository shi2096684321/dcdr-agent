<template>
  <div class="agent-mode">
    <!-- 左侧: Agent 列表 -->
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed}">
      <div class="sidebar-header">
        <button class="new-btn gradient-bg" @click="openCreateDialog">+ 新建 Agent</button>
        <button class="collapse-btn" @click="sidebarCollapsed = true" title="收起">«</button>
      </div>
      <div class="agent-list">
        <div v-for="a in agents" :key="a.id"
             class="agent-item" :class="{active: a.id === currentAgentId}"
             @click="selectAgent(a.id)">
          <div class="agent-avatar">{{ avatarLetter(a.name) }}</div>
          <div class="agent-info">
            <div class="agent-name">
              {{ a.name }}
              <el-tag v-if="a.isTemplate" size="small" type="warning" class="tag">模板</el-tag>
              <el-tag v-else-if="a.isPublic" size="small" type="success" class="tag">公开</el-tag>
              <el-tag v-else size="small" type="info" class="tag">私有</el-tag>
            </div>
            <div class="agent-meta">
              <span v-if="a.model">{{ a.model }}</span>
              <span v-else>默认模型</span>
              <span>·</span>
              <span>{{ (a.tools && a.tools.length) ? a.tools.length + ' 工具' : '无工具' }}</span>
            </div>
            <div class="agent-desc" v-if="a.description" :title="a.description">{{ a.description }}</div>
          </div>
        </div>
        <div v-if="!agents.length && !loading" class="empty-tip">
          暂无 Agent<br/>点击上方按钮创建或克隆模板
        </div>
      </div>
    </aside>
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false">»</button>

    <!-- 右侧: Agent 详情 / 模板库 -->
    <div class="agent-main">
      <!-- 顶部 Tab: 我的 Agent / 模板库 -->
      <el-tabs v-model="activeTab" class="agent-tabs" @tab-change="onTabChange">
        <el-tab-pane label="Agent 详情" name="detail">
          <template v-if="currentAgent">
            <div class="detail-toolbar">
              <div class="detail-title">
                <h2>{{ currentAgent.name }}</h2>
                <el-tag v-if="currentAgent.isTemplate" size="small" type="warning">预设模板</el-tag>
                <el-tag v-else-if="currentAgent.isPublic" size="small" type="success">公开</el-tag>
                <el-tag v-else size="small" type="info">私有</el-tag>
              </div>
              <div class="detail-actions">
                <el-button v-if="currentAgent.editable" type="primary" size="small" @click="openEditDialog">编辑</el-button>
                <el-button v-if="!currentAgent.editable || currentAgent.isTemplate" size="small" @click="cloneAgent(currentAgent)">克隆</el-button>
                <el-button v-if="currentAgent.editable" type="danger" size="small" @click="removeAgent(currentAgent)">删除</el-button>
              </div>
            </div>

            <div class="detail-body">
              <div class="detail-row">
                <div class="detail-label">描述</div>
                <div class="detail-value">{{ currentAgent.description || '—' }}</div>
              </div>
              <div class="detail-row">
                <div class="detail-label">模型</div>
                <div class="detail-value">{{ currentAgent.model || '系统默认' }}</div>
              </div>
              <div class="detail-row">
                <div class="detail-label">温度</div>
                <div class="detail-value">{{ currentAgent.temperature != null ? currentAgent.temperature : '默认' }}</div>
              </div>
              <div class="detail-row">
                <div class="detail-label">工具</div>
                <div class="detail-value">
                  <el-tag v-for="t in (currentAgent.tools || [])" :key="t" size="small" class="tool-tag">{{ t }}</el-tag>
                  <span v-if="!currentAgent.tools || !currentAgent.tools.length">无</span>
                </div>
              </div>
              <div class="detail-row">
                <div class="detail-label">知识库</div>
                <div class="detail-value">
                  <el-tag v-for="k in (currentAgent.kbIds || [])" :key="k" size="small" type="warning" class="tool-tag">{{ k }}</el-tag>
                  <span v-if="!currentAgent.kbIds || !currentAgent.kbIds.length">无</span>
                </div>
              </div>
              <div class="detail-row detail-prompt">
                <div class="detail-label">系统提示词</div>
                <pre class="prompt-box">{{ currentAgent.systemPrompt || '(空)' }}</pre>
              </div>
            </div>
          </template>
          <div v-else class="empty-detail">
            <div class="empty-icon">🤖</div>
            <p>选择左侧 Agent 查看详情, 或新建一个 Agent</p>
          </div>
        </el-tab-pane>

        <el-tab-pane label="模板库" name="templates">
          <div class="template-grid">
            <div v-for="t in templates" :key="t.id" class="template-card">
              <div class="template-avatar">{{ avatarLetter(t.name) }}</div>
              <div class="template-name">{{ t.name }}</div>
              <div class="template-desc">{{ t.description || '—' }}</div>
              <div class="template-meta">
                <span v-if="t.model">{{ t.model }}</span>
                <span v-if="t.temperature != null">· 温度 {{ t.temperature }}</span>
              </div>
              <el-button size="small" type="primary" plain @click="cloneAgent(t)">克隆为我的 Agent</el-button>
            </div>
            <div v-if="!templates.length && !templatesLoading" class="empty-tip">
              暂无预设模板
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 创建/编辑 Agent 对话框 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑 Agent' : '新建 Agent'" width="640px" @closed="resetForm">
      <el-form :model="form" label-width="100px" label-position="right">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如: 翻译助手" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="Agent 用途简介" />
        </el-form-item>
        <el-form-item label="头像 URL">
          <el-input v-model="form.avatar" placeholder="可选, 留空则用首字母占位" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="5"
                    placeholder="定义 Agent 的角色与行为, 如: 你是一个专业翻译, 支持中英互译..." />
        </el-form-item>
        <el-form-item label="模型">
          <el-select v-model="form.model" placeholder="留空使用系统默认" clearable filterable style="width:100%">
            <el-option v-for="m in chatModels" :key="m.name" :label="m.label" :value="m.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="温度">
          <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" show-input :show-input-controls="false" />
        </el-form-item>
        <el-form-item label="启用工具">
          <el-select v-model="form.tools" multiple filterable allow-create default-first-option
                     placeholder="输入工具名 (如: calculator, time, web_search, knowledge_base_search)" style="width:100%">
            <el-option v-for="t in suggestedTools" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="authStore.isAdmin" label="关联知识库">
          <el-select v-model="form.kbIds" multiple filterable placeholder="选择知识库 (可选)" style="width:100%">
            <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="公开">
          <el-switch v-model="form.isPublic" active-text="其他用户可使用此 Agent" inactive-text="仅自己可见" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useModelStore } from '@/stores/model'
import {
  getAgents, getAgentTemplates, createAgent, updateAgent, deleteAgent, cloneAgent as cloneAgentApi,
  getKnowledgeBases, extractMessage
} from '@/api'

const authStore = useAuthStore()
const modelStore = useModelStore()

const loading = ref(false)
const agents = ref([])
const currentAgentId = ref('')
const currentAgent = computed(() => agents.value.find(a => a.id === currentAgentId.value) || null)
const sidebarCollapsed = ref(false)
const activeTab = ref('detail')

const templates = ref([])
const templatesLoading = ref(false)

// 对话框表单
const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const form = reactive({
  id: '', name: '', description: '', avatar: '', systemPrompt: '',
  model: '', temperature: 0.7, tools: [], kbIds: [], isPublic: false
})

// 模型列表 (全局共享, 从 modelStore 读取; 留空代表使用系统默认模型)
const chatModels = computed(() => modelStore.chatModels)
const knowledgeBases = ref([])
// 常见工具名建议 (实际可用工具以后端 ToolRegistry 注册为准)
const suggestedTools = ['calculator', 'time', 'web_search', 'knowledge_base_search']

function avatarLetter(name) {
  return (name || '?').charAt(0).toUpperCase()
}

async function loadAgents() {
  loading.value = true
  try {
    agents.value = await getAgents()
    if (!currentAgentId.value && agents.value.length) {
      currentAgentId.value = agents.value[0].id
    }
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载 Agent 列表失败')
  } finally {
    loading.value = false
  }
}

async function loadTemplates() {
  templatesLoading.value = true
  try {
    templates.value = await getAgentTemplates()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载模板库失败')
  } finally {
    templatesLoading.value = false
  }
}

async function loadKnowledgeBases() {
  if (!authStore.isAdmin) return
  try {
    knowledgeBases.value = await getKnowledgeBases()
  } catch (e) {
    // 静默失败, 不阻塞 Agent 管理
    console.warn('[AgentView] 加载知识库列表失败:', e)
  }
}

function selectAgent(id) {
  currentAgentId.value = id
  activeTab.value = 'detail'
}

function onTabChange(tab) {
  if (tab === 'templates' && !templates.value.length) loadTemplates()
}

function openCreateDialog() {
  editing.value = false
  resetForm()
  dialogVisible.value = true
}

function openEditDialog() {
  if (!currentAgent.value) return
  editing.value = true
  const a = currentAgent.value
  form.id = a.id
  form.name = a.name
  form.description = a.description || ''
  form.avatar = a.avatar || ''
  form.systemPrompt = a.systemPrompt || ''
  form.model = a.model || ''
  form.temperature = a.temperature != null ? Number(a.temperature) : 0.7
  form.tools = [...(a.tools || [])]
  form.kbIds = [...(a.kbIds || [])]
  form.isPublic = !!a.isPublic
  dialogVisible.value = true
}

function resetForm() {
  form.id = ''
  form.name = ''
  form.description = ''
  form.avatar = ''
  form.systemPrompt = ''
  form.model = ''
  form.temperature = 0.7
  form.tools = []
  form.kbIds = []
  form.isPublic = false
}

async function submitForm() {
  if (!form.name || !form.name.trim()) {
    ElMessage.warning('请填写 Agent 名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      description: form.description || null,
      avatar: form.avatar || null,
      systemPrompt: form.systemPrompt || null,
      model: form.model || null,
      temperature: form.temperature != null ? Number(form.temperature) : null,
      tools: form.tools || [],
      kbIds: form.kbIds || [],
      isPublic: !!form.isPublic
    }
    if (editing.value) {
      await updateAgent(form.id, payload)
      ElMessage.success('Agent 已更新')
    } else {
      const created = await createAgent(payload)
      ElMessage.success('Agent 已创建')
      currentAgentId.value = created.id
    }
    dialogVisible.value = false
    await loadAgents()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeAgent(agent) {
  try {
    await ElMessageBox.confirm(`确定删除 Agent "${agent.name}" 吗?`, '提示', { type: 'warning' })
    await deleteAgent(agent.id)
    ElMessage.success('已删除')
    if (currentAgentId.value === agent.id) currentAgentId.value = ''
    await loadAgents()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(extractMessage(e) || '删除失败')
  }
}

async function cloneAgent(agent) {
  try {
    const cloned = await cloneAgentApi(agent.id)
    ElMessage.success(`已克隆为 "${cloned.name}"`)
    await loadAgents()
    currentAgentId.value = cloned.id
    activeTab.value = 'detail'
  } catch (e) {
    ElMessage.error(extractMessage(e) || '克隆失败')
  }
}

onMounted(() => {
  // 确保全局模型列表已加载 (modelStore 内部幂等, 已加载则无副作用)
  modelStore.loadModels()
  loadAgents()
  loadKnowledgeBases()
})
</script>

<style scoped>
.agent-mode { flex: 1; display: flex; overflow: hidden; }
.sidebar { width: 280px; background: #fafbfc; border-right: 1px solid #e8e8e8; display: flex; flex-direction: column; transition: width 0.2s; }
.sidebar.collapsed { width: 0; overflow: hidden; }
.sidebar-expand { position: absolute; left: 0; top: 56px; width: 20px; height: 40px; background: #fff; border: 1px solid #e8e8e8; border-left: none; border-radius: 0 6px 6px 0; cursor: pointer; z-index: 10; }
.sidebar-header { padding: 12px; display: flex; gap: 8px; align-items: center; }
.new-btn { flex: 1; padding: 8px 12px; border: none; border-radius: 8px; color: #fff; font-size: 13px; cursor: pointer; }
.collapse-btn { width: 28px; height: 32px; border: 1px solid #e8e8e8; background: #fff; border-radius: 6px; cursor: pointer; color: #86909c; }
.agent-list { flex: 1; overflow-y: auto; padding: 0 8px 12px; }
.agent-item { display: flex; gap: 10px; padding: 10px; border-radius: 8px; cursor: pointer; margin-bottom: 4px; }
.agent-item:hover { background: #f0f2f5; }
.agent-item.active { background: linear-gradient(135deg, rgba(102,126,234,0.12) 0%, rgba(118,75,162,0.10) 100%); }
.agent-avatar { width: 36px; height: 36px; border-radius: 50%; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; flex-shrink: 0; }
.agent-info { flex: 1; min-width: 0; }
.agent-name { font-size: 13px; font-weight: 500; color: #303133; display: flex; align-items: center; gap: 4px; }
.agent-name .tag { transform: scale(0.85); }
.agent-meta { font-size: 11px; color: #86909c; margin-top: 2px; display: flex; gap: 4px; }
.agent-desc { font-size: 11px; color: #a0a4ab; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty-tip { text-align: center; color: #a0a4ab; font-size: 13px; padding: 40px 12px; line-height: 1.8; }

.agent-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: #fff; }
.agent-tabs { flex: 1; display: flex; flex-direction: column; padding: 12px 20px 0; overflow: hidden; }
.agent-tabs :deep(.el-tabs__content) { flex: 1; overflow-y: auto; }

.detail-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 8px 0 16px; border-bottom: 1px solid #f0f2f5; margin-bottom: 16px; }
.detail-title { display: flex; align-items: center; gap: 8px; }
.detail-title h2 { margin: 0; font-size: 18px; }
.detail-actions { display: flex; gap: 8px; }

.detail-body { padding-bottom: 20px; }
.detail-row { display: flex; padding: 10px 0; border-bottom: 1px dashed #f5f5f5; }
.detail-label { width: 90px; color: #86909c; font-size: 13px; flex-shrink: 0; }
.detail-value { flex: 1; color: #303133; font-size: 13px; display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.tool-tag { margin: 0; }
.detail-prompt { flex-direction: column; gap: 8px; }
.prompt-box { background: #f7f8fa; border: 1px solid #e8e8e8; border-radius: 6px; padding: 12px; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; margin: 0; max-height: 320px; overflow-y: auto; font-family: inherit; }

.empty-detail { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #a0a4ab; gap: 12px; }
.empty-icon { font-size: 56px; }

.template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; padding: 8px 0; }
.template-card { background: #fafbfc; border: 1px solid #e8e8e8; border-radius: 10px; padding: 16px; display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.template-avatar { width: 44px; height: 44px; border-radius: 50%; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: 18px; }
.template-name { font-size: 15px; font-weight: 600; }
.template-desc { font-size: 12px; color: #86909c; line-height: 1.5; min-height: 36px; }
.template-meta { font-size: 11px; color: #a0a4ab; }
</style>
