<template>
  <!-- 用户可见文案统一叫「专家」; 组件名与接口字段沿用 agent —— 只改展示, 不动数据契约 -->
  <div class="agent-mode">
    <!-- 左侧: Agent 列表 -->
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed}">
      <div class="sidebar-header">
        <button class="new-btn gradient-bg" @click="openCreateDialog">+ 新建专家</button>
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
              <el-tag v-if="isOthersAgent(a)" size="small" type="danger" effect="plain" class="tag">他人</el-tag>
            </div>
            <div class="agent-meta">
              <span v-if="a.model">{{ a.model }}</span>
              <span v-else>默认模型</span>
              <span>·</span>
              <span>{{ (a.tools && a.tools.length) ? a.tools.length + ' 工具' : '无工具' }}</span>
            </div>
            <div class="agent-owner" v-if="isOthersAgent(a)">归属: {{ ownerLabel(a) }}</div>
            <div class="agent-desc" v-if="a.description" :title="a.description">{{ a.description }}</div>
          </div>
        </div>
        <div v-if="!agents.length && !loading" class="empty-tip">
          暂无专家<br/>点击上方按钮创建或克隆模板
        </div>
      </div>
    </aside>
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false">»</button>

    <!-- 右侧: Agent 详情 / 模板库 -->
    <div class="agent-main">
      <!-- 顶部 Tab: 我的 Agent / 模板库 -->
      <el-tabs v-model="activeTab" class="agent-tabs" @tab-change="onTabChange">
        <el-tab-pane label="专家详情" name="detail">
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
                <el-button size="small" @click="openVersions">版本历史</el-button>
                <el-button v-if="!currentAgent.editable || currentAgent.isTemplate || isOthersAgent(currentAgent)"
                           size="small" @click="cloneAgent(currentAgent)">克隆</el-button>
                <el-button v-if="currentAgent.editable" type="danger" size="small" @click="removeAgent(currentAgent)">删除</el-button>
              </div>
            </div>

            <div class="detail-body">
              <div class="detail-row" v-if="authStore.isAdmin">
                <div class="detail-label">归属用户</div>
                <div class="detail-value">
                  {{ ownerLabel(currentAgent) }}
                  <el-tag v-if="isOthersAgent(currentAgent)" size="small" type="danger" effect="plain">他人</el-tag>
                </div>
              </div>
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
                <div class="detail-label">角色设定</div>
                <pre class="prompt-box">{{ currentAgent.persona || '(空)' }}</pre>
              </div>
            </div>
          </template>
          <div v-else class="empty-detail">
            <div class="empty-icon">🤖</div>
            <p>选择左侧专家查看详情, 或新建一个专家</p>
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
              <el-button size="small" type="primary" plain @click="cloneAgent(t)">克隆为我的专家</el-button>
            </div>
            <div v-if="!templates.length && !templatesLoading" class="empty-tip">
              暂无预设模板
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 创建/编辑 Agent 对话框 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="640px" @closed="resetForm">
      <el-alert v-if="editing && isOthersAgent(editingTarget)" type="warning" :closable="false" show-icon
                class="owner-alert"
                title="正在编辑他人专家"
                :description="`该专家归属于 ${ownerLabel(editingTarget)}, 保存后将直接改变其使用效果。`" />
      <el-form :model="form" label-width="100px" label-position="right">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如: 翻译助手" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="专家用途简介" />
        </el-form-item>
        <el-form-item label="头像 URL">
          <el-input v-model="form.avatar" placeholder="可选, 留空则用首字母占位" />
        </el-form-item>
        <el-form-item label="角色设定">
          <el-input v-model="form.persona" type="textarea" :rows="5"
                    placeholder="设定该专家的角色与行为, 如: 你是一个专业翻译, 支持中英互译... (由平台转述给模型, 不覆盖平台安全约束)" />
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
          <el-switch v-model="form.isPublic" active-text="其他用户可使用此专家" inactive-text="仅自己可见" />
        </el-form-item>
        <el-form-item label="变更说明" v-if="editing">
          <el-input v-model="form.changeNote" placeholder="本次改动说明 (存入版本历史, 便于回滚时辨认)" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- V3.1 F-44.1 版本历史 / 对比 / 回滚 -->
    <el-dialog v-model="versionsVisible" title="版本历史" width="700px">
      <div class="version-hint">
        每次创建 / 修改 / 回滚都会生成一版完整快照。勾选任意两版点「对比」查看字段级差异;
        「回滚」会把当前定义还原到该版本, 并再生成一版新快照 —— 历史不会被覆盖, 可继续回滚。
      </div>
      <el-table :data="versions" size="small" v-loading="versionsLoading" @selection-change="onVersionSelect">
        <el-table-column type="selection" width="42" />
        <el-table-column label="版本" width="68">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="110" show-overflow-tooltip />
        <el-table-column label="变更说明" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.changeNote || '—' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="76" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="warning" text @click="doRollback(row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!versions.length && !versionsLoading" class="empty-tip">暂无版本记录</div>

      <div class="version-actions">
        <el-button size="small" :disabled="selectedVersions.length !== 2" @click="doDiff">
          对比选中两版 (已选 {{ selectedVersions.length }}/2, 左=较旧)
        </el-button>
      </div>

      <div v-if="diff" class="diff-box">
        <div class="diff-head">
          <span>v{{ diff.leftVersion }} → v{{ diff.rightVersion }}</span>
          <span class="diff-sub">{{ fmtTime(diff.leftCreatedAt) }} → {{ fmtTime(diff.rightCreatedAt) }}</span>
        </div>
        <div v-if="changedFields.length" class="diff-list">
          <div v-for="f in changedFields" :key="f.field" class="diff-row">
            <div class="diff-label">{{ f.label }}</div>
            <div class="diff-boxes">
              <pre class="diff-cell old">{{ f.left || '—' }}</pre>
              <pre class="diff-cell new">{{ f.right || '—' }}</pre>
            </div>
          </div>
        </div>
        <div v-else class="empty-tip">两版内容完全一致</div>
      </div>
      <template #footer>
        <el-button @click="versionsVisible = false">关闭</el-button>
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
  getAgentVersions, rollbackAgentVersion, diffAgentVersions,
  getKnowledgeBases, extractMessage, adminListUsers
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
  id: '', name: '', description: '', avatar: '', persona: '',
  model: '', temperature: 0.7, tools: [], kbIds: [], isPublic: false,
  changeNote: ''
})

// 模型列表 (全局共享, 从 modelStore 读取; 留空代表使用系统默认模型)
const chatModels = computed(() => modelStore.chatModels)
const knowledgeBases = ref([])
// 常见工具名建议 (实际可用工具以后端 ToolRegistry 注册为准)
const suggestedTools = ['calculator', 'time', 'web_search', 'knowledge_base_search', 'code_interpreter', 'tool_result_read']

// userId → username 映射 (仅管理员加载, 用于展示 Agent 归属用户)
const userNames = ref({})
const currentUserId = computed(() => authStore.user?.id || '')
// 正在编辑的 Agent (用于判断是否在编辑他人 Agent)
const editingTarget = computed(() => agents.value.find(a => a.id === form.id) || null)
const dialogTitle = computed(() => {
  if (!editing.value) return '新建专家'
  return isOthersAgent(editingTarget.value) ? '编辑他人专家' : '编辑专家'
})

function avatarLetter(name) {
  return (name || '?').charAt(0).toUpperCase()
}

/** 是否为「他人」的 Agent (仅管理员场景有意义; 无归属的存量数据不算) */
function isOthersAgent(agent) {
  if (!authStore.isAdmin || !agent || !agent.userId) return false
  return agent.userId !== currentUserId.value
}

/** 归属用户展示名: 优先用户名, 兜底用户 ID */
function ownerLabel(agent) {
  if (!agent || !agent.userId) return '无归属(平台)'
  if (agent.userId === currentUserId.value) return '自己'
  return userNames.value[agent.userId] || agent.userId
}

async function loadAgents() {
  loading.value = true
  try {
    agents.value = await getAgents()
    if (!currentAgentId.value && agents.value.length) {
      currentAgentId.value = agents.value[0].id
    }
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载专家列表失败')
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

// 管理员: 加载用户列表用于展示 Agent 归属 (失败仅退化为展示 userId)
async function loadUserNames() {
  if (!authStore.isAdmin) return
  try {
    const resp = await adminListUsers(0, 200)
    const map = {}
    for (const u of (resp.content || [])) {
      if (u && u.id) map[u.id] = u.username || u.id
    }
    userNames.value = map
  } catch (e) {
    console.warn('[AgentView] 加载用户列表失败:', e)
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
  form.persona = a.persona || ''
  form.model = a.model || ''
  form.temperature = a.temperature != null ? Number(a.temperature) : 0.7
  form.tools = [...(a.tools || [])]
  form.kbIds = [...(a.kbIds || [])]
  form.isPublic = !!a.isPublic
  form.changeNote = '' // 每次编辑都是新版本, 说明不复用上一版
  dialogVisible.value = true
}

function resetForm() {
  form.id = ''
  form.name = ''
  form.description = ''
  form.avatar = ''
  form.persona = ''
  form.model = ''
  form.temperature = 0.7
  form.tools = []
  form.kbIds = []
  form.isPublic = false
  form.changeNote = ''
}

async function submitForm() {
  if (!form.name || !form.name.trim()) {
    ElMessage.warning('请填写专家名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      description: form.description || null,
      avatar: form.avatar || null,
      persona: form.persona || null,
      model: form.model || null,
      temperature: form.temperature != null ? Number(form.temperature) : null,
      tools: form.tools || [],
      kbIds: form.kbIds || [],
      isPublic: !!form.isPublic,
      changeNote: form.changeNote || null
    }
    if (editing.value) {
      await updateAgent(form.id, payload)
      ElMessage.success('专家已更新')
    } else {
      const created = await createAgent(payload)
      ElMessage.success('专家已创建')
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
    const ownerTip = isOthersAgent(agent) ? `(归属: ${ownerLabel(agent)}) ` : ''
    await ElMessageBox.confirm(`确定删除${ownerTip}专家 "${agent.name}" 吗?`, '提示', { type: 'warning' })
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

// ===== V3.1 F-44.1 版本历史 / 对比 / 回滚 =====
const versionsVisible = ref(false)
const versionsLoading = ref(false)
const versions = ref([])
const selectedVersions = ref([])
const diff = ref(null)
/** 只展示发生变化的字段: 未变化的字段对"看改了什么"没有信息量 */
const changedFields = computed(() => (diff.value?.fields || []).filter(f => f.changed))

async function openVersions() {
  if (!currentAgent.value) return
  versionsVisible.value = true
  diff.value = null
  selectedVersions.value = []
  versionsLoading.value = true
  try {
    versions.value = await getAgentVersions(currentAgent.value.id)
  } catch (e) {
    versions.value = []
    ElMessage.error(extractMessage(e) || '加载版本历史失败')
  } finally {
    versionsLoading.value = false
  }
}

function onVersionSelect(rows) {
  selectedVersions.value = rows || []
}

async function doDiff() {
  if (selectedVersions.value.length !== 2 || !currentAgent.value) return
  // 左固定为较旧版本: 避免"反向对比"让红/绿含义反转
  const [older, newer] = [...selectedVersions.value].sort((x, y) => x.version - y.version)
  try {
    diff.value = await diffAgentVersions(currentAgent.value.id, older.version, newer.version)
  } catch (e) {
    diff.value = null
    ElMessage.error(extractMessage(e) || '版本对比失败')
  }
}

async function doRollback(row) {
  if (!currentAgent.value) return
  try {
    await ElMessageBox.confirm(
      `将 Agent「${currentAgent.value.name}」回滚到 v${row.version}?\n回滚会再生成一版新快照, 历史不会被覆盖。`,
      '回滚确认', { type: 'warning' }
    )
  } catch {
    return // 用户取消
  }
  try {
    await rollbackAgentVersion(currentAgent.value.id, row.version)
    ElMessage.success(`已回滚到 v${row.version}`)
    await loadAgents()
    versions.value = await getAgentVersions(currentAgent.value.id)
    diff.value = null
  } catch (e) {
    ElMessage.error(extractMessage(e) || '回滚失败')
  }
}

/** 时间展示 (后端 LocalDateTime 无时区, 直接本地化即可) */
function fmtTime(t) {
  return t ? new Date(t).toLocaleString('zh-CN') : '—'
}

onMounted(() => {
  // 确保全局模型列表已加载 (modelStore 内部幂等, 已加载则无副作用)
  modelStore.loadModels()
  loadAgents()
  loadKnowledgeBases()
  loadUserNames()
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
.agent-owner { font-size: 11px; color: #f56c6c; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agent-desc { font-size: 11px; color: #a0a4ab; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty-tip { text-align: center; color: #a0a4ab; font-size: 13px; padding: 40px 12px; line-height: 1.8; }

.agent-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: #fff; }
.agent-tabs { flex: 1; display: flex; flex-direction: column; padding: 12px 20px 0; overflow: hidden; }
.agent-tabs :deep(.el-tabs__content) { flex: 1; overflow-y: auto; }

.detail-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 8px 0 16px; border-bottom: 1px solid #f0f2f5; margin-bottom: 16px; }
.detail-title { display: flex; align-items: center; gap: 8px; }
.detail-title h2 { margin: 0; font-size: 18px; }
.detail-actions { display: flex; gap: 8px; }
.owner-alert { margin-bottom: 14px; }

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

/* ===== V3.1 F-44.1 版本历史 / 对比 ===== */
.version-hint { font-size: 12px; color: #86909c; line-height: 1.7; margin-bottom: 10px; }
.version-actions { margin-top: 12px; }
.diff-box { margin-top: 14px; border: 1px solid #e8e8e8; border-radius: 8px; padding: 12px; background: #fafbfc; }
.diff-head { font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 10px; display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; }
.diff-sub { font-weight: 400; font-size: 12px; color: #a0a4ab; }
.diff-list { display: flex; flex-direction: column; gap: 10px; max-height: 320px; overflow-y: auto; }
.diff-row { display: flex; gap: 10px; }
.diff-label { width: 74px; flex-shrink: 0; font-size: 12px; color: #606266; text-align: right; padding-top: 6px; }
.diff-boxes { flex: 1; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; min-width: 0; }
.diff-cell { margin: 0; font-size: 12px; line-height: 1.6; padding: 8px; border-radius: 6px; white-space: pre-wrap; word-break: break-word; max-height: 120px; overflow-y: auto; font-family: inherit; }
.diff-cell.old { background: #fef0f0; border: 1px solid #fde2e2; color: #909399; }
.diff-cell.new { background: #f0f9eb; border: 1px solid #e1f3d8; color: #303133; }
</style>
