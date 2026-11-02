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
              <span class="cron" :title="t.cron">{{ cronText(t.cron) }}</span>
              <span>·</span>
              <span>{{ agentLabel(t) }}</span>
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
            <el-button type="primary" size="small" :loading="triggering" @click="triggerTask(false)">立即触发</el-button>
            <!-- 手动触发默认不推送(结果就在本页), 但"能不能收到推送"必须有个当场能验证的入口 -->
            <el-button v-if="currentTask.notifyEnabled" size="small" :loading="triggering"
                       @click="triggerTask(true)">执行并推送</el-button>
            <el-button size="small" @click="notifyConfigVisible = true">推送配置</el-button>
            <el-button v-if="currentTask.notifyEnabled" size="small" :loading="testingNotify" @click="testNotify">
              测试推送
            </el-button>
            <el-button v-if="currentTask.notifyEnabled" size="small" @click="openPreview">
              预览消息
            </el-button>
            <el-button size="small" @click="openEditDialog">编辑</el-button>
            <el-button type="danger" size="small" @click="removeTask">删除</el-button>
          </div>
        </div>

        <div class="task-detail">
          <div class="detail-row">
            <div class="detail-label">关联专家</div>
            <div class="detail-value">{{ agentLabel(currentTask) }}</div>
          </div>
          <div class="detail-row">
            <div class="detail-label">执行模型</div>
            <div class="detail-value">{{ modelLabel(currentTask.model) }}</div>
          </div>
          <div class="detail-row">
            <div class="detail-label">执行时间</div>
            <div class="detail-value">
              {{ cronText(currentTask.cron) }}
              <code class="cron-raw" :title="currentTask.cron">{{ currentTask.cron }}</code>
            </div>
          </div>
          <div class="detail-row">
            <div class="detail-label">提示词</div>
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
          <div class="detail-row">
            <div class="detail-label">结果推送</div>
            <div class="detail-value">
              <template v-if="currentTask.notifyEnabled">
                <el-tag size="small" type="success">已开启</el-tag>
                <span class="notify-channel-name">{{ notifyChannelLabel(currentTask.notifyChannel) }}</span>
                <el-tag v-if="currentTask.notifyLastStatus" size="small"
                        :type="notifyTagType(currentTask.notifyLastStatus)" style="margin-left:8px">
                  {{ notifyStatusLabel(currentTask.notifyLastStatus) }}
                </el-tag>
                <span v-if="currentTask.notifyLastAt" class="notify-time">{{ formatTime(currentTask.notifyLastAt) }}</span>
              </template>
              <el-tag v-else size="small" type="info">未开启</el-tag>
            </div>
          </div>
          <div class="detail-row" v-if="currentTask.notifyEnabled && currentTask.notifyLastError">
            <!-- skipped 与 failed 语义不同: 前者是"按规则没发"(节流/未开启), 前者红字会让人误以为推送坏了 -->
            <div class="detail-label">{{ currentTask.notifyLastStatus === 'failed' ? '推送失败原因' : '推送跳过原因' }}</div>
            <div class="detail-value" :class="currentTask.notifyLastStatus === 'failed' ? 'error' : 'muted'">
              {{ currentTask.notifyLastError }}
            </div>
          </div>
        </div>

        <!-- 触发结果 -->
        <div v-if="triggerResult" class="trigger-result">
          <div class="tr-head">
            <span>触发结果</span>
            <el-tag size="small" :type="statusTagType(triggerResult.status)">{{ statusLabel(triggerResult.status) }}</el-tag>
            <el-tag v-if="triggerResult.notifyStatus" size="small" :type="notifyTagType(triggerResult.notifyStatus)">
              {{ notifyStatusLabel(triggerResult.notifyStatus) }}
            </el-tag>
            <span v-if="triggerResult.durationMs" class="tr-meta">耗时 {{ formatDuration(triggerResult.durationMs) }}</span>
          </div>
          <!-- 未推送/推送失败必须写出来: 否则"执行成功但没收到消息"只能靠猜 -->
          <div v-if="triggerResult.notifyReason" class="tr-note" :class="{ warn: triggerResult.notifyStatus === 'failed' }">
            {{ triggerResult.notifyReason }}
          </div>
          <pre class="tr-output">{{ triggerResult.output || triggerResult.error || '(无输出)' }}</pre>
        </div>

        <!-- 执行历史 -->
        <div class="run-history">
          <div class="rh-head">
            <span>执行历史</span>
            <div class="rh-actions">
              <span v-if="runs.length" class="rh-count">最近 {{ runs.length }} 条</span>
              <el-button link type="primary" size="small" @click="loadRuns">刷新</el-button>
            </div>
          </div>
          <div v-if="runsLoading" class="rh-empty">加载中…</div>
          <div v-else-if="!runs.length" class="rh-empty">
            暂无执行记录（定时触发或手动触发后产生）
          </div>
          <div v-else class="rh-list">
            <div v-for="r in runs" :key="r.id" class="rh-item" @click="openRun(r)">
              <el-tag size="small" :type="statusTagType(r.status)">{{ statusLabel(r.status) }}</el-tag>
              <span class="rh-time">{{ formatTime(r.startedAt) }}</span>
              <span class="rh-dur">{{ formatDuration(r.durationMs) }}</span>
              <span class="rh-preview">{{ r.preview || r.error || '(无输出)' }}</span>
            </div>
          </div>
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
        <el-form-item label="关联专家">
          <el-select v-model="form.agentId" placeholder="可选: 留空则用默认助手执行" filterable clearable style="width:100%">
            <el-option v-for="a in agents" :key="a.id" :label="a.name + (a.isTemplate ? ' (模板)' : '')" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行模型">
          <el-select v-model="form.model" clearable filterable
                     placeholder="可选: 留空则用专家配置的模型 / 平台默认" style="width:100%">
            <el-option-group label="平台模型">
              <el-option v-for="m in platformChatModels" :key="m.name" :label="m.label" :value="m.name" />
            </el-option-group>
            <el-option-group v-if="customChatModels.length" label="我的自定义模型">
              <el-option v-for="m in customChatModels" :key="m.name" :label="m.label" :value="m.name" />
            </el-option-group>
          </el-select>
          <div class="notify-hint">指定后优先级最高：即使专家配置了别的模型，也按这里执行。</div>
        </el-form-item>
        <el-form-item label="执行时间" required>
          <div class="sched-config">
            <el-select v-model="form.scheduleType" style="width:128px">
              <el-option v-for="o in scheduleTypes" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-select v-if="form.scheduleType === 'weekly'" v-model="form.weekday" style="width:96px">
              <el-option v-for="d in weekdays" :key="d.value" :label="d.label" :value="d.value" />
            </el-select>
            <el-select v-if="form.scheduleType === 'monthly'" v-model="form.monthDay" style="width:104px">
              <el-option v-for="d in 31" :key="d" :label="d + ' 日'" :value="d" />
            </el-select>
            <el-time-select v-if="needTimeOfDay" v-model="form.timeOfDay" start="00:00" step="00:05" end="23:55"
                            placeholder="时间" style="width:118px" />
            <template v-if="form.scheduleType === 'hourly'">
              <span class="sched-word">第</span>
              <el-input-number v-model="form.minuteOfHour" :min="0" :max="59" controls-position="right" style="width:92px" />
              <span class="sched-word">分</span>
            </template>
            <template v-if="form.scheduleType === 'minutes'">
              <span class="sched-word">每</span>
              <el-input-number v-model="form.intervalMinutes" :min="1" :max="59" controls-position="right" style="width:92px" />
              <span class="sched-word">分钟</span>
            </template>
          </div>
          <el-input v-if="form.scheduleType === 'custom'" v-model="form.cron"
                    placeholder="Spring 6 字段: 秒 分 时 日 月 周 (如: 0 0 9 * * *)" style="margin-top:8px" />
          <div class="cron-presets" v-if="form.scheduleType === 'custom'">
            <el-button v-for="p in cronPresets" :key="p.value" link type="primary" @click="form.cron = p.value">
              {{ p.label }}
            </el-button>
          </div>
          <div class="cron-hint">
            {{ cronText(effectiveCron) }}<template v-if="form.scheduleType === 'custom'"> · {{ effectiveCron }}</template>
          </div>
        </el-form-item>
        <el-form-item label="提示词">
          <el-input v-model="form.inputMessage" type="textarea" :rows="3"
                    placeholder="每次触发时发给模型的提示词 (留空则用 '(定时触发)')" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="结果推送">
          <div class="notify-row">
            <el-switch v-model="form.notifyEnabled" />
            <el-select v-if="form.notifyEnabled" v-model="form.notifyChannel" size="small"
                       placeholder="跟随默认渠道" clearable style="width:220px">
              <el-option v-for="c in notifyChannels" :key="c.value"
                         :label="c.label + (c.configured ? '' : ' (未配置)')"
                         :value="c.value" />
            </el-select>
          </div>
          <div class="notify-hint">
            执行完成后把结果推到<i>你自己的</i>机器人：企业微信（经「微信插件」可在个人微信收到）或钉钉。
            失败消息必定推送；成功消息同一任务在节流窗口内最多一条，避免刷屏。
            <el-button link type="primary" size="small" @click="notifyConfigVisible = true">
              去配置推送渠道
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 消息模板预览：用示例数据渲染，不发送 -->
    <el-dialog v-model="previewVisible" title="消息预览" width="680px">
      <div v-if="previewData">
        <div class="preview-meta">
          渠道：{{ previewData.channelLabel }} · 单条上限
          {{ previewData.maxSize }} {{ previewData.unit === 'BYTES' ? '字节' : '字符' }}
        </div>
        <el-tabs v-model="previewTab">
          <el-tab-pane label="执行成功" name="success">
            <pre class="preview-pre">{{ previewData.successContent }}</pre>
          </el-tab-pane>
          <el-tab-pane label="执行失败" name="failure">
            <pre class="preview-pre">{{ previewData.failureContent }}</pre>
          </el-tab-pane>
        </el-tabs>
        <div class="preview-hint">
          这是发给机器人的原始文本（markdown 源码），实际排版以群内渲染结果为准。
        </div>
      </div>
      <div v-else class="preview-loading">加载中…</div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 执行历史详情 -->
    <el-dialog v-model="runVisible" title="执行详情" width="720px">
      <div v-if="runDetail">
        <div class="preview-meta">
          <el-tag size="small" :type="statusTagType(runDetail.status)">{{ statusLabel(runDetail.status) }}</el-tag>
          <span class="rh-time">{{ formatTime(runDetail.startedAt) }}</span>
          <span class="rh-dur">{{ formatDuration(runDetail.durationMs) }}</span>
          <a v-if="runDetail.shareUrl" :href="runDetail.shareUrl" target="_blank" rel="noopener" class="rh-link">
            分享页
          </a>
        </div>
        <pre class="preview-pre">{{ runDetail.output || runDetail.error || '(无输出)' }}</pre>
      </div>
      <div v-else class="preview-loading">加载中…</div>
      <template #footer>
        <el-button @click="runVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- V3.3 个人推送渠道配置: 与任务解耦, 配一次所有任务共用 -->
    <NotifyConfigDialog v-model="notifyConfigVisible" @saved="loadNotifyConfigs" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getScheduledTasks, createScheduledTask, updateScheduledTask, deleteScheduledTask, triggerScheduledTask,
  testScheduledTaskNotify, previewScheduledTaskNotify, getScheduledTaskRuns, getScheduledTaskRun,
  getAgents, getNotifyConfigs, extractMessage
} from '@/api'
import NotifyConfigDialog from '@/components/NotifyConfigDialog.vue'
import { useModelStore } from '@/stores/model'

const loading = ref(false)
const tasks = ref([])
const currentTaskId = ref('')
const currentTask = computed(() => tasks.value.find(t => t.id === currentTaskId.value) || null)
const sidebarCollapsed = ref(false)

const agents = ref([])

const triggering = ref(false)
const triggerResult = ref(null)
const testingNotify = ref(false)
const previewVisible = ref(false)
const previewData = ref(null)
const previewTab = ref('success')

// 执行历史
const runs = ref([])
const runsLoading = ref(false)
const runVisible = ref(false)
const runDetail = ref(null)

const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)

const DEFAULT_TIME = '09:00'
// scheduleType 是"给人看"的选项, 真正存库的仍是 cron(兼容存量任务与内部调度)
const form = reactive({
  id: '', name: '', agentId: '', model: '',
  scheduleType: 'daily', timeOfDay: DEFAULT_TIME, weekday: 'MON', monthDay: 1,
  minuteOfHour: 0, intervalMinutes: 30,
  cron: '', inputMessage: '', enabled: true, notifyEnabled: false, notifyChannel: ''
})

const modelStore = useModelStore()

// ===== V3.3 用户级推送配置 =====
const notifyConfigVisible = ref(false)
const notifyConfigs = ref([])

/**
 * 渠道清单唯一来源是后端 /api/notify-config。
 * 前端再维护一份常量, 新增渠道时必然漏改一处, 表现为"能配不能选"或"选了发不出去"。
 */
const notifyChannels = computed(() =>
  notifyConfigs.value.map((c) => ({
    value: c.channel,
    label: c.channelLabel,
    configured: c.configured && c.enabled
  })))

async function loadNotifyConfigs() {
  try {
    notifyConfigs.value = await getNotifyConfigs()
  } catch (e) {
    // 静默失败会让下拉整体变成"(未配置)", 看起来像"配置丢了", 必须提示
    console.warn('[ScheduledTaskView] 加载推送配置失败:', e)
    ElMessage.warning('推送配置加载失败，渠道列表可能不完整')
  }
}

// ===== 执行时间: 可读选项 → cron =====
const scheduleTypes = [
  { value: 'daily', label: '每天' },
  { value: 'workdays', label: '工作日' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' },
  { value: 'hourly', label: '每小时' },
  { value: 'minutes', label: '按分钟间隔' },
  { value: 'custom', label: '自定义 Cron' }
]
const weekdays = [
  { value: 'MON', label: '周一' },
  { value: 'TUE', label: '周二' },
  { value: 'WED', label: '周三' },
  { value: 'THU', label: '周四' },
  { value: 'FRI', label: '周五' },
  { value: 'SAT', label: '周六' },
  { value: 'SUN', label: '周日' }
]
const WEEK_LABEL = Object.fromEntries(weekdays.map((d) => [d.value, d.label]))

// Spring cron 的星期字段同时接受名称(MON)和数字(0/7=周日, 1=周一), 展示与回填都要认
const DAY_NAMES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']

function normalizeWeek(w) {
  const s = String(w || '').toUpperCase()
  return /^[0-7]$/.test(s) ? DAY_NAMES[Number(s) % 7] : s
}

/** 哪些类型需要"几点几分" */
const needTimeOfDay = computed(() =>
  ['daily', 'workdays', 'weekly', 'monthly'].includes(form.scheduleType))

/** 由可读选项拼出的 cron; 自定义模式直接用用户填的 */
const effectiveCron = computed(() => {
  const [h, m] = String(form.timeOfDay || DEFAULT_TIME).split(':').map(Number)
  switch (form.scheduleType) {
    case 'daily': return `0 ${m} ${h} * * *`
    case 'workdays': return `0 ${m} ${h} * * MON-FRI`
    case 'weekly': return `0 ${m} ${h} * * ${form.weekday}`
    case 'monthly': return `0 ${m} ${h} ${form.monthDay} * *`
    case 'hourly': return `0 ${form.minuteOfHour} * * * *`
    case 'minutes': return `0 0/${Math.min(59, Math.max(1, form.intervalMinutes || 1))} * * * *`
    default: return (form.cron || '').trim()
  }
})

// ===== 模型 =====
const platformChatModels = computed(() =>
  modelStore.chatModels.filter((m) => !String(m.name).startsWith('custom:')))
const customChatModels = computed(() =>
  modelStore.chatModels.filter((m) => String(m.name).startsWith('custom:')))

function modelLabel(name) {
  if (!name) return '跟随专家/平台默认'
  const hit = modelStore.chatModels.find((m) => m.name === name)
  return hit ? hit.label : name
}

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
// 关联专家可选: 未关联时用默认助手执行
function agentLabel(t) {
  return t.agentName || t.agentId || '默认助手（未关联）'
}
function notifyChannelLabel(code) {
  if (!code) return '跟随默认渠道'
  const hit = notifyChannels.value.find((c) => c.value === code)
  return hit ? hit.label : code
}
function notifyStatusLabel(s) {
  // skipped 不是故障: 总开关关闭 / 触发节流都会走到这里, 单独一种配色避免误判为失败
  return ({ success: '推送成功', failed: '推送失败', skipped: '已跳过' })[s] || s
}
function notifyTagType(s) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  return 'info'
}
function formatTime(t) {
  if (!t) return '—'
  // 后端返回 LocalDateTime, 序列化为数组或 ISO 字符串, 兜底处理
  const d = new Date(typeof t === 'string' ? t.replace(' ', 'T') : t)
  if (isNaN(d.getTime())) return String(t)
  return d.toLocaleString('zh-CN', { hour12: false })
}
/**
 * cron → 人话。只覆盖平台自己生成的几种形态 (列表/详情展示用),
 * 其它一律回落成 "自定义 (原始 cron)", 绝不猜错时间点。
 */
function cronText(cron) {
  const p = String(cron || '').trim().split(/\s+/)
  if (p.length !== 6) return '自定义时间'
  const [s, m, h, d, mo, w] = p
  const week = normalizeWeek(w)
  const star = week === '*' || week === '?'
  const hhmm = (h, m) => `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
  if (s !== '0') return `自定义 (${cron})`
  if (h === '*' && m === '*' && d === '*' && mo === '*' && star) return '每分钟'
  if (h === '*' && /^\d+$/.test(m) && d === '*' && mo === '*' && star) return `每小时第 ${m} 分`
  const everyN = /^(?:\*|0)\/(\d+)$/.exec(m)
  if (h === '*' && everyN && d === '*' && mo === '*' && star) return `每 ${everyN[1]} 分钟`
  if (/^\d+$/.test(h) && /^\d+$/.test(m) && mo === '*') {
    const t = hhmm(h, m)
    if (d === '*' && star) return `每天 ${t}`
    if (d === '*' && week === 'MON-FRI') return `工作日 ${t}`
    if (d === '*' && WEEK_LABEL[week]) return `每${WEEK_LABEL[week]} ${t}`
    if (/^\d+$/.test(d) && star) return `每月 ${d} 日 ${t}`
  }
  return `自定义 (${cron})`
}

/** cron → 表单选项 (编辑存量任务时用); 认不出来就落到"自定义", 原样保留 */
function parseCronToForm(cron) {
  form.timeOfDay = DEFAULT_TIME
  form.weekday = 'MON'
  form.monthDay = 1
  form.minuteOfHour = 0
  form.intervalMinutes = 30
  form.cron = cron || ''
  const p = String(cron || '').trim().split(/\s+/)
  if (p.length !== 6) { form.scheduleType = 'custom'; return }
  const [s, m, h, d, mo, w] = p
  const week = normalizeWeek(w)
  const star = week === '*' || week === '?'
  if (s === '0' && /^\d+$/.test(m) && /^\d+$/.test(h) && mo === '*') {
    const time = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
    if (d === '*' && star) { form.scheduleType = 'daily'; form.timeOfDay = time; return }
    if (d === '*' && week === 'MON-FRI') { form.scheduleType = 'workdays'; form.timeOfDay = time; return }
    if (d === '*' && WEEK_LABEL[week]) {
      form.scheduleType = 'weekly'; form.weekday = week; form.timeOfDay = time; return
    }
    if (/^\d+$/.test(d) && star) {
      form.scheduleType = 'monthly'; form.monthDay = Number(d); form.timeOfDay = time; return
    }
  }
  if (s === '0' && h === '*' && /^\d+$/.test(m) && d === '*' && mo === '*' && star) {
    form.scheduleType = 'hourly'; form.minuteOfHour = Number(m); return
  }
  const everyN = /^(?:\*|0)\/(\d+)$/.exec(m)
  if (s === '0' && h === '*' && everyN && d === '*' && mo === '*' && star) {
    form.scheduleType = 'minutes'; form.intervalMinutes = Number(everyN[1]); return
  }
  form.scheduleType = 'custom'
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
  runs.value = []
  loadRuns()
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
  form.agentId = t.agentId || ''
  form.model = t.model || ''
  parseCronToForm(t.cron)
  form.inputMessage = t.inputMessage || ''
  form.enabled = t.enabled
  form.notifyEnabled = !!t.notifyEnabled
  form.notifyChannel = t.notifyChannel || ''
  dialogVisible.value = true
}

function resetForm() {
  form.id = ''
  form.name = ''
  form.agentId = ''
  form.model = ''
  form.scheduleType = 'daily'
  form.timeOfDay = DEFAULT_TIME
  form.weekday = 'MON'
  form.monthDay = 1
  form.minuteOfHour = 0
  form.intervalMinutes = 30
  form.cron = ''
  form.inputMessage = ''
  form.enabled = true
  form.notifyEnabled = false
  form.notifyChannel = ''
}

async function submitForm() {
  if (!form.name || !form.name.trim()) { ElMessage.warning('请填写任务名称'); return }
  const cron = effectiveCron.value
  if (!cron) { ElMessage.warning('请填写 Cron 表达式'); return }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      agentId: form.agentId || null,
      model: form.model || null,
      cron,
      inputMessage: form.inputMessage || null,
      enabled: form.enabled,
      notifyEnabled: form.notifyEnabled,
      notifyChannel: form.notifyChannel || null
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

async function triggerTask(notify = false) {
  if (!currentTask.value) return
  triggering.value = true
  triggerResult.value = null
  try {
    triggerResult.value = await triggerScheduledTask(currentTask.value.id, null, notify)
    const r = triggerResult.value
    if (notify) {
      // 推送结果当场回显: 这是用户点「执行并推送」唯一想知道的事
      if (r && r.notifyStatus === 'success') {
        ElMessage.success('执行完成，已推送到' + notifyChannelLabel(currentTask.value.notifyChannel))
      } else if (r && r.notifyStatus === 'failed') {
        ElMessage.error('执行完成，但推送失败：' + (r.notifyReason || '未知原因'))
      } else {
        ElMessage.warning('执行完成，但未推送：' + ((r && r.notifyReason) || '未开启结果推送'))
      }
    } else {
      ElMessage.success('触发完成')
    }
    // 刷新列表以获取最新状态, 并重新拉历史（手动触发也会落一条执行记录）
    await loadTasks()
    await loadRuns()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '触发失败')
  } finally {
    triggering.value = false
  }
}

async function testNotify() {
  if (!currentTask.value) return
  testingNotify.value = true
  try {
    const r = await testScheduledTaskNotify(currentTask.value.id)
    if (r && r.success) {
      // 渠道名从任务配置取: 写死"企业微信"的话, 钉钉用户会去企微里找一条永远不会有的消息
      ElMessage.success('测试推送已发出（' + notifyChannelLabel(currentTask.value.notifyChannel) + '），请查看对应群消息')
    } else {
      // skipped 也走这里: 总开关没开时用户需要知道"不是网络问题, 是配置没开"
      ElMessage.error((r && (r.error || r.reason)) || '推送未成功')
    }
  } catch (e) {
    ElMessage.error(extractMessage(e) || '推送失败')
  } finally {
    testingNotify.value = false
  }
}

async function openPreview() {
  if (!currentTask.value) return
  previewVisible.value = true
  previewData.value = null
  previewTab.value = 'success'
  try {
    // 传任务的渠道；任务没选则传 null，由后端回退全局默认
    previewData.value = await previewScheduledTaskNotify(
      currentTask.value.id, currentTask.value.notifyChannel || null)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '预览失败')
    previewVisible.value = false
  }
}

async function loadRuns() {
  if (!currentTask.value) return
  runsLoading.value = true
  try {
    runs.value = await getScheduledTaskRuns(currentTask.value.id, 20)
  } catch (e) {
    // 历史是辅助信息，失败不该打断主流程
    console.warn('[ScheduledTaskView] 加载执行历史失败:', e)
    runs.value = []
  } finally {
    runsLoading.value = false
  }
}

async function openRun(r) {
  if (!currentTask.value) return
  runVisible.value = true
  runDetail.value = null
  try {
    runDetail.value = await getScheduledTaskRun(currentTask.value.id, r.id)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载执行详情失败')
    runVisible.value = false
  }
}

function formatDuration(ms) {
  if (ms == null || ms < 0) return '—'
  if (ms < 1000) return ms + ' ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + ' s'
  return Math.floor(ms / 60000) + ' 分 ' + Math.round((ms % 60000) / 1000) + ' 秒'
}

onMounted(async () => {
  await loadTasks()
  loadAgents()
  loadRuns()
  loadNotifyConfigs()
  // 模型列表用于"执行模型"下拉; store 内部幂等, 重复调用不会重复请求
  modelStore.loadModels().catch(() => {})
})</script>

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
.task-item.active { background: linear-gradient(135deg, var(--dcdr-accent-wash) 0%, var(--dcdr-accent-wash) 100%); }
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
.task-toolbar-left h2 { margin: 0; font-size: 15px; font-weight: 600; }
.task-toolbar-right { display: flex; gap: 8px; }

.task-detail { padding: 16px 0; }
.detail-row { display: flex; padding: 10px 0; border-bottom: 1px dashed var(--dcdr-border); }
.detail-label { width: 100px; color: var(--dcdr-text-3); font-size: 13px; flex-shrink: 0; }
.detail-value { flex: 1; color: var(--dcdr-text-1); font-size: 13px; }
.detail-value.pre { white-space: pre-wrap; word-break: break-word; }
.detail-value code { background: var(--dcdr-bg-subtle); padding: 2px 6px; border-radius: 4px; font-family: monospace; }
.detail-value.error { color: var(--dcdr-danger); }
/* skipped 的原因不是故障: 与 failed 用同一种红色会让人以为推送坏了 */
.detail-value.muted { color: var(--dcdr-text-3); }
.cron-hint { color: var(--dcdr-text-3); font-size: 12px; margin-top: 4px; }
.cron-raw { margin-left: 10px; background: var(--dcdr-bg-subtle); padding: 1px 6px; border-radius: 4px; font-family: var(--dcdr-font-mono); font-size: 11px; color: var(--dcdr-text-3); }
/* 执行时间的可读配置行 */
.sched-config { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; width: 100%; }
.sched-word { font-size: 12.5px; color: var(--dcdr-text-2); }

.trigger-result { margin-top: 16px; background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 12px 14px; }
.tr-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; font-weight: 500; }
.tr-meta { color: var(--dcdr-text-3); font-size: 12px; font-weight: 400; }
/* 未推送/推送失败的原因: 单独一行, 不要塞进正文里被长输出淹没 */
.tr-note { margin-bottom: 8px; font-size: 12px; color: var(--dcdr-text-3); }
.tr-note.warn { color: var(--dcdr-danger); }
.tr-output { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 4px; padding: 8px; white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 12px; max-height: 240px; overflow-y: auto; margin: 0; }

.empty-detail { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--dcdr-text-3); gap: 12px; }
.empty-icon { font-size: 56px; }

.cron-presets { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 4px; }

/* V3.3 结果推送 */
.notify-row { display: flex; align-items: center; gap: 10px; }
.notify-hint { color: var(--dcdr-text-3); font-size: 12px; line-height: 1.6; margin-top: 4px; }
.notify-channel-name { margin-left: 8px; color: var(--dcdr-text-2); font-size: 12px; }
.notify-time { margin-left: 8px; color: var(--dcdr-text-3); font-size: 12px; }

/* 消息预览 */
.preview-meta { font-size: 12px; color: var(--dcdr-text-3); margin-bottom: 10px; }
.preview-pre { background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 6px; padding: 10px 12px; white-space: pre-wrap; word-break: break-word; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.6; max-height: 320px; overflow-y: auto; margin: 0; }
.preview-hint { font-size: 12px; color: var(--dcdr-text-3); margin-top: 10px; line-height: 1.6; }
.preview-loading { padding: 40px; text-align: center; color: var(--dcdr-text-3); font-size: 13px; }

/* 执行历史 */
.run-history { margin-top: 20px; }
.rh-head { display: flex; align-items: center; justify-content: space-between; padding-bottom: 8px; border-bottom: 1px solid var(--dcdr-border); font-size: 13px; font-weight: 500; color: var(--dcdr-text-1); }
.rh-actions { display: flex; align-items: center; gap: 8px; }
.rh-count { font-size: 12px; font-weight: 400; color: var(--dcdr-text-3); }
.rh-empty { padding: 20px 0; text-align: center; color: var(--dcdr-text-3); font-size: 12px; }
.rh-list { max-height: 260px; overflow-y: auto; }
.rh-item { display: flex; align-items: center; gap: 10px; padding: 8px 4px; border-bottom: 1px dashed var(--dcdr-border); cursor: pointer; }
.rh-item:hover { background: var(--dcdr-bg-subtle); }
.rh-time { font-size: 12px; color: var(--dcdr-text-2); flex-shrink: 0; }
.rh-dur { font-size: 12px; color: var(--dcdr-text-3); flex-shrink: 0; min-width: 56px; }
.rh-preview { flex: 1; min-width: 0; font-size: 12px; color: var(--dcdr-text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rh-link { margin-left: auto; font-size: 12px; color: var(--dcdr-accent); text-decoration: none; }
</style>
