<template>
  <div class="sched-mode">
    <!-- 左侧: 任务列表 -->
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed}">
      <div class="sidebar-header">
        <button class="new-btn gradient-bg" @click="openCreateDialog">+ 新建定时任务</button>
        <button class="collapse-btn" @click="sidebarCollapsed = true" title="收起">«</button>
      </div>
      <div class="task-list">
        <div v-for="t in tasks" :key="t.id"
             class="task-item" :class="{active: t.id === currentTaskId}"
             @click="selectTask(t.id)">
          <div class="task-icon">⏰</div>
          <div class="task-info">
            <div class="task-name">
              {{ t.name }}
              <el-tag v-if="!t.enabled" size="small" type="info" class="tag">停用</el-tag>
            </div>
            <div class="task-meta">
              <span class="cron">{{ t.cron }}</span>
              <span>·</span>
              <span>{{ t.agentName || t.agentId }}</span>
            </div>
            <div class="task-status">
              <el-tag v-if="t.lastStatus" size="small" :type="statusTagType(t.lastStatus)" class="tag">
                {{ statusLabel(t.lastStatus) }}
              </el-tag>
              <span v-if="t.nextRunAt" class="next-run">下次: {{ formatTime(t.nextRunAt) }}</span>
            </div>
          </div>
        </div>
        <div v-if="!tasks.length && !loading" class="empty-tip">
          暂无定时任务<br/>点击上方按钮创建
        </div>
      </div>
    </aside>
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false">»</button>

    <!-- 右侧: 任务详情 -->
    <div class="task-main">
      <template v-if="currentTask">
        <div class="task-toolbar">
          <div class="task-toolbar-left">
            <h2>{{ currentTask.name }}</h2>
            <el-tag v-if="currentTask.enabled" size="small" type="success">启用中</el-tag>
            <el-tag v-else size="small" type="info">已停用</el-tag>
          </div>
          <div class="task-toolbar-right">
            <el-button type="primary" size="small" :loading="triggering" @click="triggerTask">立即触发</el-button>
            <el-button size="small" @click="openEditDialog">编辑</el-button>
            <el-button type="danger" size="small" @click="removeTask">删除</el-button>
          </div>
        </div>

        <div class="task-detail">
          <div class="detail-row">
            <div class="detail-label">关联专家</div>
            <div class="detail-value">{{ currentTask.agentName || currentTask.agentId }}</div>
          </div>
          <div class="detail-row">
            <div class="detail-label">Cron 表达式</div>
            <div class="detail-value"><code>{{ currentTask.cron }}</code> <span class="cron-hint">{{ cronHint(currentTask.cron) }}</span></div>
          </div>
          <div class="detail-row">
            <div class="detail-label">触发输入</div>
            <div class="detail-value pre">{{ currentTask.inputMessage || '(默认: 定时触发)' }}</div>
          </div>
          <div class="detail-row">
            <div class="detail-label">下次运行</div>
            <div class="detail-value">{{ currentTask.nextRunAt ? formatTime(currentTask.nextRunAt) : '—' }}</div>
          </div>
          <div class="detail-row">
            <div class="detail-label">最近运行</div>
            <div class="detail-value">
              <span v-if="currentTask.lastRunAt">{{ formatTime(currentTask.lastRunAt) }}</span>
              <el-tag v-if="currentTask.lastStatus" size="small" :type="statusTagType(currentTask.lastStatus)" style="margin-left:8px">
                {{ statusLabel(currentTask.lastStatus) }}
              </el-tag>
            </div>
          </div>
          <div class="detail-row" v-if="currentTask.lastError">
            <div class="detail-label">最近错误</div>
            <div class="detail-value error">{{ currentTask.lastError }}</div>
          </div>
        </div>

        <!-- 触发结果 -->
        <div v-if="triggerResult" class="trigger-result">
          <div class="tr-head">
            <span>触发结果</span>
            <el-tag size="small" :type="statusTagType(triggerResult.status)">{{ statusLabel(triggerResult.status) }}</el-tag>
          </div>
          <pre class="tr-output">{{ triggerResult.output || triggerResult.error || '(无输出)' }}</pre>
        </div>
      </template>
      <div v-else class="empty-detail">
        <div class="empty-icon">⏰</div>
        <p>选择左侧任务查看详情, 或新建一个定时任务</p>
      </div>
    </div>

    <!-- 创建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑定时任务' : '新建定时任务'" width="600px" @closed="resetForm">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如: 每日晨报" maxlength="100" />
        </el-form-item>
        <el-form-item label="关联专家" required>
          <el-select v-model="form.agentId" placeholder="选择触发执行的专家" filterable style="width:100%">
            <el-option v-for="a in agents" :key="a.id" :label="a.name + (a.isTemplate ? ' (模板)' : '')" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Cron 表达式" required>
          <el-input v-model="form.cron" placeholder="Spring 6 字段: 秒 分 时 日 月 周 (如: 0 0 9 * * * = 每天9点)" />
          <div class="cron-presets">
            <el-button v-for="p in cronPresets" :key="p.value" link type="primary" @click="form.cron = p.value">
              {{ p.label }}
            </el-button>
          </div>
          <div class="cron-hint" v-if="form.cron">{{ cronHint(form.cron) }}</div>
        </el-form-item>
        <el-form-item label="触发输入">
          <el-input v-model="form.inputMessage" type="textarea" :rows="3"
                    placeholder="触发时喂给专家的输入消息 (留空则用 '(定时触发)')" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
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
import {
  getScheduledTasks, createScheduledTask, updateScheduledTask, deleteScheduledTask, triggerScheduledTask,
  getAgents, extractMessage
} from '@/api'

const loading = ref(false)
const tasks = ref([])
const currentTaskId = ref('')
const currentTask = computed(() => tasks.value.find(t => t.id === currentTaskId.value) || null)
const sidebarCollapsed = ref(false)

const agents = ref([])

const triggering = ref(false)
const triggerResult = ref(null)

const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const form = reactive({ id: '', name: '', agentId: '', cron: '', inputMessage: '', enabled: true })

// 常用 cron 预设
const cronPresets = [
  { label: '每分钟', value: '0 * * * * *' },
  { label: '每小时', value: '0 0 * * * *' },
  { label: '每天 9:00', value: '0 0 9 * * *' },
  { label: '每天 0:00', value: '0 0 0 * * *' },
  { label: '工作日 9:00', value: '0 0 9 * * MON-FRI' },
  { label: '每周一 9:00', value: '0 0 9 * * MON' }
]

function statusTagType(s) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  if (s === 'running') return 'warning'
  return 'info'
}
function statusLabel(s) {
  return ({ success: '成功', failed: '失败', running: '运行中' })[s] || s
}
function formatTime(t) {
  if (!t) return '—'
  // 后端返回 LocalDateTime, 序列化为数组或 ISO 字符串, 兜底处理
  const d = new Date(typeof t === 'string' ? t.replace(' ', 'T') : t)
  if (isNaN(d.getTime())) return String(t)
  return d.toLocaleString('zh-CN', { hour12: false })
}
function cronHint(cron) {
  // 简单 cron 提示 (不完整, 仅辅助)
  const p = (cron || '').trim().split(/\s+/)
  if (p.length !== 6) return '需 6 字段: 秒 分 时 日 月 周'
  const [s, m, h, d, mo, w] = p
  if (s === '0' && m === '0' && h === '9' && d === '*' && mo === '*' && w === '*') return '每天 9:00'
  if (s === '0' && m === '0' && h === '0' && d === '*' && mo === '*' && w === '*') return '每天 0:00'
  if (s === '0' && m === '0' && h === '*' && d === '*' && mo === '*' && w === '*') return '每小时整点'
  if (s === '0' && m === '*' && h === '*' && d === '*' && mo === '*' && w === '*') return '每分钟'
  return ''
}

async function loadTasks() {
  loading.value = true
  try {
    tasks.value = await getScheduledTasks()
    if (!currentTaskId.value && tasks.value.length) {
      currentTaskId.value = tasks.value[0].id
    }
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载定时任务列表失败')
  } finally {
    loading.value = false
  }
}

async function loadAgents() {
  try {
    agents.value = await getAgents()
  } catch (e) {
    console.warn('[ScheduledTaskView] 加载 Agent 列表失败:', e)
  }
}

function selectTask(id) {
  currentTaskId.value = id
  triggerResult.value = null
}

function openCreateDialog() {
  editing.value = false
  resetForm()
  dialogVisible.value = true
}

function openEditDialog() {
  if (!currentTask.value) return
  editing.value = true
  const t = currentTask.value
  form.id = t.id
  form.name = t.name
  form.agentId = t.agentId
  form.cron = t.cron
  form.inputMessage = t.inputMessage || ''
  form.enabled = t.enabled
  dialogVisible.value = true
}

function resetForm() {
  form.id = ''
  form.name = ''
  form.agentId = ''
  form.cron = ''
  form.inputMessage = ''
  form.enabled = true
}

async function submitForm() {
  if (!form.name || !form.name.trim()) { ElMessage.warning('请填写任务名称'); return }
  if (!form.agentId) { ElMessage.warning('请选择关联专家'); return }
  if (!form.cron || !form.cron.trim()) { ElMessage.warning('请填写 Cron 表达式'); return }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      agentId: form.agentId,
      cron: form.cron.trim(),
      inputMessage: form.inputMessage || null,
      enabled: form.enabled
    }
    if (editing.value) {
      await updateScheduledTask(form.id, payload)
      ElMessage.success('任务已更新')
    } else {
      const created = await createScheduledTask(payload)
      ElMessage.success('任务已创建')
      currentTaskId.value = created.id
    }
    dialogVisible.value = false
    await loadTasks()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeTask() {
  if (!currentTask.value) return
  try {
    await ElMessageBox.confirm(`确定删除任务 "${currentTask.value.name}" 吗?`, '提示', { type: 'warning' })
    await deleteScheduledTask(currentTask.value.id)
    ElMessage.success('已删除')
    if (currentTaskId.value === currentTask.value.id) currentTaskId.value = ''
    await loadTasks()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(extractMessage(e) || '删除失败')
  }
}

async function triggerTask() {
  if (!currentTask.value) return
  triggering.value = true
  triggerResult.value = null
  try {
    triggerResult.value = await triggerScheduledTask(currentTask.value.id, null)
    ElMessage.success('触发完成')
    // 刷新列表以获取最新状态
    await loadTasks()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '触发失败')
  } finally {
    triggering.value = false
  }
}

onMounted(() => {
  loadTasks()
  loadAgents()
})
</script>

<style scoped>
.sched-mode { flex: 1; display: flex; overflow: hidden; }
.sidebar { width: 280px; background: var(--dcdr-bg-subtle); border-right: 1px solid var(--dcdr-border); display: flex; flex-direction: column; transition: width 0.2s; }
.sidebar.collapsed { width: 0; overflow: hidden; }
.sidebar-expand { position: absolute; left: 0; top: 56px; width: 20px; height: 40px; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-left: none; border-radius: 0 6px 6px 0; cursor: pointer; z-index: 10; }
.sidebar-header { padding: 12px; display: flex; gap: 8px; align-items: center; }
.new-btn { flex: 1; padding: 8px 12px; border: none; border-radius: 8px; color: #fff; font-size: 13px; cursor: pointer; }
.collapse-btn { width: 28px; height: 32px; border: 1px solid var(--dcdr-border); background: var(--dcdr-surface); border-radius: 6px; cursor: pointer; color: var(--dcdr-text-3); }
.task-list { flex: 1; overflow-y: auto; padding: 0 8px 12px; }
.task-item { display: flex; gap: 10px; padding: 10px; border-radius: 8px; cursor: pointer; margin-bottom: 4px; }
.task-item:hover { background: var(--dcdr-bg); }
.task-item.active { background: linear-gradient(135deg, rgba(102,126,234,0.12) 0%, rgba(118,75,162,0.10) 100%); }
.task-icon { width: 32px; height: 32px; border-radius: 8px; background: var(--dcdr-bg); display: flex; align-items: center; justify-content: center; font-size: 16px; flex-shrink: 0; }
.task-info { flex: 1; min-width: 0; }
.task-name { font-size: 13px; font-weight: 500; color: var(--dcdr-text-1); display: flex; align-items: center; gap: 4px; }
.task-name .tag { transform: scale(0.85); }
.task-meta { font-size: 11px; color: var(--dcdr-text-3); margin-top: 2px; display: flex; gap: 4px; }
.task-meta .cron { font-family: monospace; }
.task-status { font-size: 11px; color: var(--dcdr-text-3); margin-top: 4px; display: flex; align-items: center; gap: 6px; }
.next-run { }
.empty-tip { text-align: center; color: var(--dcdr-text-3); font-size: 13px; padding: 40px 12px; line-height: 1.8; }

.task-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: var(--dcdr-surface); padding: 12px 20px 20px; }
.task-toolbar { display: flex; align-items: center; justify-content: space-between; padding-bottom: 12px; border-bottom: 1px solid var(--dcdr-border); }
.task-toolbar-left { display: flex; align-items: center; gap: 8px; }
.task-toolbar-left h2 { margin: 0; font-size: 18px; }
.task-toolbar-right { display: flex; gap: 8px; }

.task-detail { padding: 16px 0; }
.detail-row { display: flex; padding: 10px 0; border-bottom: 1px dashed var(--dcdr-border); }
.detail-label { width: 100px; color: var(--dcdr-text-3); font-size: 13px; flex-shrink: 0; }
.detail-value { flex: 1; color: var(--dcdr-text-1); font-size: 13px; }
.detail-value.pre { white-space: pre-wrap; word-break: break-word; }
.detail-value code { background: var(--dcdr-bg-subtle); padding: 2px 6px; border-radius: 4px; font-family: monospace; }
.detail-value.error { color: var(--dcdr-danger); }
.cron-hint { color: var(--dcdr-text-3); font-size: 12px; margin-left: 8px; }

.trigger-result { margin-top: 16px; background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 12px 14px; }
.tr-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; font-weight: 500; }
.tr-output { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 4px; padding: 8px; white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 12px; max-height: 240px; overflow-y: auto; margin: 0; }

.empty-detail { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--dcdr-text-3); gap: 12px; }
.empty-icon { font-size: 56px; }

.cron-presets { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 4px; }
</style>
