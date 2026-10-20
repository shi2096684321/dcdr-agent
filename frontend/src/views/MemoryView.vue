<template>
  <div class="memory-mode">
    <div class="memory-header">
      <h2>长期记忆</h2>
      <p class="subtitle">
        跨会话生效的记忆。用户级记忆对所有专家通用；专家级记忆仅在该专家下生效，冲突时以专家级为准。
        记忆只在相关内容出现时作为背景注入，不会主动复述。
      </p>
      <!-- 与知识库的分工: 两者都会进上下文, 用户会问"这条该放哪" —— 在这里给出判据, 而不是靠猜 -->
      <p class="subtitle scope-tip">
        这里放<b>关于你的稳定结论</b>：偏好、习惯、纠正、长期在做的事（一条一结论，越短越好）。
        <b>成篇资料请放进「知识库」</b> —— 那里保留原文、按需检索引用，不需要你先总结成一句话。
      </p>
    </div>

    <div class="toolbar">
      <el-button type="primary" size="small" @click="openCreate">新增记忆</el-button>
      <el-select v-model="filterScope" size="small" style="width:140px" placeholder="作用域">
        <el-option label="全部作用域" value="" />
        <el-option label="用户级" value="USER" />
        <el-option label="专家级" value="AGENT" />
      </el-select>
      <el-select v-model="filterType" size="small" style="width:140px" placeholder="类型">
        <el-option label="全部类型" value="" />
        <el-option v-for="t in TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-checkbox v-model="includeInactive" size="small" @change="load">显示已删除/归档</el-checkbox>
      <!-- 召回观测: 记忆此前完全没有"这条到底有没有被用过"的视图, 于是没人能发现"它从没生效过" -->
      <el-select v-model="hitFilter" size="small" style="width:150px" placeholder="命中情况">
        <el-option label="全部命中情况" value="" />
        <el-option label="从未命中" value="never" />
        <el-option label="命中过" value="hit" />
      </el-select>
      <el-button size="small" @click="load" :loading="loading">刷新</el-button>
      <el-button size="small" type="danger" plain :disabled="!memories.length" @click="clearAll">清空全部</el-button>

    </div>

    <!-- 策略说明由后端按实际参数生成(常驻类型 / 条数上限 / 重要度阈值 / 预算裁剪),
         所以它会比一句话长 —— 但它解释了"为什么这条没被注入""为什么这条一直占着位置" -->


    <div class="memory-body">
    <div class="memory-list" v-loading="loading">
      <div v-for="m in filteredMemories" :key="m.id" class="memory-card" :class="{ inactive: m.status !== 'ACTIVE' }">
        <div class="mc-head">
          <el-tag size="small" :type="typeTag(m.type)">{{ m.typeLabel || m.type }}</el-tag>
          <el-tag size="small" :type="m.scope === 'AGENT' ? 'warning' : 'success'" effect="plain">
            <!-- scopeLabel 由后端下发(MemoryScope 的展示名是"Agent 级记忆"), 而后端不改 ——
                 这里是唯一一处需要按用户可见口径转一道的地方, 否则它会和筛选器里的"专家级"对不上 -->
            {{ m.scope === 'AGENT' ? '专家级记忆' : (m.scopeLabel || m.scope) }}
          </el-tag>
          <span v-if="m.agentName || m.agentId" class="mc-agent">@{{ m.agentName || m.agentId }}</span>
          <el-tag v-if="m.pinned" size="small" type="danger" effect="dark">常驻</el-tag>
          <el-tag v-if="m.status !== 'ACTIVE'" size="small" type="info">{{ m.statusLabel || m.status }}</el-tag>
          <!-- 写入来源: "我要求记的" 与 "AI 自己提炼的" 必须能分辨 —— 否则用户既看不出
               自己那句"记住 X"到底记下没有, 也会对着一条陌生记忆发懵。
               MANUAL 不打标: 那是默认情形, 每条都标等于没标 -->
          <el-tag v-if="m.sourceKind && m.sourceKind !== MEMORY_ORIGIN.MANUAL" size="small"
                  :type="m.sourceKind === MEMORY_ORIGIN.REQUESTED ? 'success' : 'primary'" effect="plain"
                  :title="originTip(m)">
            {{ m.sourceKindLabel }}
          </el-tag>
          <span class="mc-importance">重要度 {{ Number(m.importance).toFixed(2) }}</span>
          <span class="mc-time">{{ formatTime(m.updatedAt) }}</span>
        </div>
        <div class="mc-content">{{ m.content }}</div>
        <div class="mc-meta">
          <span v-if="m.topic">触发键: <b>{{ m.topic }}</b></span>
          <span v-if="m.tags && m.tags.length">标签: {{ m.tags.join(' / ') }}</span>
          <span v-if="m.hitCount">
            已命中 {{ m.hitCount }} 次<span v-if="m.lastHitAt">（最近 {{ formatTime(m.lastHitAt) }}）</span>
          </span>
          <!-- 之前 lastHitAt 有值却从不展示: 它是判断"这条还有没有用"的直接依据 -->
          <span v-else class="mc-never-hit" title="创建后从未被召回进上下文 —— 可能是触发键不对，也可能确实用不上">
            从未命中
          </span>
          <span v-if="m.expiresAt">
            到期: {{ formatTime(m.expiresAt) }}
            <!-- 过期后会被定时任务归档(不再参与召回) —— 标出来, 否则用户只会觉得"这条怎么不生效了" -->
            <el-tag v-if="isExpired(m)" size="small" type="warning" effect="plain">已过期</el-tag>
          </span>
        </div>
        <div class="mc-ops">
          <el-button size="small" text type="primary" @click="openEdit(m)">编辑</el-button>
          <el-button size="small" text @click="togglePin(m)">{{ m.pinned ? '取消常驻' : '设为常驻' }}</el-button>
          <!-- 归档 =「还有效, 但现在先别用它」, 与删除的区别在用户意图上; 两者都能用右侧的「恢复」撤回 -->
          <el-button v-if="m.status === 'ACTIVE'" size="small" text @click="archive(m)">归档</el-button>
          <!-- 恢复同时覆盖归档与已删除: 软删除若不给出撤销入口, "软"就只剩"占着库不删"的意思 -->
          <el-button v-else size="small" text type="success" @click="restore(m)">恢复</el-button>
          <el-button v-if="m.status === 'ACTIVE'" size="small" text type="danger" @click="remove(m)">删除</el-button>
        </div>
      </div>
      <div v-if="!loading && !filteredMemories.length" class="empty-tip">
        暂无长期记忆<br />
        点击「新增记忆」手动添加，例如「我是 Java 后端工程师，主要使用 Spring Boot」
      </div>
    </div>

      <!-- 右栏：把「当前召回策略」和「类型分布」从工具栏里挪出来。
           它们解释的是"为什么这条没被注入""为什么这条一直占位置"，属于需要随时对照的参考信息，
           塞在筛选器旁边既挤又容易被忽略。 -->
      <aside class="memory-side">
        <div class="ms-card">
          <div class="ms-card-title">当前召回策略</div>
          <div class="ms-text" v-if="summary.retrieval"><b>当前召回策略：</b>{{ summary.retrieval }}
          </div>
        </div>
        <div class="ms-card">
          <div class="ms-card-title">记忆分布</div>
          <div class="ms-stats">

            <span class="stat-item">活跃 {{ summary.active ?? 0 }} 条</span>
            <span class="stat-item">用户级 {{ summary.userLevel ?? 0 }}</span>
            <span class="stat-item">专家级 {{ summary.agentLevel ?? 0 }}</span>
            <!-- 从未命中 = 活跃且命中数为 0: 每轮都要过一遍召回与打分, 却从未进过上下文 -->
            <span class="stat-item" :class="{ 'stat-warn': neverHitCount > 0 }">
            从未命中 {{ neverHitCount }} 条
            </span>
            <!-- 自动记忆开关 —— 由用户自己决定"要不要让 AI 替我记"。
               它和部署方的 agent.memory.auto-extract.enabled 是两回事：后者是能力闸门（决定费用），
               这里是用户同意；两者都开才生效，所以部署关着时开关禁用并写明原因，
               而不是让用户对着一个点不动的开关猜 -->
            <span class="stat-item auto-switch">
            <span class="auto-label" :class="{ 'auto-on': summary.autoExtractEnabled }"
                  :title="autoExtractTip">自动记忆</span>
            <el-switch
              size="small"
              :model-value="!!summary.autoExtractEnabled"
              :disabled="!summary.autoExtractAllowed"
              :loading="autoExtractSaving"
              @change="onToggleAutoExtract"
            />
            <span v-if="!summary.autoExtractAllowed" class="auto-note">部署未开启</span>
            <template v-else>
              <!-- 未表态要写出来：否则用户会以为"现在的状态"是自己选的 -->
              <span v-if="summary.autoExtractChoice === AUTO_EXTRACT_CHOICE.DEFAULT" class="auto-note"
                    title="当前跟随部署配置；拨动开关即成为你自己的选择">{{ summary.autoExtractChoiceLabel }}</span>
              <el-button v-else size="small" text type="primary" @click="onResetAutoExtract">恢复默认</el-button>
            </template>
            </span>

          </div>
        </div>
      </aside>

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
            <el-radio value="USER">用户级（所有专家通用）</el-radio>
            <el-radio value="AGENT">专家级（仅指定专家）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scope === 'AGENT'" label="所属专家" required>
          <el-select v-model="form.agentId" placeholder="选择专家" style="width:100%">
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
  archiveMemory, restoreMemory, setAutoExtract, getAgents, extractMessage
} from '@/api'
// 取值镜像：仅用于判断；展示用的中文名一律由后端下发（见文件头注释）
import { AUTO_EXTRACT_CHOICE, MEMORY_ORIGIN } from '@/constants/enums'

// 类型选项: note 里的「常驻 / 按需触发」对应后端 MemoryType.resident(), 让用户理解注入行为
const TYPE_OPTIONS = [
  { value: 'PROFILE', label: '用户画像', note: '角色/技术栈 · 常驻' },
  { value: 'PREFERENCE', label: '偏好', note: '回答风格/格式 · 常驻' },
  { value: 'FEEDBACK', label: '纠正', note: '对专家行为的纠正 · 常驻' },
  { value: 'PROJECT', label: '项目', note: '进行中的工作/决策 · 按需触发' },
  { value: 'REFERENCE', label: '资源指引', note: '外部系统/资源定位 · 按需触发' }
]

const loading = ref(false)
const memories = ref([])
const summary = ref({})
const agents = ref([])
const filterScope = ref('')
const filterType = ref('')
const hitFilter = ref('')
const includeInactive = ref(false)
// 自动记忆开关提交中（防连点；失败时回读，避免开关停在被拒绝的位置）
const autoExtractSaving = ref(false)

/** 活跃但从未命中: 占着常驻额度(每轮都要过一遍召回与打分)却从未进过上下文 —— 就是纯成本 */
const neverHitCount = computed(() =>
  memories.value.filter((m) => m.status === 'ACTIVE' && !m.hitCount).length)

const filteredMemories = computed(() => memories.value.filter((m) => {
  if (filterScope.value && m.scope !== filterScope.value) return false
  if (filterType.value && m.type !== filterType.value) return false
  if (hitFilter.value === 'never' && m.hitCount) return false
  if (hitFilter.value === 'hit' && !m.hitCount) return false
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

// ===== 写入来源 =====

/** 来源标签的说明: 讲清"这条怎么来的"，以及它能不能改 */
function originTip(m) {
  const base = m.sourceSessionId ? `（来源会话 ${m.sourceSessionId}）` : ''
  return m.sourceKind === MEMORY_ORIGIN.REQUESTED
    ? `你在对话中明确要求记住的${base}；和手动新增的一样，可编辑、可归档、可删除`
    : `AI 从对话里自动提炼的${base}；如不准确可直接编辑或归档，关闭「自动记忆」后不再新增`
}

// ===== 自动记忆用户级开关 =====

const autoExtractTip = computed(() => {
  if (!summary.value.autoExtractAllowed) {
    return '部署方未开启此能力（agent.memory.auto-extract.enabled=false），所以无法自动记忆'
  }
  return summary.value.autoExtractEnabled
    ? 'AI 会每隔若干轮从对话里提炼结论并写入记忆。自动写入的条目会标为「自动记录」，可随时编辑或归档'
    : '记忆目前只由你手动创建。开启后，AI 会把对话里关于你的结论性信息自动记下来'
})

async function onToggleAutoExtract(val) {
  await saveAutoExtract(val)
}

// 恢复默认 = 不再表态、重新跟随部署配置（后端把 null 解释为"没有选择过"）
async function onResetAutoExtract() {
  await saveAutoExtract(null)
}

async function saveAutoExtract(enabled) {
  autoExtractSaving.value = true
  try {
    const sum = await setAutoExtract(enabled)
    if (sum) summary.value = sum
    ElMessage.success(
      enabled === null
        ? '已恢复为跟随部署配置'
        : (enabled ? '已开启自动记忆：AI 会把对话中的结论记下来' : '已关闭自动记忆：不再自动写入（已有记忆保留）')
    )
  } catch (e) {
    ElMessage.error(extractMessage(e) || '设置失败')
    // 请求失败时开关会停在用户拨到的位置，与实际状态不一致 —— 回读一次纠正
    try { summary.value = (await getMemorySummary()) || {} } catch { /* 回读失败就保持原样 */ }
  } finally {
    autoExtractSaving.value = false
  }
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
  if (form.scope === 'AGENT' && !form.agentId) return ElMessage.warning('专家级记忆必须选择所属专家')

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

/** 到期时间已过 —— 过期后会被定时任务归档(不再参与召回), 列表里据此标出来 */
function isExpired(m) {
  if (!m.expiresAt) return false
  const t = new Date(String(m.expiresAt).replace(' ', 'T')).getTime()
  return !Number.isNaN(t) && t <= Date.now()
}

function archive(m) {
  ElMessageBox.confirm(
    '归档后该记忆不再参与后续对话，但会保留、随时可以恢复。确定归档？', '提示', { type: 'info' })
    .then(async () => {
      try {
        await archiveMemory(m.id)
        ElMessage.success('已归档')
        load()
      } catch (e) {
        ElMessage.error(extractMessage(e) || '归档失败')
      }
    })
    .catch(() => {})
}

function restore(m) {
  restoreMemory(m.id)
    .then(() => { ElMessage.success('已恢复为活跃'); load() })
    .catch((e) => ElMessage.error(extractMessage(e) || '恢复失败'))
}

function remove(m) {
  // 明确写出"可恢复": 软删除若不说, 用户误删之后不会知道还能找回来
  ElMessageBox.confirm(
    '删除后该记忆不再参与后续对话，确定删除？可在「显示已删除/归档」里恢复。', '提示', { type: 'warning' })
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
.memory-mode { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 18px 24px 20px; }
.memory-header { margin-bottom: 16px; }
.memory-header h2 { margin: 0 0 4px; font-size: 20px; color: var(--dcdr-text-1); }
.subtitle { margin: 0; font-size: 13px; color: var(--dcdr-text-3); line-height: 1.7; }
/* 与知识库的选用指引: 视觉权重低于正文说明, 但常驻可见 —— "这条该放哪"的疑问随时会产生 */
.scope-tip { margin: 8px 0 0; padding: 8px 12px; font-size: 12.5px; color: var(--dcdr-accent);
  background: var(--dcdr-bg-subtle); border-radius: 8px; line-height: 1.7; }

.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.stat-block { margin-left: auto; display: flex; gap: 14px; font-size: 12px; color: var(--dcdr-text-3); }
.stat-item { white-space: nowrap; }
/* 有"从未命中"的记忆时给个视觉提示 —— 它们是纯成本, 值得被注意到 */
.stat-warn { color: var(--dcdr-warning); font-weight: 500; }
/* 自动记忆开关: 标签 + 开关 + 状态说明排一行 */
.auto-switch { display: inline-flex; align-items: center; gap: 6px; }
.auto-label { cursor: help; }
/* 开启时用主色: 提示"这里的内容可能不再是全部手写的" */
.auto-on { color: var(--dcdr-accent); font-weight: 500; }
.auto-note { font-size: 12px; color: var(--dcdr-text-3); cursor: help; }

.retrieval-tip {
  font-size: 12px; color: var(--dcdr-accent); background: var(--dcdr-bg-subtle);
  border-radius: 6px; padding: 6px 10px; margin-bottom: 12px;
  line-height: 1.6;   /* 说明按实际参数生成, 会占两三行 */
}

.memory-body { flex: 1; display: flex; gap: 16px; min-height: 0; }
.memory-list { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px; min-height: 0; overflow-y: auto; padding-right: 4px; }

/* 右栏：召回策略 + 记忆分布 */
.memory-side { width: 300px; flex: none; display: flex; flex-direction: column; gap: 12px; overflow-y: auto; }
.ms-card {
  display: flex; flex-direction: column; gap: 9px; padding: 13px;
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-inner);
}
.ms-card-title { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 1.2px; color: var(--dcdr-text-3); }
.ms-text { font-size: 11px; line-height: 1.7; color: var(--dcdr-text-2); }
.ms-stats { display: flex; flex-wrap: wrap; gap: 8px 14px; font-size: 11px; color: var(--dcdr-text-2); }
.ms-stats .stat-item { display: inline-flex; align-items: center; gap: 6px; font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-3); }
.ms-stats .stat-warn { color: var(--dcdr-warning); }
.ms-stats .auto-switch { display: inline-flex; align-items: center; gap: 6px; }
.ms-stats .auto-label { font-size: 10px; color: var(--dcdr-text-2); }
.ms-stats .auto-label.auto-on { color: var(--dcdr-success); }
.ms-stats .auto-note { font-size: 9.5px; color: var(--dcdr-text-3); }
.memory-card { background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner); padding: 13px 14px; transition: border-color .18s, background .18s; }
.memory-card:hover { border-color: var(--dcdr-accent-line); }

.memory-card.inactive { opacity: 0.6; border-left: 3px solid #c0c4cc; }
.mc-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.mc-agent { font-size: 12px; color: var(--dcdr-warning); }
.mc-never-hit { font-size: 11px; color: var(--dcdr-text-3); }
.mc-importance { font-size: 11px; color: var(--dcdr-text-3); }
.mc-time { font-size: 11px; color: var(--dcdr-text-3); margin-left: auto; }
.mc-content { font-size: 12.5px; color: var(--dcdr-text-1); line-height: 1.65; word-break: break-word; white-space: pre-wrap; }
.mc-meta { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 7px; font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.2px; color: var(--dcdr-text-3); }
.mc-meta b { color: var(--dcdr-text-2); font-weight: 500; }
.mc-ops { display: flex; gap: 4px; margin-top: 8px; }

.empty-tip { text-align: center; color: var(--dcdr-text-3); padding: 60px 20px; font-size: 13px; line-height: 1.8; }
.opt-note { float: right; color: var(--dcdr-text-3); font-size: 12px; margin-left: 16px; }
.form-note { margin-left: 12px; font-size: 12px; color: var(--dcdr-text-3); }
</style>
