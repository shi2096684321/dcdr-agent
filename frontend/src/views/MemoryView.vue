<template>
  <div class="memory-mode">
    <div class="memory-header">
      <h2>长期记忆</h2>
      <p class="subtitle">
        跨会话生效的记忆。用户级记忆对所有 Agent 通用；Agent 级记忆仅在该 Agent 下生效，冲突时以 Agent 级为准。
        记忆只在相关内容出现时作为背景注入，不会主动复述。
      </p>
    </div>

    <div class="toolbar">
      <el-button type="primary" size="small" @click="openCreate">新增记忆</el-button>
      <el-select v-model="filterScope" size="small" style="width:140px" placeholder="作用域">
        <el-option label="全部作用域" value="" />
        <el-option label="用户级" value="USER" />
        <el-option label="Agent 级" value="AGENT" />
      </el-select>
      <el-select v-model="filterType" size="small" style="width:140px" placeholder="类型">
        <el-option label="全部类型" value="" />
        <el-option v-for="t in TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-checkbox v-model="includeInactive" size="small" @change="load">显示已删除/归档</el-checkbox>
      <el-button size="small" @click="load" :loading="loading">刷新</el-button>
      <el-button size="small" type="danger" plain :disabled="!memories.length" @click="clearAll">清空全部</el-button>
      <div class="stat-block">
        <span class="stat-item">活跃 {{ summary.active ?? 0 }} 条</span>
        <span class="stat-item">用户级 {{ summary.userLevel ?? 0 }}</span>
        <span class="stat-item">Agent 级 {{ summary.agentLevel ?? 0 }}</span>
      </div>
    </div>

    <div class="retrieval-tip" v-if="summary.retrieval">
      当前召回策略：{{ summary.retrieval }}
    </div>

    <div class="memory-list" v-loading="loading">
      <div v-for="m in filteredMemories" :key="m.id" class="memory-card" :class="{ inactive: m.status !== 'ACTIVE' }">
        <div class="mc-head">
          <el-tag size="small" :type="typeTag(m.type)">{{ m.typeLabel || m.type }}</el-tag>
          <el-tag size="small" :type="m.scope === 'AGENT' ? 'warning' : 'success'" effect="plain">
            {{ m.scopeLabel || m.scope }}
          </el-tag>
          <span v-if="m.agentName || m.agentId" class="mc-agent">@{{ m.agentName || m.agentId }}</span>
          <el-tag v-if="m.pinned" size="small" type="danger" effect="dark">常驻</el-tag>
          <el-tag v-if="m.status !== 'ACTIVE'" size="small" type="info">{{ m.statusLabel || m.status }}</el-tag>
          <span class="mc-importance">重要度 {{ Number(m.importance).toFixed(2) }}</span>
          <span class="mc-time">{{ formatTime(m.updatedAt) }}</span>
        </div>
        <div class="mc-content">{{ m.content }}</div>
        <div class="mc-meta">
          <span v-if="m.topic">触发键: <b>{{ m.topic }}</b></span>
          <span v-if="m.tags && m.tags.length">标签: {{ m.tags.join(' / ') }}</span>
          <span v-if="m.hitCount">已命中 {{ m.hitCount }} 次</span>
          <span v-if="m.expiresAt">到期: {{ formatTime(m.expiresAt) }}</span>
        </div>
        <div class="mc-ops">
          <el-button size="small" text type="primary" @click="openEdit(m)">编辑</el-button>
          <el-button size="small" text @click="togglePin(m)">{{ m.pinned ? '取消常驻' : '设为常驻' }}</el-button>
          <el-button v-if="m.status === 'ACTIVE'" size="small" text type="danger" @click="remove(m)">删除</el-button>
        </div>
      </div>
      <div v-if="!loading && !filteredMemories.length" class="empty-tip">
        暂无长期记忆<br />
        点击「新增记忆」手动添加，例如「我是 Java 后端工程师，主要使用 Spring Boot」
      </div>
    </div>

    <!-- 新增 / 编辑记忆 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑记忆' : '新增记忆'" width="580px">
      <el-form label-width="92px">
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="4" maxlength="1000" show-word-limit
                    placeholder="填写提炼后的结论性信息，例如：回答代码时优先给出可运行的最小示例" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width:100%">
            <el-option v-for="t in TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value">
              <span>{{ t.label }}</span>
              <span class="opt-note">{{ t.note }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="作用域">
          <el-radio-group v-model="form.scope">
            <el-radio value="USER">用户级（所有 Agent 通用）</el-radio>
            <el-radio value="AGENT">Agent 级（仅指定 Agent）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scope === 'AGENT'" label="所属 Agent" required>
          <el-select v-model="form.agentId" placeholder="选择 Agent" style="width:100%">
            <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="触发键">
          <el-input v-model="form.topic" maxlength="64" placeholder="可选：命中该关键词时才注入，如「部署环境」" />
        </el-form-item>
        <el-form-item label="标签">
          <el-select v-model="form.tags" multiple filterable allow-create default-first-option
                     placeholder="输入后回车添加，如 spring-boot" style="width:100%">
            <el-option v-for="t in form.tags" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="重要度">
          <el-slider v-model="form.importance" :min="0" :max="1" :step="0.05" show-input />
        </el-form-item>
        <el-form-item label="常驻注入">
          <el-switch v-model="form.pinned" />
          <span class="form-note">开启后每轮对话都会带上该记忆</span>
        </el-form-item>
        <el-form-item label="到期时间">
          <el-date-picker v-model="form.expiresAt" type="datetime" placeholder="留空表示永久有效" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMemories, getMemorySummary, createMemory, updateMemory, deleteMemory, clearMemories,
  getAgents, extractMessage
} from '@/api'

// 类型选项: note 里的「常驻 / 按需触发」对应后端 MemoryType.resident(), 让用户理解注入行为
const TYPE_OPTIONS = [
  { value: 'PROFILE', label: '用户画像', note: '角色/技术栈 · 常驻' },
  { value: 'PREFERENCE', label: '偏好', note: '回答风格/格式 · 常驻' },
  { value: 'FEEDBACK', label: '纠正', note: '对 Agent 行为的纠正 · 常驻' },
  { value: 'PROJECT', label: '项目', note: '进行中的工作/决策 · 按需触发' },
  { value: 'REFERENCE', label: '资源指引', note: '外部系统/资源定位 · 按需触发' }
]

const loading = ref(false)
const memories = ref([])
const summary = ref({})
const agents = ref([])
const filterScope = ref('')
const filterType = ref('')
const includeInactive = ref(false)

const filteredMemories = computed(() => memories.value.filter((m) => {
  if (filterScope.value && m.scope !== filterScope.value) return false
  if (filterType.value && m.type !== filterType.value) return false
  return true
}))

function typeTag(t) {
  return ({ PROFILE: 'primary', PREFERENCE: 'success', FEEDBACK: 'danger', PROJECT: 'warning', REFERENCE: 'info' })[t] || 'info'
}

function formatTime(t) {
  if (!t) return ''
  try { return new Date(t).toLocaleString('zh-CN') } catch { return t }
}

/** 日期 → 后端 LocalDateTime 字面量 (yyyy-MM-ddTHH:mm:ss)。
 *  不能用 toISOString()：它带 Z 与时区偏移，LocalDateTime 无法反序列化。 */
function toLocalDateTime(date) {
  if (!date) return null
  const d = date instanceof Date ? date : new Date(date)
  if (Number.isNaN(d.getTime())) return null
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function load() {
  loading.value = true
  try {
    const [list, sum] = await Promise.all([getMemories(includeInactive.value), getMemorySummary()])
    memories.value = Array.isArray(list) ? list : []
    summary.value = sum || {}
    // Agent 列表仅用于 Agent 级记忆的选择项，加载失败不阻断页面
    if (!agents.value.length) {
      try { agents.value = (await getAgents()) || [] } catch { agents.value = [] }
    }
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载记忆失败')
  } finally {
    loading.value = false
  }
}

// ===== 新增 / 编辑 =====
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({
  id: '', content: '', type: 'PREFERENCE', scope: 'USER', agentId: '',
  topic: '', tags: [], importance: 0.5, pinned: false, expiresAt: null
})

function resetForm() {
  form.id = ''
  form.content = ''
  form.type = 'PREFERENCE'
  form.scope = 'USER'
  form.agentId = ''
  form.topic = ''
  form.tags = []
  form.importance = 0.5
  form.pinned = false
  form.expiresAt = null
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(m) {
  resetForm()
  form.id = m.id
  form.content = m.content || ''
  form.type = m.type || 'PREFERENCE'
  form.scope = m.scope || 'USER'
  form.agentId = m.agentId || ''
  form.topic = m.topic || ''
  form.tags = Array.isArray(m.tags) ? [...m.tags] : []
  form.importance = Number(m.importance ?? 0.5)
  form.pinned = !!m.pinned
  form.expiresAt = m.expiresAt ? new Date(m.expiresAt) : null
  dialogVisible.value = true
}

async function submit() {
  if (!form.content.trim()) return ElMessage.warning('请填写记忆内容')
  if (form.scope === 'AGENT' && !form.agentId) return ElMessage.warning('Agent 级记忆必须选择所属 Agent')

  const payload = {
    content: form.content.trim(),
    type: form.type,
    scope: form.scope,
    agentId: form.scope === 'AGENT' ? form.agentId : null,
    topic: form.topic.trim() || null,
    tags: form.tags,
    importance: form.importance,
    pinned: form.pinned,
    expiresAt: toLocalDateTime(form.expiresAt)
  }

  saving.value = true
  try {
    if (form.id) await updateMemory(form.id, payload)
    else await createMemory(payload)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '保存失败')
  } finally {
    saving.value = false
  }
}

// ===== 常用操作 =====
function togglePin(m) {
  // 更新接口为「仅提交字段生效」语义, 因此只需提交 pinned
  updateMemory(m.id, { pinned: !m.pinned })
    .then(() => { ElMessage.success(m.pinned ? '已取消常驻' : '已设为常驻'); load() })
    .catch((e) => ElMessage.error(extractMessage(e) || '操作失败'))
}

function remove(m) {
  ElMessageBox.confirm('删除后该记忆不再参与后续对话，确定删除？', '提示', { type: 'warning' })
    .then(async () => {
      try {
        await deleteMemory(m.id)
        ElMessage.success('已删除')
        load()
      } catch (e) {
        ElMessage.error(extractMessage(e) || '删除失败')
      }
    })
    .catch(() => {})
}

function clearAll() {
  ElMessageBox.confirm('将清空你的全部长期记忆，且不再参与后续对话，确定继续？', '警告', { type: 'warning' })
    .then(async () => {
      try {
        const count = await clearMemories()
        ElMessage.success(`已清空 ${count ?? 0} 条记忆`)
        load()
      } catch (e) {
        ElMessage.error(extractMessage(e) || '清空失败')
      }
    })
    .catch(() => {})
}

onMounted(() => {
  load()
})
</script>

<style scoped>
/* flex:1 + overflow-y:auto: 根容器自行滚动(父级 .app-main 为 overflow:hidden, 不加则内容超高被裁剪无法下滑) */
.memory-mode { flex: 1; overflow-y: auto; padding: 20px 24px; max-width: 960px; margin: 0 auto; }
.memory-header { margin-bottom: 16px; }
.memory-header h2 { margin: 0 0 4px; font-size: 20px; color: #1d2129; }
.subtitle { margin: 0; font-size: 13px; color: #86909c; line-height: 1.7; }

.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.stat-block { margin-left: auto; display: flex; gap: 14px; font-size: 12px; color: #86909c; }
.stat-item { white-space: nowrap; }

.retrieval-tip {
  font-size: 12px; color: #667eea; background: #f2f4ff;
  border-radius: 6px; padding: 6px 10px; margin-bottom: 12px;
}

.memory-list { display: flex; flex-direction: column; gap: 10px; min-height: 200px; }
.memory-card { background: #fff; border: 1px solid #e8e8e8; border-radius: 8px; padding: 12px 14px; transition: box-shadow .2s; }
.memory-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.memory-card.inactive { opacity: 0.6; border-left: 3px solid #c0c4cc; }
.mc-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.mc-agent { font-size: 12px; color: #e6a23c; }
.mc-importance { font-size: 11px; color: #86909c; }
.mc-time { font-size: 11px; color: #a0a4ab; margin-left: auto; }
.mc-content { font-size: 13px; color: #1d2129; line-height: 1.6; word-break: break-word; white-space: pre-wrap; }
.mc-meta { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 6px; font-size: 12px; color: #86909c; }
.mc-meta b { color: #606266; font-weight: 500; }
.mc-ops { display: flex; gap: 4px; margin-top: 8px; }

.empty-tip { text-align: center; color: #a0a4ab; padding: 60px 20px; font-size: 13px; line-height: 1.8; }
.opt-note { float: right; color: #a0a4ab; font-size: 12px; margin-left: 16px; }
.form-note { margin-left: 12px; font-size: 12px; color: #a0a4ab; }
</style>
