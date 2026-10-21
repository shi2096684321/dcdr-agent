<template>
  <!-- 用户可见文案统一叫「专家」; 组件名与接口字段沿用 agent —— 只改展示, 不动数据契约 -->
  <div class="agent-mode">
    <!-- 顶栏: 视图切换 + 搜索 + 新建 -->
    <div class="agent-topbar">
      <template v-if="activeTab === 'detail'">
        <div class="topbar-search">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>
          <input v-model="keyword" placeholder="搜索专家名称、描述或标签" />
        </div>
        <div class="topbar-spacer"></div>
        <span class="agent-count">{{ filteredAgents.length }} 位专家</span>
        <button class="plain-btn" @click="switchToTemplates">从模板创建</button>
        <button class="new-btn" @click="openCreateDialog">+ 新建专家</button>
      </template>
      <template v-else>
        <button class="back-btn" @click="activeTab = 'detail'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M15 6l-6 6 6 6"/></svg>
          返回
        </button>
        <span class="topbar-title">模板库</span>
        <div class="topbar-spacer"></div>
        <span class="agent-count">{{ templates.length }} 个模板</span>
      </template>
    </div>

    <!-- ===== 我的专家: 卡片网格 + 右栏 ===== -->
    <template v-if="activeTab==='detail'">
      <div class="agent-body">
        <div class="agent-scroll" v-loading="loading">
          <div v-if="filteredAgents.length" class="agent-grid">
            <div v-for="a in filteredAgents" :key="a.id"
                 class="agent-card" :class="{active: a.id === currentAgentId}"
                 @click="openAgentDetail(a)">
              <div class="card-top">
                <div class="agent-avatar">{{ avatarLetter(a.name) }}</div>
                <div class="card-actions" @click.stop>
                  <button v-if="a.editable" class="card-act" title="编辑" @click="editAgent(a)">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20h4l9.5-9.5a2.1 2.1 0 0 0-3-3L5 17v3z"/></svg>
                </button>
                  <button class="card-act" title="版本历史" @click="openVersionsFor(a)">⏱</button>
                  <button v-if="!a.editable || a.isTemplate || isOthersAgent(a)" class="card-act" title="克隆" @click="cloneAgent(a)">⧉</button>
                  <button v-if="a.editable" class="card-act danger" title="删除" @click="removeAgent(a)">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>
                </button>
                </div>
              </div>
              <div class="card-name">
                {{ a.name }}
                <el-tag v-if="a.isTemplate" size="small" type="warning" class="tag">模板</el-tag>
                <el-tag v-else-if="a.isPublic" size="small" type="success" class="tag">公开</el-tag>
                <el-tag v-else size="small" type="info" class="tag">私有</el-tag>
                <el-tag v-if="isOthersAgent(a)" size="small" type="danger" effect="plain" class="tag">他人</el-tag>
              </div>
              <div class="card-desc" :title="a.description">{{ a.description || '暂无描述' }}</div>
              <div v-if="agentTags(a).length" class="card-tags">
                <span v-for="t in agentTags(a).slice(0, 3)" :key="t" class="chip">{{ t }}</span>
              </div>
              <div class="card-meta">
                {{ a.profession || a.model || '默认模型' }} · {{ (a.tools && a.tools.length) ? a.tools.length + ' 工具' : '无工具' }} · {{ (a.kbIds && a.kbIds.length) ? a.kbIds.length + ' 知识库' : '无知识库' }}
              </div>
              <div class="card-meta dim">更新于 {{ relTime(a.updatedAt) }}</div>
            </div>
          </div>
          <div v-else-if="!loading" class="empty-tip">
            暂无专家<br/>点击右上角「新建专家」开始，或去模板库克隆一个
          </div>
        </div>

        <!-- 右栏: 技能 + 运行概览 -->
        <aside class="agent-rail">
          <div class="rail-card">
            <div class="rail-head">
              已装技能
              <button class="rail-link" @click="goSkills">去管理</button>
            </div>
            <div v-if="skills.length" class="rail-list">
              <div v-for="s in skills.slice(0, 6)" :key="s.id" class="rail-row">
                <span class="rail-name" :title="s.description">{{ s.displayName || s.name }}</span>
                <span class="rail-state" :class="{off: !s.enabled}">{{ s.enabled ? '启用中' : '已停用' }}</span>
              </div>
            </div>
            <div v-else class="rail-empty">还没有安装技能</div>
          </div>
          <div class="rail-card">
            <div class="rail-head">运行概览</div>
            <div class="stat-row">
              <span class="stat-label">累计对话</span>
              <span class="stat-value">{{ stats.totalTurns != null ? stats.totalTurns : '—' }}</span>
            </div>
            <div class="stat-row">
              <span class="stat-label">LLM 调用<em v-if="stats.sampleSize">（近 {{ stats.sampleSize }} 次）</em></span>
              <span class="stat-value">{{ stats.llmCalls != null ? stats.llmCalls : '—' }}</span>
            </div>
            <div class="stat-row">
              <span class="stat-label">Token 用量<em v-if="stats.sampleSize">（近 {{ stats.sampleSize }} 次）</em></span>
              <span class="stat-value">{{ stats.tokens || '—' }}</span>
            </div>
            <div class="stat-row">
              <span class="stat-label">最近一次调用</span>
              <span class="stat-value">{{ stats.lastCallAt ? relTime(stats.lastCallAt) : '—' }}</span>
            </div>
          </div>
        </aside>
      </div>
    </template>

    <!-- ===== 模板库 ===== -->
    <div v-else class="agent-body">
      <div class="agent-scroll" v-loading="templatesLoading">
        <div class="template-grid">
          <div v-for="t in templates" :key="t.id" class="template-card">
            <div class="template-avatar">{{ avatarLetter(t.name) }}</div>
            <div class="template-name">{{ t.name }}</div>
            <div class="template-desc">{{ t.description || '—' }}</div>
            <div class="template-meta">
              <span v-if="t.profession">{{ t.profession }} · </span>
              <span v-if="t.model">{{ t.model }}</span>
              <span v-if="t.temperature != null">· 温度 {{ t.temperature }}</span>
            </div>
            <el-button size="small" type="primary" plain @click="cloneAgent(t)">克隆为我的专家</el-button>
          </div>
          <div v-if="!templates.length && !templatesLoading" class="empty-tip">
            暂无预设模板
          </div>
        </div>
      </div>
    </div>

    <!-- 专家详情弹窗 -->
    <el-dialog v-model="detailVisible" :title="detailTitle" width="720px" class="agent-detail-dialog">
      <template v-if="currentAgent">
        <div class="detail-toolbar">
          <div class="detail-title">
            <div class="agent-avatar lg">{{ avatarLetter(currentAgent.name) }}</div>
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
          <div class="detail-row" v-if="currentAgent.profession">
            <div class="detail-label">头衔</div>
            <div class="detail-value">{{ currentAgent.profession }}</div>
          </div>
          <div class="detail-row">
            <div class="detail-label">描述</div>
            <div class="detail-value">{{ currentAgent.description || '—' }}</div>
          </div>
          <div class="detail-row" v-if="agentTags(currentAgent).length">
            <div class="detail-label">领域标签</div>
            <div class="detail-value">
              <el-tag v-for="t in agentTags(currentAgent)" :key="t" size="small" class="tool-tag">{{ t }}</el-tag>
            </div>
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
    </el-dialog>

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
        <el-button size="small" @click="versionsVisible = false">关闭</el-button>
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
  getKnowledgeBases, extractMessage, adminListUsers,
  getSkills, getLlmCallLogs
} from '@/api'

const emit = defineEmits(['go-skills'])

const authStore = useAuthStore()
const modelStore = useModelStore()

const loading = ref(false)
const agents = ref([])
const currentAgentId = ref('')
const currentAgent = computed(() => agents.value.find(a => a.id === currentAgentId.value) || null)
const activeTab = ref('detail')
const keyword = ref('')

const templates = ref([])
const templatesLoading = ref(false)

// 详情弹窗(卡片点击进入; 编辑/版本/克隆/删除都挂在详情里)
const detailVisible = ref(false)
const detailTitle = computed(() => currentAgent.value ? `专家详情 · ${currentAgent.value.name}` : '专家详情')

// 右栏: 技能与运行概览(全部取真实接口, 没有数据就显示 —)
const skills = ref([])
const stats = reactive({ totalTurns: null, llmCalls: null, tokens: '', sampleSize: 0, lastCallAt: null })

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

/** 领域标签: 后端已解析成数组, 兜底防空 */
function agentTags(a) {
  return Array.isArray(a?.tags) ? a.tags.filter(Boolean) : []
}

const filteredAgents = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return agents.value
  return agents.value.filter(a =>
    [a.name, a.description, a.profession, ...agentTags(a)]
      .some(v => (v || '').toLowerCase().includes(k))
  )
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

/** 相对时间: 卡片上的"更新于 x 小时前" */
function relTime(t) {
  if (!t) return '—'
  const d = new Date(t).getTime()
  if (Number.isNaN(d)) return '—'
  const min = Math.floor((Date.now() - d) / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  const day = Math.floor(h / 24)
  if (day < 30) return `${day} 天前`
  return new Date(t).toLocaleDateString('zh-CN')
}

function fmtTokens(n) {
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
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

/** 已装技能(自己的 + 公开的), 用于右栏速览 */
async function loadSkills() {
  try {
    skills.value = await getSkills() || []
  } catch (e) {
    console.warn('[AgentView] 加载技能列表失败:', e)
  }
}

/**
 * 运行概览: 拉 20 条调用链日志做样本统计。
 * 累计对话取 totalElements(全量); LLM 调用数与 Token 只能按这 20 条样本统计,
 * 所以标签里写清"近 N 次" —— 不拿样本量冒充全量。
 * 明细不在这里展开: 完整调用链在运维台的「调用链」页, 专家页只留聚合指标。
 */
async function loadUsage() {
  try {
    const page = await getLlmCallLogs({ page: 0, size: 20 })
    const list = page?.content || []
    stats.totalTurns = page?.totalElements ?? null
    stats.sampleSize = list.length
    stats.lastCallAt = list.length ? list[0].createdAt : null
    let calls = 0
    let tokens = 0
    for (const l of list) {
      calls += Number(l.llmCalls || 0)
      tokens += Number(l.totalInTokens || 0) + Number(l.totalOutTokens || 0)
    }
    stats.llmCalls = calls
    stats.tokens = list.length ? fmtTokens(tokens) : ''
  } catch (e) {
    console.warn('[AgentView] 加载调用统计失败:', e)
  }
}

/** 跳转技能管理页(由外壳 ExpertHubView 切页签) */
function goSkills() {
  emit('go-skills')
}

function selectAgent(id) {
  currentAgentId.value = id
  activeTab.value = 'detail'
}

function switchToTemplates() {
  activeTab.value = 'templates'
  if (!templates.value.length) loadTemplates()
}

function onTabChange(tab) {
  if (tab === 'templates' && !templates.value.length) loadTemplates()
}

/** 点卡片看详情 */
function openAgentDetail(a) {
  currentAgentId.value = a.id
  detailVisible.value = true
}

/** 卡片上的编辑: 先定位当前专家, 再走原编辑表单 */
function editAgent(a) {
  currentAgentId.value = a.id
  openEditDialog()
}

function openVersionsFor(a) {
  currentAgentId.value = a.id
  openVersions()
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
    // 删除会连带归档该专家名下的专属记忆 —— 必须在确认框里说清, 否则用户事后会以为记忆丢了
    await ElMessageBox.confirm(
      `确定删除${ownerTip}专家 "${agent.name}" 吗？其名下的专属记忆会被一并归档（可在记忆页恢复）。`,
      '提示', { type: 'warning' })
    await deleteAgent(agent.id)
    ElMessage.success('已删除')
    if (currentAgentId.value === agent.id) currentAgentId.value = ''
    detailVisible.value = false
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
    detailVisible.value = true
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
  loadSkills()
  loadUsage()
})
</script>

<style scoped>
.agent-mode { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-width: 0; }

/* ===== 顶栏 ===== */
.agent-topbar {
  flex: none; display: flex; align-items: center; gap: 12px;
  padding: 12px 20px; border-bottom: 1px solid var(--dcdr-border);
  background: var(--dcdr-surface-faint);
}
.seg { display: flex; gap: 4px; padding: 3px; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-pill); }
.seg-item {
  height: 26px; line-height: 26px; padding: 0 14px; border-radius: var(--dcdr-radius-pill);
  font-size: 12.5px; color: var(--dcdr-text-3); cursor: pointer; user-select: none;
  transition: background 0.15s, color 0.15s;
}
.seg-item:hover { color: var(--dcdr-text-1); }
.seg-item.active { color: var(--dcdr-accent-soft); background: var(--dcdr-accent-wash); box-shadow: inset 0 0 0 1px var(--dcdr-accent-line); }
.topbar-title { font-size: 13px; font-weight: 600; color: var(--dcdr-text-1); letter-spacing: 0.2px; }
.back-btn {
  display: flex; align-items: center; gap: 4px; height: 30px; padding: 0 10px 0 6px;
  border: 1px solid var(--dcdr-border); background: transparent; color: var(--dcdr-text-2);
  border-radius: var(--dcdr-radius-inner); font-size: 12.5px; cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}
.back-btn svg { width: 14px; height: 14px; }
.back-btn:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); }
.plain-btn {
  height: 32px; padding: 0 14px; border-radius: var(--dcdr-radius-inner);
  border: 1px solid var(--dcdr-border); background: transparent;
  color: var(--dcdr-text-2); font-size: 12.5px; cursor: pointer; white-space: nowrap;
  transition: color 0.15s, border-color 0.15s;
}
.plain-btn:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); }
.topbar-search {
  display: flex; align-items: center; gap: 8px; width: 300px; height: 32px; padding: 0 12px;
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-inner); color: var(--dcdr-text-3);
  transition: border-color 0.15s;
}
.topbar-search:focus-within { border-color: var(--dcdr-accent); }
.topbar-search svg { width: 14px; height: 14px; flex: none; }
.topbar-search input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; color: var(--dcdr-text-1); font-size: 13px; }
.topbar-search input::placeholder { color: var(--dcdr-text-3); }
.topbar-spacer { flex: 1; }
.agent-count { font-family: var(--dcdr-font-mono); font-size: 10.5px; letter-spacing: 0.5px; color: var(--dcdr-text-3); white-space: nowrap; }
.new-btn {
  height: 32px; padding: 0 16px; border-radius: var(--dcdr-radius-inner);
  border: 1px solid var(--dcdr-accent-line); background: var(--dcdr-accent-wash);
  color: var(--dcdr-accent-soft); font-size: 13px; font-weight: 500; cursor: pointer;
  white-space: nowrap; transition: background 0.2s, border-color 0.2s;
}
.new-btn:hover { background: var(--dcdr-active); border-color: var(--dcdr-accent); }

/* ===== 主体: 网格 + 右栏 ===== */
.agent-body { flex: 1; min-height: 0; display: flex; gap: 16px; padding: 16px 20px 0; }
.agent-scroll { flex: 1; min-width: 0; overflow-y: auto; padding-bottom: 4px; }
.agent-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; align-content: start; }
@media (max-width: 1280px) { .agent-grid { grid-template-columns: 1fr; } }

.agent-card {
  display: flex; flex-direction: column; gap: 8px; padding: 16px;
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-inner); cursor: pointer;
  transition: border-color 0.15s, background 0.15s, transform 0.15s;
}
.agent-card:hover { border-color: var(--dcdr-border-strong); transform: translateY(-1px); }
.agent-card.active { border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); }
.card-top { display: flex; align-items: center; justify-content: space-between; }
.agent-avatar {
  width: 36px; height: 36px; border-radius: 10px; flex-shrink: 0;
  background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%);
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-weight: 600; font-size: 15px;
}
.agent-avatar.lg { width: 40px; height: 40px; border-radius: 12px; font-size: 16px; }
.card-actions { display: flex; gap: 4px; opacity: 0; transition: opacity 0.15s; }
.agent-card:hover .card-actions { opacity: 1; }
.card-act {
  width: 26px; height: 26px; border: 1px solid var(--dcdr-border); background: var(--dcdr-surface);
  color: var(--dcdr-text-3); border-radius: 7px; cursor: pointer; line-height: 1; padding: 0;
  display: inline-flex; align-items: center; justify-content: center;
  transition: color 0.15s, border-color 0.15s;
}
.card-act svg { width: 12px; height: 12px; }
.card-act:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); }
.card-act.danger:hover { color: var(--dcdr-danger); border-color: var(--dcdr-danger); }
.card-name { display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 600; color: var(--dcdr-text-1); }
.card-name .tag { transform: scale(0.85); }
.card-desc {
  font-size: 12.5px; color: var(--dcdr-text-3); line-height: 1.55; min-height: 39px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.card-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.chip {
  font-size: 11px; color: var(--dcdr-text-1); background: var(--dcdr-hover);
  border: 1px solid var(--dcdr-border-strong); border-radius: var(--dcdr-radius-pill); padding: 2px 9px;
}
.card-meta { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.3px; color: var(--dcdr-text-3); }
.card-meta.dim { opacity: 0.85; }

/* ===== 右栏 ===== */
.agent-rail { width: 300px; flex: none; display: flex; flex-direction: column; gap: 12px; overflow-y: auto; padding-bottom: 4px; }
.rail-card { background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner); padding: 13px 14px; }
.rail-head { display: flex; align-items: center; justify-content: space-between; font-size: 12.5px; font-weight: 600; color: var(--dcdr-text-2); margin-bottom: 10px; }
.rail-link { border: none; background: transparent; color: var(--dcdr-accent-soft); font-size: 11.5px; cursor: pointer; padding: 0; }
.rail-link:hover { text-decoration: underline; }
.rail-list { display: flex; flex-direction: column; gap: 8px; }
.rail-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 12.5px; }
.rail-name { color: var(--dcdr-text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rail-state { font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-success); flex: none; }
.rail-state.off { color: var(--dcdr-text-3); }
.rail-empty { font-size: 12px; color: var(--dcdr-text-3); }
.stat-row { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; padding: 8px 0; border-bottom: 1px dashed var(--dcdr-border); }
.stat-row:last-child { border-bottom: none; }
.stat-label { font-size: 12.5px; color: var(--dcdr-text-3); }
.stat-label em { font-style: normal; font-size: 10.5px; opacity: 0.85; }
.stat-value { font-family: var(--dcdr-font-mono); font-size: 15px; font-weight: 600; color: var(--dcdr-text-1); }

/* ===== 模板库 ===== */
.template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; padding: 4px 0 16px; align-content: start; }
.template-card { background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner); padding: 16px; display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.template-avatar { width: 44px; height: 44px; border-radius: 50%; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: 18px; }
.template-name { font-size: 15px; font-weight: 600; }
.template-desc { font-size: 12px; color: var(--dcdr-text-3); line-height: 1.5; min-height: 36px; }
.template-meta { font-size: 11px; color: var(--dcdr-text-3); }

.empty-tip { grid-column: 1 / -1; text-align: center; color: var(--dcdr-text-3); font-size: 13px; padding: 48px 12px; line-height: 1.9; }

/* ===== 详情弹窗 ===== */
.detail-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 0 14px; border-bottom: 1px solid var(--dcdr-border); margin-bottom: 14px; flex-wrap: wrap; }
.detail-title { display: flex; align-items: center; gap: 10px; }
.detail-title h2 { margin: 0; font-size: 16px; font-weight: 600; }
.detail-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.owner-alert { margin-bottom: 14px; }

.detail-body { padding-bottom: 4px; }
.detail-row { display: flex; padding: 10px 0; border-bottom: 1px dashed var(--dcdr-border); }
.detail-label { width: 90px; color: var(--dcdr-text-3); font-size: 13px; flex-shrink: 0; }
.detail-value { flex: 1; color: var(--dcdr-text-1); font-size: 13px; display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.tool-tag { margin: 0; }
.detail-prompt { flex-direction: column; gap: 8px; }
.prompt-box { background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 6px; padding: 12px; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; margin: 0; max-height: 320px; overflow-y: auto; font-family: inherit; }

/* ===== V3.1 F-44.1 版本历史 / 对比 ===== */
.version-hint { font-size: 12px; color: var(--dcdr-text-3); line-height: 1.7; margin-bottom: 10px; }
.version-actions { margin-top: 12px; }
.diff-box { margin-top: 14px; border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 12px; background: var(--dcdr-bg-subtle); }
.diff-head { font-size: 13px; font-weight: 600; color: var(--dcdr-text-1); margin-bottom: 10px; display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; }
.diff-sub { font-weight: 400; font-size: 12px; color: var(--dcdr-text-3); }
.diff-list { display: flex; flex-direction: column; gap: 10px; max-height: 320px; overflow-y: auto; }
.diff-row { display: flex; gap: 10px; }
.diff-label { width: 74px; flex-shrink: 0; font-size: 12px; color: var(--dcdr-text-2); text-align: right; padding-top: 6px; }
.diff-boxes { flex: 1; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; min-width: 0; }
.diff-cell { margin: 0; font-size: 12px; line-height: 1.6; padding: 8px; border-radius: 6px; white-space: pre-wrap; word-break: break-word; max-height: 120px; overflow-y: auto; font-family: inherit; }
.diff-cell.old { background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); color: var(--dcdr-text-3); }
.diff-cell.new { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); color: var(--dcdr-text-1); }
</style>
