<template>
  <div class="llm-log-mode">
    <div class="llm-log-header">
      <h2>调用链日志</h2>
      <p class="subtitle">每次对话请求的完整 LLM 调用链(路由判断 / 各生成轮 / 工具步骤 / token / 耗时), 独立于对话内容归档</p>
    </div>

    <div class="toolbar">
      <el-button size="small" @click="loadLogs" :loading="loading">刷新</el-button>
      <el-select v-if="authStore.isAdmin" v-model="filterUserId" size="small" style="width:160px" @change="loadLogs">
        <!-- 取值必须非空: 空串会被 el-select 当作"未选择", 于是默认态下输入框什么都不显示,
             用户看不出当前查的是谁(而"全部用户"取值是 all, 标签能正常渲染 —— 两个选项表现不一致)。
             默认态本身就是"仅自己", 所以它该是一个真实取值而不是"没有值" -->
        <el-option label="仅自己" value="self" />
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
        <!-- 模型输出: 取最后一轮 LLM 的本轮输出(即该次请求的最终回答), 默认可见, 无需展开步骤 -->
        <div class="log-output" v-if="finalOutput(log)">
          <span class="rl">模型输出:</span> {{ finalOutput(log) }}
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
                <!-- 检索来源: 补这个字段之前, "这次检索是谁发起的"只能靠步骤位置反推 ——
                     看到 rag 步骤却在工具调用里找不到 knowledge_base_search 时尤其难判断 -->
                <span v-if="s.ragSource" class="step-src">
                  {{ RAG_SOURCE_LABELS[s.ragSource] || s.ragSource }}
                </span>
                <span v-if="s.model" class="step-model">{{ s.model }}</span>
                <span v-if="s.purpose" class="step-purpose">{{ s.purpose }}</span>
                <span v-if="s.query" class="step-purpose" :title="s.query">{{ s.query }}</span>
                <span v-if="stepTokens(s)" class="step-tokens">🪙 {{ stepTokens(s) }}</span>
                <span v-if="s.hits !== undefined && s.hits !== null" class="step-tokens">命中 {{ s.hits }} 条</span>
                <span v-if="s.kbCount !== undefined && s.kbCount !== null" class="step-tokens">· {{ s.kbCount }} 库</span>
                <span v-if="s.costMs !== undefined && s.costMs !== null" class="step-cost">{{ fmtDur(s.costMs) }}</span>
                <span v-if="s.outputChars" class="step-tokens" title="本轮模型输出字符数">📝 {{ fmtTokens(s.outputChars) }} 字</span>
                <span v-if="s.interrupted" class="step-interrupted">已中断</span>
              </div>
              <!-- 本轮输出预览(列表接口已返回, 截断 500 字符): 多轮时可逐轮对照 -->
              <div v-if="s.outputPreview" class="step-output">{{ s.outputPreview }}</div>
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
      <div v-loading="detailLoading" class="d-wrap">
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
          <!-- 本轮模型输出: 中断/出错时记录的是"已产出的部分", 是判断模型吐到哪一步的唯一现场 -->
          <div class="d-block" v-if="s.outputPreview">
            <div class="d-k">
              本轮模型输出
              <span v-if="s.outputChars" class="d-hint">· {{ fmtTokens(s.outputChars) }} 字</span>
              <span v-if="s.outputChars > 500" class="d-hint">· 仅显示前 500 字, 完整正文见该会话消息</span>
            </div>
            <pre class="d-pre">{{ s.outputPreview }}</pre>
          </div>
          <details class="d-raw" v-if="s.systemPrompt">
            <summary>本次提交的 System Prompt</summary>
            <pre class="d-pre">{{ s.systemPrompt }}</pre>
          </details>
          <details class="d-raw" v-if="s.wireRequest">
            <summary>实际发给上游的请求体（由 Provider 组装，权威口径）</summary>
            <pre class="d-pre">{{ prettyJson(s.wireRequest) }}</pre>
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
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getLlmCallLogs, getLlmCallLogDetail, extractMessage } from '@/api'
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
const filterUserId = ref('self')
const expanded = ref(new Set())

// ===== 详情抽屉: 展示一次调用链的完整步骤与每次 LLM 调用的请求参数 =====
// 列表接口不返回 systemPrompt / messages(单条可达数十 KB), 故打开抽屉时按 id 拉完整详情;
// 先用列表数据渲染基本信息(秒开), 详情返回后整体替换。
const detailVisible = ref(false)
const detailLog = ref(null)
const detailLoading = ref(false)
const detailTitle = computed(() => detailLog.value ? `调用链详情 · #${detailLog.value.turnId}` : '调用链详情')

async function openDetail(log) {
  detailLog.value = log
  detailVisible.value = true
  detailLoading.value = true
  try {
    detailLog.value = await getLlmCallLogDetail(log.id)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载调用链详情失败')
  } finally {
    detailLoading.value = false
  }
}

/** 步骤字段中文标签(未列出的字段原样显示 key) */
const STEP_LABELS = {
  purpose: '用途', model: '模型', temperature: '温度',
  messageCount: '上下文消息数', toolSpecCount: '注入工具数',
  inTokens: '输入 token', outTokens: '输出 token', costMs: '耗时',
  interrupted: '是否中断', tools: '工具', query: '检索词',
  kbCount: '知识库数', hits: '命中条数', outputChars: '输出字数',
  // 记忆召回步骤(kind=memory): 与 rag 共用 query/hits/costMs, 额外的两个是它特有的
  chars: '注入字数', memoryIds: '召回记忆',
  ragSource: '检索来源'
}


/** 请求体 JSON 字符串 → 缩进美化(解析失败则原样返回, 不阻断展示) */
function prettyJson(raw) {
  try { return JSON.stringify(JSON.parse(raw), null, 2) } catch { return raw }
}

/** 把步骤 Map 转为键值对列表(kind/seq 已在标题, calls 单独渲染, 输出预览单独成块) */
function stepEntries(s) {
  const out = []
  for (const k of Object.keys(s)) {
    if (k === 'kind' || k === 'seq' || k === 'calls' || k === 'systemPrompt' || k === 'messages' || k === 'wireRequest' || k === 'outputPreview') continue
    // 后端为某字段另发了中文名时（约定 key + 'Label'，如 ragSource + ragSourceLabel），
    // 由原值那一行合并展示，这里跳过 —— 否则会出现两行都叫「检索来源」
    if (s[k + 'Label'] !== undefined) continue
    const v = s[k]
    let label = STEP_LABELS[k] || k
    let value = v
    let mono = k === 'model'
    if (k === 'costMs') value = fmtDur(v)
    else if (k === 'inTokens' || k === 'outTokens') value = v == null ? '-' : fmtTokens(v)
    else if (k === 'interrupted') value = v ? '是' : '否'
    // 中文名取自后端，原值仍留在括号里：它是按日志/库侧聚合时要 grep 的东西，只留中文反而查不下去
    else if (k === 'ragSource') value = s.ragSourceLabel ? `${s.ragSourceLabel}（${v}）` : v
    else if (k === 'outputChars') value = fmtTokens(v) + ' 字'
    else if (typeof v === 'object') value = JSON.stringify(v)
    out.push({ label, value: value == null || value === '' ? '-' : value, mono })
  }
  return out
}

function stepBadgeLabel(s, i) {
  if (s.kind === 'llm') return `#${s.seq} LLM 调用 · ${s.purpose}`
  if (s.kind === 'tool') return `#${s.seq} 工具执行 · ${s.tools}`
  if (s.kind === 'rag') {
    return `#${s.seq} RAG 检索${s.ragSourceLabel ? ' · ' + s.ragSourceLabel : ''}`
  }
  if (s.kind === 'memory') return `#${s.seq} 记忆召回`
  return `步骤 ${i + 1}`
}

/**
 * 最终回答预览: 取最后一个 LLM 步骤的本轮输出。
 * 列表接口已返回(截断 500 字符), 故无需展开步骤/打开详情即可看到"这次答了什么";
 * 多轮时前面的轮次输出在「步骤明细」里逐轮可见。
 */
function finalOutput(log) {
  const steps = (log && log.steps) || []
  for (let i = steps.length - 1; i >= 0; i--) {
    if (steps[i].kind === 'llm' && steps[i].outputPreview) return steps[i].outputPreview
  }
  return ''
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
  // 记忆召回此前完全没有留痕, 现在与 RAG 并列可见 —— 两者是同一层"背景信息"的两个来源
  if (s.kind === 'memory') return '🧠 记忆'
  return s.kind === 'tool' ? '🔧' : '🤖'
}
function stepClass(kind) {
  if (kind === 'rag') return 'step-rag'
  if (kind === 'memory') return 'step-memory'
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
    // 只有"全部用户"需要带参数: 后端仅在 userId=all 时查全部, 其余取值(含不传)一律查自己
    if (authStore.isAdmin && filterUserId.value === 'all') params.userId = filterUserId.value
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
.llm-log-header h2 { margin: 0 0 4px; font-size: 20px; color: var(--dcdr-text-1); }
.subtitle { margin: 0; font-size: 13px; color: var(--dcdr-text-3); }

.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.stat-block { margin-left: auto; display: flex; gap: 14px; font-size: 12px; color: var(--dcdr-text-3); }

.log-list { display: flex; flex-direction: column; gap: 10px; min-height: 200px; }
.log-card { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 12px 14px; transition: box-shadow .2s; }
.log-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.log-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.log-turn { font-size: 12px; font-weight: 600; color: var(--dcdr-accent); font-family: monospace; }
.log-user { font-size: 11px; color: var(--dcdr-text-3); font-family: monospace; }
.log-llm, .log-tool { font-size: 12px; color: var(--dcdr-text-2); }
.log-tokens { font-size: 11px; color: var(--dcdr-text-3); }
.log-time { font-size: 11px; color: var(--dcdr-text-3); margin-left: auto; }
.log-message { font-size: 13px; color: var(--dcdr-text-1); line-height: 1.6; word-break: break-word; }
.log-message .rl, .log-chain .rl { color: var(--dcdr-text-3); font-size: 12px; }
.log-chain { font-size: 12px; color: var(--dcdr-text-2); margin-top: 6px; line-height: 1.6; word-break: break-word; }
.log-chain .log-dur { color: var(--dcdr-text-3); margin-left: 6px; }
/* 模型输出预览: 默认最多 4 行, 超出省略(正文见详情抽屉), 避免长回答撑爆卡片 */
.log-output { font-size: 13px; color: var(--dcdr-text-1); margin-top: 6px; line-height: 1.6; word-break: break-word; white-space: pre-wrap; display: -webkit-box; -webkit-line-clamp: 4; line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; }
.log-output .rl { color: var(--dcdr-text-3); font-size: 12px; }

.log-steps-wrap { margin-top: 8px; }
.steps-toggle { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--dcdr-accent); background: var(--dcdr-bg-subtle); border: none; border-radius: 6px; padding: 4px 10px; cursor: pointer; }
.steps-toggle .caret { font-size: 10px; }
.steps-list { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.step-item { border: 1px solid var(--dcdr-border); border-radius: 6px; background: var(--dcdr-bg-subtle); padding: 6px 10px; }
.step-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.step-badge { font-size: 12px; font-weight: 600; border-radius: 6px; padding: 2px 8px; }
.step-llm { color: var(--dcdr-accent); background: var(--dcdr-bg-subtle); }
.step-tool { color: #059669; background: var(--dcdr-bg-subtle); }
.step-rag { color: #b45309; background: var(--dcdr-bg-subtle); }
/* 检索来源标签: 与 step-memory 同色系(都属"背景信息"这一类), 但要能一眼分出来 */
.step-src { font-size: 11px; color: #7c3aed; background: var(--dcdr-accent-wash); border-radius: 4px; padding: 0 5px; }
.step-memory { color: #7c3aed; background: var(--dcdr-accent-wash); }
.step-model, .step-purpose { font-size: 11px; color: var(--dcdr-text-3); font-family: monospace; }
.step-purpose { color: var(--dcdr-text-2); }
.step-tokens, .step-cost { font-size: 11px; color: var(--dcdr-text-3); }
.step-interrupted { font-size: 11px; color: var(--dcdr-warning); }
/* 步骤内本轮输出预览: 最多 3 行, 多轮时可逐轮对照 */
.step-output { font-size: 12px; color: var(--dcdr-text-2); margin-top: 4px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; display: -webkit-box; -webkit-line-clamp: 3; line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }

.empty-tip { text-align: center; color: var(--dcdr-text-3); padding: 60px 20px; font-size: 13px; line-height: 1.8; }
.pager { margin-top: 20px; display: flex; justify-content: center; }

/* ===== 详情抽屉 ===== */
/* 详情内容容器: 给 v-loading 遮罩提供最小高度, 避免加载中抽屉塌陷 */
.d-wrap { min-height: 240px; }
.card-foot { margin-top: 8px; }
.detail-toggle { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--dcdr-accent); background: var(--dcdr-bg-subtle); border: none; border-radius: 6px; padding: 4px 10px; cursor: pointer; }
.detail-toggle:hover { background: var(--dcdr-bg-subtle); }
.d-section { margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid var(--dcdr-border); }
.d-section:last-child { border-bottom: none; }
.d-title { font-weight: 600; font-size: 13px; color: var(--dcdr-text-1); margin-bottom: 8px; }
.d-grid { display: grid; grid-template-columns: 110px 1fr; gap: 6px 10px; font-size: 12px; align-items: baseline; }
.d-k { color: var(--dcdr-text-3); }
.d-hint { color: var(--dcdr-text-3); font-weight: 400; margin-left: 2px; }
.d-v { color: var(--dcdr-text-1); word-break: break-all; }
.d-v.mono { font-family: monospace; }
.d-block { margin-top: 10px; }
.d-pre { margin: 4px 0 0; background: var(--dcdr-bg-subtle); border-radius: 6px; padding: 8px 10px; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; max-height: 220px; overflow-y: auto; }
.d-raw { margin-top: 10px; }
.d-raw summary { font-size: 12px; color: var(--dcdr-accent); cursor: pointer; user-select: none; }

/* 完整消息列表 */
.d-msg { margin-top: 8px; border: 1px solid var(--dcdr-border); border-radius: 6px; padding: 6px 10px; background: var(--dcdr-bg-subtle); }
.d-msg-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.d-msg-role { font-size: 11px; font-weight: 600; border-radius: 4px; padding: 1px 6px; }
.d-msg-role.role-system { color: #b45309; background: var(--dcdr-bg-subtle); }
.d-msg-role.role-user { color: #059669; background: var(--dcdr-bg-subtle); }
.d-msg-role.role-assistant { color: var(--dcdr-accent); background: var(--dcdr-bg-subtle); }
.d-msg-role.role-tool { color: #9333ea; background: var(--dcdr-accent-wash); }
.d-msg-name { font-size: 11px; color: var(--dcdr-text-3); font-family: monospace; }
.d-msg .d-pre { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); max-height: 260px; }
</style>