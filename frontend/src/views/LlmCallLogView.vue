<template>
  <div class="llm-log-mode">
    <div class="llm-log-header">
      <h2>调用链日志</h2>
      <p class="subtitle">每次对话请求的完整 LLM 调用链(路由判断 / 各生成轮 / 工具步骤 / token / 耗时), 独立于对话内容归档</p>
    </div>

    <div class="toolbar">
      <el-button size="small" @click="loadLogs" :loading="loading">刷新</el-button>
      <el-select v-if="authStore.isAdmin" v-model="filterUserId" size="small" style="width:160px" @change="loadLogs">
        <el-option label="仅自己" value="" />
        <el-option label="全部用户" value="all" />
      </el-select>
      <div class="stat-block" v-if="logs.length">
        <span class="stat-item">共 {{ total }} 条</span>
        <span class="stat-item">LLM 平均 {{ avgLlmCalls }} 次/条</span>
      </div>
    </div>

    <div class="log-list" v-loading="loading">
      <div v-for="log in logs" :key="log.id" class="log-card">
        <div class="log-head">
          <el-tag size="small" :type="endTag(log.endSignal)">{{ endLabel(log.endSignal) }}</el-tag>
          <span class="log-turn" title="本轮追踪短 ID">#{{ log.turnId }}</span>
          <span v-if="log.userId && authStore.isAdmin" class="log-user">{{ shortId(log.userId) }}</span>
          <span class="log-llm">🤖 {{ log.llmCalls || 0 }} 次</span>
          <span v-if="log.toolSteps" class="log-tool">🔧 {{ log.toolSteps }} 步</span>
          <span class="log-tokens">🪙 {{ fmtTokens(log.totalInTokens) }}↑ / {{ fmtTokens(log.totalOutTokens) }}↓</span>
          <span class="log-time">{{ formatTime(log.createdAt) }}</span>
        </div>
        <div class="log-message">
          <span class="rl">用户消息:</span> {{ log.userMessage }}
        </div>
        <div class="log-chain" v-if="log.chain">
          <span class="rl">链路:</span> {{ log.chain }}
          <span class="log-dur">⏱ {{ fmtDur(log.totalDurationMs) }}</span>
        </div>
        <div v-if="log.steps && log.steps.length" class="log-steps-wrap">
          <button class="steps-toggle" @click="toggle(log.id)">
            <span>步骤明细 ({{ log.steps.length }})</span>
            <span class="caret">{{ expanded.has(log.id) ? '▲' : '▼' }}</span>
          </button>
          <div v-if="expanded.has(log.id)" class="steps-list">
            <div v-for="(s, i) in log.steps" :key="i" class="step-item">
              <div class="step-head">
                <span class="step-badge" :class="stepClass(s.kind)">
                  {{ stepLabel(s) }}
                </span>
                <span v-if="s.model" class="step-model">{{ s.model }}</span>
                <span v-if="s.purpose" class="step-purpose">{{ s.purpose }}</span>
                <span v-if="s.query" class="step-purpose" :title="s.query">{{ s.query }}</span>
                <span v-if="stepTokens(s)" class="step-tokens">🪙 {{ stepTokens(s) }}</span>
                <span v-if="s.hits !== undefined && s.hits !== null" class="step-tokens">命中 {{ s.hits }} 条</span>
                <span v-if="s.kbCount !== undefined && s.kbCount !== null" class="step-tokens">· {{ s.kbCount }} 库</span>
                <span v-if="s.costMs !== undefined && s.costMs !== null" class="step-cost">{{ fmtDur(s.costMs) }}</span>
                <span v-if="s.interrupted" class="step-interrupted">已中断</span>
              </div>
            </div>
          </div>
        </div>
        <div class="card-foot" v-if="authStore.isAdmin">
          <button class="detail-toggle" @click="openDetail(log)">🔍 查看详情</button>
        </div>
      </div>
      <div v-if="!loading && !logs.length" class="empty-tip">
        暂无调用链日志<br />
        在对话页面任意提问一次即可生成一条完整调用链记录
      </div>
    </div>

    <div class="pager" v-if="total > pageSize">
      <el-pagination background layout="total, sizes, prev, pager, next" :total="total"
                     :page-sizes="[10, 20, 50, 100]" v-model:page-size="pageSize"
                     v-model:current-page="page" @current-change="loadLogs" @size-change="onSizeChange" />
    </div>

    <!-- 调用链详情抽屉: 展示每次 LLM 调用的请求参数(温度/上下文规模/工具数)与工具调用参数/结果摘要 -->
    <el-drawer v-model="detailVisible" size="620px" :title="detailTitle">
      <template v-if="detailLog">
        <div class="d-section">
          <div class="d-title">基本信息</div>
          <div class="d-grid">
            <span class="d-k">追踪 ID</span><span class="d-v mono">{{ detailLog.turnId }}</span>
            <span class="d-k">结束信号</span><span class="d-v"><el-tag size="small" :type="endTag(detailLog.endSignal)">{{ endLabel(detailLog.endSignal) }}</el-tag></span>
            <span class="d-k">会话 ID</span><span class="d-v mono">{{ detailLog.sessionId || '-' }}</span>
            <template v-if="detailLog.userId && authStore.isAdmin">
              <span class="d-k">用户</span><span class="d-v mono">{{ detailLog.userId }}</span>
            </template>
            <span class="d-k">时间</span><span class="d-v">{{ formatTime(detailLog.createdAt) }}</span>
            <span class="d-k">汇总</span><span class="d-v">🤖 {{ detailLog.llmCalls || 0 }} 次 · 🔧 {{ detailLog.toolSteps || 0 }} 步 · 🪙 {{ fmtTokens(detailLog.totalInTokens) }}↑/{{ fmtTokens(detailLog.totalOutTokens) }}↓ · ⏱ {{ fmtDur(detailLog.totalDurationMs) }}</span>
          </div>
          <div class="d-block">
            <div class="d-k">用户消息</div>
            <pre class="d-pre">{{ detailLog.userMessage || '(空)' }}</pre>
          </div>
          <div class="d-block" v-if="detailLog.chain">
            <div class="d-k">链路</div>
            <pre class="d-pre">{{ detailLog.chain }}</pre>
          </div>
        </div>

        <div class="d-section" v-for="(s, i) in detailLog.steps || []" :key="i">
          <div class="d-title">{{ stepBadgeLabel(s, i) }}</div>
          <div class="d-grid">
            <template v-for="(e, ei) in stepEntries(s)" :key="ei">
              <span class="d-k">{{ e.label }}</span>
              <span class="d-v" :class="{ mono: e.mono }">{{ e.value }}</span>
            </template>
          </div>
          <div class="d-block" v-for="(c, ci) in s.calls || []" :key="'c' + ci">
            <div class="d-k">工具调用 {{ ci + 1 }}: {{ c.name }}</div>
            <pre class="d-pre" v-if="c.args">参数: {{ c.args }}</pre>
            <pre class="d-pre" v-if="c.resultPreview">结果摘要: {{ c.resultPreview }}</pre>
          </div>
          <details class="d-raw" v-if="s.systemPrompt">
            <summary>本次提交的 System Prompt</summary>
            <pre class="d-pre">{{ s.systemPrompt }}</pre>
          </details>
          <details class="d-raw" v-if="s.messages && s.messages.length">
            <summary>本次提交的完整消息列表 ({{ s.messages.length }} 条)</summary>
            <div v-for="(mm, mi) in s.messages" :key="mi" class="d-msg">
              <div class="d-msg-head">
                <span class="d-msg-role" :class="'role-' + mm.role">{{ mm.role }}</span>
                <span class="d-msg-name" v-if="mm.name">{{ mm.name }}</span>
                <span class="d-msg-name" v-if="mm.toolCallId">tool_call_id: {{ mm.toolCallId }}</span>
              </div>
              <pre class="d-pre">{{ mm.content || '(空)' }}</pre>
              <pre class="d-pre" v-if="mm.toolCalls">tool_calls: {{ mm.toolCalls }}</pre>
            </div>
          </details>
          <details class="d-raw">
            <summary>原始 JSON</summary>
            <pre class="d-pre">{{ JSON.stringify(s, null, 2) }}</pre>
          </details>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getLlmCallLogs, extractMessage } from '@/api'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const loading = ref(false)
const logs = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

/** 切换每页条数: 回到第一页重新加载(避免当前页越界) */
function onSizeChange() {
  page.value = 1
  loadLogs()
}
const filterUserId = ref('')
const expanded = ref(new Set())

// ===== 详情抽屉: 展示一次调用链的完整步骤与每次 LLM 调用的请求参数 =====
const detailVisible = ref(false)
const detailLog = ref(null)
const detailTitle = computed(() => detailLog.value ? `调用链详情 · #${detailLog.value.turnId}` : '调用链详情')

function openDetail(log) {
  detailLog.value = log
  detailVisible.value = true
}

/** 步骤字段中文标签(未列出的字段原样显示 key) */
const STEP_LABELS = {
  purpose: '用途', model: '模型', temperature: '温度',
  messageCount: '上下文消息数', toolSpecCount: '注入工具数',
  inTokens: '输入 token', outTokens: '输出 token', costMs: '耗时',
  interrupted: '是否中断', tools: '工具', query: '检索词',
  kbCount: '知识库数', hits: '命中条数'
}

/** 把步骤 Map 转为键值对列表(kind/seq 已在标题, calls 单独渲染) */
function stepEntries(s) {
  const out = []
  for (const k of Object.keys(s)) {
    if (k === 'kind' || k === 'seq' || k === 'calls' || k === 'systemPrompt' || k === 'messages') continue
    const v = s[k]
    let label = STEP_LABELS[k] || k
    let value = v
    let mono = k === 'model'
    if (k === 'costMs') value = fmtDur(v)
    else if (k === 'inTokens' || k === 'outTokens') value = v == null ? '-' : fmtTokens(v)
    else if (k === 'interrupted') value = v ? '是' : '否'
    else if (typeof v === 'object') value = JSON.stringify(v)
    out.push({ label, value: value == null || value === '' ? '-' : value, mono })
  }
  return out
}

function stepBadgeLabel(s, i) {
  if (s.kind === 'llm') return `#${s.seq} LLM 调用 · ${s.purpose}`
  if (s.kind === 'tool') return `#${s.seq} 工具执行 · ${s.tools}`
  if (s.kind === 'rag') return `#${s.seq} RAG 检索`
  return `步骤 ${i + 1}`
}

const avgLlmCalls = computed(() => {
  if (!logs.value.length) return '0'
  const sum = logs.value.reduce((a, l) => a + (l.llmCalls || 0), 0)
  return (sum / logs.value.length).toFixed(1)
})

function toggle(id) {
  const next = new Set(expanded.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expanded.value = next
}

function endLabel(s) {
  return ({ ON_COMPLETE: '完成', ON_ERROR: '错误', ON_CANCEL: '取消' })[s] || s || '-'
}
function endTag(s) {
  if (s === 'ON_ERROR') return 'danger'
  if (s === 'ON_CANCEL') return 'warning'
  return 'success'
}
function stepLabel(s) {
  if (s.kind === 'rag') return '📚 RAG'
  return s.kind === 'tool' ? '🔧' : '🤖'
}
function stepClass(kind) {
  if (kind === 'rag') return 'step-rag'
  return kind === 'tool' ? 'step-tool' : 'step-llm'
}
function stepTokens(s) {
  const i = s.inTokens, o = s.outTokens
  if (i == null && o == null) return ''
  return `${fmtTokens(i)}↑ / ${fmtTokens(o)}↓`
}
function fmtTokens(n) {
  if (n == null) return '0'
  return Number(n).toLocaleString()
}
function fmtDur(ms) {
  if (ms == null) return ''
  if (ms >= 60000) return (ms / 60000).toFixed(1) + 'm'
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's'
  return ms + 'ms'
}
function formatTime(t) {
  if (!t) return ''
  try { return new Date(t).toLocaleString('zh-CN') } catch { return t }
}
function shortId(id) {
  if (!id) return ''
  return id.length > 12 ? id.slice(0, 6) + '…' + id.slice(-6) : id
}

async function loadLogs() {
  loading.value = true
  try {
    const params = { page: page.value - 1, size: pageSize.value }
    if (authStore.isAdmin && filterUserId.value) params.userId = filterUserId.value
    const data = await getLlmCallLogs(params)
    logs.value = Array.isArray(data) ? data : (data.content || [])
    total.value = Array.isArray(data) ? data.length : (data.totalElements || data.length)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载调用链日志失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadLogs()
})
</script>

<style scoped>
/* flex:1 + overflow-y:auto: 根容器自行滚动(父级 .app-main 为 overflow:hidden, 不加则内容超高被裁剪无法下滑) */
.llm-log-mode { flex: 1; overflow-y: auto; padding: 20px 24px; max-width: 960px; margin: 0 auto; }
.llm-log-header { margin-bottom: 16px; }
.llm-log-header h2 { margin: 0 0 4px; font-size: 20px; color: #1d2129; }
.subtitle { margin: 0; font-size: 13px; color: #86909c; }

.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.stat-block { margin-left: auto; display: flex; gap: 14px; font-size: 12px; color: #86909c; }

.log-list { display: flex; flex-direction: column; gap: 10px; min-height: 200px; }
.log-card { background: #fff; border: 1px solid #e8e8e8; border-radius: 8px; padding: 12px 14px; transition: box-shadow .2s; }
.log-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.log-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.log-turn { font-size: 12px; font-weight: 600; color: #667eea; font-family: monospace; }
.log-user { font-size: 11px; color: #86909c; font-family: monospace; }
.log-llm, .log-tool { font-size: 12px; color: #4a5568; }
.log-tokens { font-size: 11px; color: #86909c; }
.log-time { font-size: 11px; color: #a0a4ab; margin-left: auto; }
.log-message { font-size: 13px; color: #1d2129; line-height: 1.6; word-break: break-word; }
.log-message .rl, .log-chain .rl { color: #86909c; font-size: 12px; }
.log-chain { font-size: 12px; color: #606266; margin-top: 6px; line-height: 1.6; word-break: break-word; }
.log-chain .log-dur { color: #86909c; margin-left: 6px; }

.log-steps-wrap { margin-top: 8px; }
.steps-toggle { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: #667eea; background: #f2f4ff; border: none; border-radius: 6px; padding: 4px 10px; cursor: pointer; }
.steps-toggle .caret { font-size: 10px; }
.steps-list { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.step-item { border: 1px solid #e3e8f2; border-radius: 6px; background: #fafbff; padding: 6px 10px; }
.step-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.step-badge { font-size: 12px; font-weight: 600; border-radius: 6px; padding: 2px 8px; }
.step-llm { color: #667eea; background: #eef0ff; }
.step-tool { color: #059669; background: #e6f7f1; }
.step-rag { color: #b45309; background: #fef3c7; }
.step-model, .step-purpose { font-size: 11px; color: #9aa7c2; font-family: monospace; }
.step-purpose { color: #606266; }
.step-tokens, .step-cost { font-size: 11px; color: #86909c; }
.step-interrupted { font-size: 11px; color: #e6a23c; }

.empty-tip { text-align: center; color: #a0a4ab; padding: 60px 20px; font-size: 13px; line-height: 1.8; }
.pager { margin-top: 20px; display: flex; justify-content: center; }

/* ===== 详情抽屉 ===== */
.card-foot { margin-top: 8px; }
.detail-toggle { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: #667eea; background: #f2f4ff; border: none; border-radius: 6px; padding: 4px 10px; cursor: pointer; }
.detail-toggle:hover { background: #e6eaff; }
.d-section { margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid #f0f2f7; }
.d-section:last-child { border-bottom: none; }
.d-title { font-weight: 600; font-size: 13px; color: #1d2129; margin-bottom: 8px; }
.d-grid { display: grid; grid-template-columns: 110px 1fr; gap: 6px 10px; font-size: 12px; align-items: baseline; }
.d-k { color: #86909c; }
.d-v { color: #1d2129; word-break: break-all; }
.d-v.mono { font-family: monospace; }
.d-block { margin-top: 10px; }
.d-pre { margin: 4px 0 0; background: #f8f9fc; border-radius: 6px; padding: 8px 10px; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; max-height: 220px; overflow-y: auto; }
.d-raw { margin-top: 10px; }
.d-raw summary { font-size: 12px; color: #667eea; cursor: pointer; user-select: none; }

/* 完整消息列表 */
.d-msg { margin-top: 8px; border: 1px solid #e3e8f2; border-radius: 6px; padding: 6px 10px; background: #fafbff; }
.d-msg-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.d-msg-role { font-size: 11px; font-weight: 600; border-radius: 4px; padding: 1px 6px; }
.d-msg-role.role-system { color: #b45309; background: #fef3c7; }
.d-msg-role.role-user { color: #059669; background: #e6f7f1; }
.d-msg-role.role-assistant { color: #667eea; background: #eef0ff; }
.d-msg-role.role-tool { color: #9333ea; background: #f3e8ff; }
.d-msg-name { font-size: 11px; color: #9aa7c2; font-family: monospace; }
.d-msg .d-pre { background: #fff; border: 1px solid #eef1f7; max-height: 260px; }
</style>