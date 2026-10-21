<template>
  <div class="llm-log-mode">
    <!-- 页头: 与外层 hub 页签呼应, 不重复"运维台"字样, 只说明这一页看什么 -->
    <div class="log-toolbar">
      <div class="tb-title">
        调用链
        <span class="tb-sub">每次请求的完整链路 · 路由判断 / 生成轮 / 工具步骤 / token / 耗时</span>
      </div>
      <div class="tb-actions">
        <el-select v-if="authStore.isAdmin" v-model="filterUserId" size="small" style="width:132px" @change="loadLogs">
          <!-- 取值必须非空: 空串会被 el-select 当作"未选择", 于是默认态下输入框什么都不显示,
               用户看不出当前查的是谁(而"全部用户"取值是 all, 标签能正常渲染 —— 两个选项表现不一致)。
               默认态本身就是"仅自己", 所以它该是一个真实取值而不是"没有值" -->
          <el-option label="仅自己" value="self" />
          <el-option label="全部用户" value="all" />
        </el-select>
        <el-button size="small" @click="loadLogs" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- 指标条: 全部由当前页样本算出, 标签上写清"本页", 不冒充全量 -->
    <div class="metric-row" v-if="logs.length">
      <div class="metric">
        <span class="m-label">总记录</span>
        <span class="m-value">{{ total }}</span>
      </div>
      <div class="metric">
        <span class="m-label">本页平均轮次</span>
        <span class="m-value">{{ avgLlmCalls }}</span>
      </div>
      <div class="metric">
        <span class="m-label">本页平均耗时</span>
        <span class="m-value">{{ avgDuration }}</span>
      </div>
      <div class="metric">
        <span class="m-label">本页工具步</span>
        <span class="m-value">{{ totalToolSteps }}</span>
      </div>
      <div class="metric">
        <span class="m-label">本页异常</span>
        <span class="m-value" :class="{ bad: abnormalCount > 0 }">{{ abnormalCount }}</span>
      </div>
    </div>

    <!-- 行式列表: 一行一条调用链, 点行在原地展开步骤明细(不跳页、不弹窗) -->
    <div class="log-table" v-loading="loading">
      <div class="lt-head">
        <span>时间</span>
        <span>追踪</span>
        <span>用户消息</span>
        <span class="ta-r">轮次</span>
        <span class="ta-r">工具</span>
        <span class="ta-r">Token ↑ / ↓</span>
        <span class="ta-r">耗时</span>
        <span class="ta-r">状态</span>
      </div>

      <template v-for="log in logs" :key="log.id">
        <div class="lt-row" :class="{ open: expanded.has(log.id) }" @click="toggle(log.id)">
          <span class="lt-time">{{ formatTime(log.createdAt) }}</span>
          <span class="lt-turn">
            <svg class="lt-caret" :class="{ open: expanded.has(log.id) }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg>
            #{{ log.turnId }}
          </span>
          <span class="lt-msg" :title="log.userMessage">{{ log.userMessage || '—' }}</span>
          <span class="lt-num">{{ log.llmCalls || 0 }}</span>
          <span class="lt-num">{{ log.toolSteps || 0 }}</span>
          <span class="lt-num">
            {{ fmtTokens(log.totalInTokens) }}<i class="lt-sep">/</i>{{ fmtTokens(log.totalOutTokens) }}
          </span>
          <span class="lt-num">{{ fmtDur(log.totalDurationMs) || '—' }}</span>
          <span class="lt-state" :class="endClass(log.endSignal)">{{ endLabel(log.endSignal) }}</span>
        </div>

        <div v-if="expanded.has(log.id)" class="lt-detail">
          <div class="ld-row" v-if="log.chain">
            <span class="ld-k">链路</span>
            <code class="ld-chain">{{ log.chain }}</code>
          </div>
          <div class="ld-row" v-if="finalOutput(log)">
            <span class="ld-k">模型输出</span>
            <p class="ld-out">{{ finalOutput(log) }}</p>
          </div>

          <div class="ld-steps" v-if="log.steps && log.steps.length">
            <div v-for="(s, i) in log.steps" :key="i" class="ld-step">
              <span class="step-badge" :class="stepClass(s.kind)">{{ stepLabel(s) }}</span>
              <span class="step-model" v-if="s.model">{{ s.model }}</span>
              <span class="step-purpose" v-if="s.purpose">{{ s.purpose }}</span>
              <span class="step-src" v-if="s.ragSource">{{ s.ragSourceLabel || s.ragSource }}</span>
              <span class="step-meta" v-if="s.query" :title="s.query">{{ s.query }}</span>
              <span class="step-meta" v-if="s.hits != null">命中 {{ s.hits }}</span>
              <span class="step-meta" v-if="s.kbCount != null">{{ s.kbCount }} 库</span>
              <span class="step-meta" v-if="s.outputChars">输出 {{ fmtTokens(s.outputChars) }} 字</span>
              <span class="step-cost" v-if="s.costMs != null">{{ fmtDur(s.costMs) }}</span>
              <span class="step-interrupted" v-if="s.interrupted">已中断</span>
            </div>
          </div>

          <div class="ld-foot">
            <span class="ld-user" v-if="log.userId && authStore.isAdmin">用户 {{ shortId(log.userId) }}</span>
            <button v-if="authStore.isAdmin" class="ld-detail-btn" @click.stop="openDetail(log)">查看完整详情</button>
          </div>
        </div>
      </template>

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

    <!-- 调用链详情抽屉: 一次请求的完整时间轴 —— 头部粘住关键指标, 主体按步骤顺序排列 -->
    <el-drawer v-model="detailVisible" size="680px" :with-header="false" append-to-body class="chain-drawer">
      <div v-loading="detailLoading" class="cd-wrap">
        <template v-if="detailLog">
          <!-- 粘性头部: 往下滚看细节时, 始终知道这是哪一次请求、结果如何 -->
          <header class="cd-head">
            <div class="cd-head-top">
              <span class="cd-title">调用链详情</span>
              <span class="cd-turn">#{{ detailLog.turnId }}</span>
              <span class="cd-state" :class="endClass(detailLog.endSignal)">{{ endLabel(detailLog.endSignal) }}</span>
              <button class="cd-close" @click="detailVisible = false" title="关闭">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>
              </button>
            </div>
            <div class="cd-metrics">
              <span class="cd-metric"><b>{{ detailLog.llmCalls || 0 }}</b> 轮 LLM</span>
              <span class="cd-metric"><b>{{ detailLog.toolSteps || 0 }}</b> 工具步</span>
              <span class="cd-metric"><b>{{ fmtTokens(detailLog.totalInTokens) }}</b>↑ / <b>{{ fmtTokens(detailLog.totalOutTokens) }}</b>↓</span>
              <span class="cd-metric"><b>{{ fmtDur(detailLog.totalDurationMs) || '—' }}</b></span>
              <span class="cd-time">{{ formatTime(detailLog.createdAt) }}</span>
            </div>
            <div class="cd-msg">{{ detailLog.userMessage || '(空)' }}</div>
            <div class="cd-sub">
              <span v-if="detailLog.sessionId">会话 <code>{{ detailLog.sessionId }}</code></span>
              <span v-if="detailLog.userId && authStore.isAdmin">用户 <code>{{ detailLog.userId }}</code></span>
              <span v-if="detailLog.chain">链路 <code>{{ detailLog.chain }}</code></span>
            </div>
          </header>

          <!-- 时间轴: 每一步一个节点, 左侧竖线串起执行顺序 -->
          <div class="cd-steps">
            <div v-for="(s, i) in detailLog.steps || []" :key="i" class="cd-step">
              <div class="cd-rail">
                <span class="cd-dot" :class="stepClass(s.kind)"></span>
              </div>
              <div class="cd-card">
                <div class="cd-card-head">
                  <span class="step-badge" :class="stepClass(s.kind)">{{ stepLabel(s) }}</span>
                  <span class="cd-seq">#{{ s.seq ?? i }}</span>
                  <span class="cd-name">{{ s.purpose || s.tools || (s.kind === 'rag' ? '检索' : '') }}</span>
                  <span class="cd-cost">{{ fmtDur(s.costMs) }}</span>
                </div>

                <div class="cd-fields">
                  <template v-for="(e, ei) in stepEntries(s)" :key="ei">
                    <span class="cf"><i>{{ e.label }}</i><b :class="{ mono: e.mono }">{{ e.value }}</b></span>
                  </template>
                </div>

                <div v-for="(c, ci) in s.calls || []" :key="'c' + ci" class="cd-tool">
                  <div class="cd-tool-head">工具调用 · {{ c.name }}</div>
                  <pre v-if="c.args" class="cd-pre">{{ c.args }}</pre>
                  <pre v-if="c.resultPreview" class="cd-pre">{{ c.resultPreview }}</pre>
                </div>

                <div v-if="s.outputPreview" class="cd-out">
                  <div class="cd-out-head">
                    本轮模型输出
                    <span v-if="s.outputChars" class="cd-hint">{{ fmtTokens(s.outputChars) }} 字</span>
                    <span v-if="s.outputChars > 500" class="cd-hint">仅前 500 字，完整正文见该会话消息</span>
                  </div>
                  <pre class="cd-pre">{{ s.outputPreview }}</pre>
                </div>

                <div class="cd-raws">
                  <details v-if="s.systemPrompt" class="cd-raw">
                    <summary>System Prompt</summary>
                    <pre class="cd-pre">{{ s.systemPrompt }}</pre>
                  </details>
                  <details v-if="s.wireRequest" class="cd-raw">
                    <summary>实际发给上游的请求体</summary>
                    <pre class="cd-pre">{{ prettyJson(s.wireRequest) }}</pre>
                  </details>
                  <details v-if="s.messages && s.messages.length" class="cd-raw">
                    <summary>完整消息列表 · {{ s.messages.length }} 条</summary>
                    <div v-for="(mm, mi) in s.messages" :key="mi" class="cd-msg-item">
                      <div class="cd-msg-head">
                        <span class="cd-role" :class="'role-' + mm.role">{{ mm.role }}</span>
                        <span v-if="mm.name" class="cd-msg-name">{{ mm.name }}</span>
                        <span v-if="mm.toolCallId" class="cd-msg-name">tool_call_id: {{ mm.toolCallId }}</span>
                      </div>
                      <pre class="cd-pre">{{ mm.content || '(空)' }}</pre>
                      <pre v-if="mm.toolCalls" class="cd-pre">{{ mm.toolCalls }}</pre>
                    </div>
                  </details>
                  <details class="cd-raw">
                    <summary>原始 JSON</summary>
                    <pre class="cd-pre">{{ JSON.stringify(s, null, 2) }}</pre>
                  </details>
                </div>
              </div>
            </div>
            <div v-if="!(detailLog.steps || []).length" class="cd-empty">这次请求没有记录步骤明细</div>
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

/** 当前页平均总耗时(样本口径, 与指标条标签一致) */
const avgDuration = computed(() => {
  const withDur = logs.value.filter((l) => l.totalDurationMs != null)
  if (!withDur.length) return '—'
  const sum = withDur.reduce((a, l) => a + Number(l.totalDurationMs || 0), 0)
  return fmtDur(Math.round(sum / withDur.length))
})

const totalToolSteps = computed(() =>
  logs.value.reduce((a, l) => a + Number(l.toolSteps || 0), 0)
)

/** 非正常结束(错误/取消)的条数 */
const abnormalCount = computed(() =>
  logs.value.filter((l) => l.endSignal && l.endSignal !== 'ON_COMPLETE').length
)

function toggle(id) {
  const next = new Set(expanded.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expanded.value = next
}

function endLabel(s) {
  return ({ ON_COMPLETE: '完成', ON_ERROR: '错误', ON_CANCEL: '取消' })[s] || s || '-'
}
function endClass(s) {
  if (s === 'ON_ERROR') return 'is-err'
  if (s === 'ON_CANCEL') return 'is-warn'
  return 'is-ok'
}
/** 步骤类型短标签(纯文字, 不用 emoji: 等宽字体下 emoji 宽度不稳, 表格会抖) */
function stepLabel(s) {
  if (s.kind === 'rag') return 'RAG'
  if (s.kind === 'memory') return '记忆'
  if (s.kind === 'route') return '路由'
  return s.kind === 'tool' ? '工具' : 'LLM'
}
function stepClass(kind) {
  if (kind === 'rag') return 'step-rag'
  if (kind === 'memory') return 'step-memory'
  if (kind === 'route') return 'step-route'
  return kind === 'tool' ? 'step-tool' : 'step-llm'
}
function fmtTokens(n) {
  if (n == null) return '-'
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
  try {
    const d = new Date(t)
    const p = (n) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
  } catch { return t }
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
.llm-log-mode { flex: 1; overflow-y: auto; padding: 18px 24px 24px; }

/* ===== 页头 ===== */
.log-toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 14px; }
.tb-title { display: flex; align-items: baseline; gap: 10px; font-size: 15px; font-weight: 600; color: var(--dcdr-text-1); }
.tb-sub { font-size: 11.5px; font-weight: 400; color: var(--dcdr-text-3); }
.tb-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }

/* ===== 指标条 ===== */
.metric-row { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
.metric {
  display: flex; flex-direction: column-reverse; gap: 6px;
  padding: 11px 13px; background: var(--dcdr-surface-faint);
  border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner);
}
.m-label { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 1px; color: var(--dcdr-text-3); }
.m-value { font-family: var(--dcdr-font-mono); font-size: 21px; font-weight: 600; color: var(--dcdr-text-1); line-height: 1.1; }
.m-value.bad { color: var(--dcdr-danger); }

/* ===== 行式列表 ===== */
.log-table {
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-inner); overflow: hidden; min-height: 180px;
}
.lt-head,
.lt-row {
  display: grid;
  grid-template-columns: 116px 104px minmax(0, 1fr) 48px 48px 116px 62px 62px;
  gap: 12px; align-items: center; padding: 0 14px;
}
.lt-head {
  height: 34px; font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.6px;
  color: var(--dcdr-text-3);
  border-bottom: 1px solid var(--dcdr-border); background: var(--dcdr-bg-subtle);
}
.lt-row {
  height: 40px; font-size: 12.5px; cursor: pointer;
  border-bottom: 1px solid var(--dcdr-border);
  transition: background-color 0.14s var(--dcdr-ease);
}
.lt-row:hover { background: var(--dcdr-hover); }
/* 展开态只用中性提亮 + 左侧强调线: 整行涂强调色会把下面紧邻的展开区压暗, 层级反而乱 */
.lt-row.open {
  background: var(--dcdr-hover);
  border-bottom-color: transparent;
  box-shadow: inset 2px 0 0 var(--dcdr-accent-line);
}
.lt-time { font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-text-3); }
.lt-turn { display: flex; align-items: center; gap: 4px; font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-accent-soft); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lt-caret { width: 10px; height: 10px; flex: none; color: var(--dcdr-text-3); transition: transform 0.16s var(--dcdr-ease); }
.lt-caret.open { transform: rotate(90deg); color: var(--dcdr-accent-soft); }
.lt-msg { color: var(--dcdr-text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lt-num { font-family: var(--dcdr-font-mono); font-size: 11px; color: var(--dcdr-text-2); text-align: right; white-space: nowrap; }
.lt-sep { font-style: normal; color: var(--dcdr-text-3); margin: 0 2px; }
.lt-state { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px; text-align: right; }
.lt-state.is-ok { color: var(--dcdr-success); }
.lt-state.is-warn { color: var(--dcdr-warning); }
.lt-state.is-err { color: var(--dcdr-danger); }

/* ===== 展开区 ===== */
.lt-detail {
  padding: 12px 14px 14px 14px; border-bottom: 1px solid var(--dcdr-border);
  background: var(--dcdr-surface); display: flex; flex-direction: column; gap: 9px;
}
.ld-row { display: flex; gap: 10px; align-items: baseline; }
.ld-k { flex: none; width: 56px; font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.5px; color: var(--dcdr-text-3); }
.ld-chain { font-family: var(--dcdr-font-mono); font-size: 11px; color: var(--dcdr-text-2); }
.ld-out {
  flex: 1; min-width: 0; font-size: 12.5px; color: var(--dcdr-text-1); line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 3; line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.ld-steps { display: flex; flex-direction: column; gap: 5px; margin-top: 2px; }
.ld-step { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 5px 9px; background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); border-radius: 6px; }
.step-badge { font-family: var(--dcdr-font-mono); font-size: 9.5px; letter-spacing: 0.4px; border-radius: 4px; padding: 1px 6px; }
.step-llm { color: var(--dcdr-accent-soft); background: var(--dcdr-accent-wash); }
.step-route { color: var(--dcdr-text-2); background: var(--dcdr-hover); }
.step-tool { color: var(--dcdr-success); background: color-mix(in srgb, var(--dcdr-success) 14%, transparent); }
.step-rag { color: var(--dcdr-warning); background: color-mix(in srgb, var(--dcdr-warning) 14%, transparent); }
.step-memory { color: var(--dcdr-accent-2); background: color-mix(in srgb, var(--dcdr-accent-2) 14%, transparent); }
.step-src { font-size: 10.5px; color: var(--dcdr-accent-2); background: color-mix(in srgb, var(--dcdr-accent-2) 12%, transparent); border-radius: 4px; padding: 0 5px; }
.step-model { font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-text-2); }
.step-purpose { font-size: 11.5px; color: var(--dcdr-text-2); }
.step-meta, .step-cost { font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-3); }
.step-interrupted { font-size: 10.5px; color: var(--dcdr-warning); }
.ld-foot { display: flex; align-items: center; gap: 12px; margin-top: 2px; }
.ld-user { font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-3); }
.ld-detail-btn {
  margin-left: auto; height: 26px; padding: 0 12px; border-radius: 6px; cursor: pointer;
  font-size: 11.5px; color: var(--dcdr-accent-soft);
  background: var(--dcdr-accent-wash); border: 1px solid var(--dcdr-accent-line);
  transition: border-color 0.15s var(--dcdr-ease);
}
.ld-detail-btn:hover { border-color: var(--dcdr-accent); }

.empty-tip { text-align: center; color: var(--dcdr-text-3); padding: 60px 20px; font-size: 12.5px; line-height: 1.9; }
.pager { margin-top: 16px; display: flex; justify-content: center; }

/* ===== 详情抽屉: 一次请求的时间轴 ===== */
/* 抽屉 body 的 padding/overflow 在 styles/element.css 里处理:
   el-drawer 会 teleport 到 body, 组件内的 :deep() 拿不到它自己的 body 元素 */

.cd-wrap { flex: 1; min-height: 0; height: 100%; display: flex; flex-direction: column; }

/* 粘性头部: 一行身份 + 一行指标 + 用户消息 */
.cd-head {
  flex: none;
  padding: 16px 20px 14px;
  background: var(--dcdr-surface);
  border-bottom: 1px solid var(--dcdr-border);
}
.cd-head-top { display: flex; align-items: center; gap: 10px; }
.cd-title { font-size: 14.5px; font-weight: 600; color: var(--dcdr-text-1); }
.cd-turn { font-family: var(--dcdr-font-mono); font-size: 11px; color: var(--dcdr-accent-soft); }
.cd-state { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px; }
.cd-state.is-ok { color: var(--dcdr-success); }
.cd-state.is-warn { color: var(--dcdr-warning); }
.cd-state.is-err { color: var(--dcdr-danger); }
.cd-close {
  margin-left: auto; width: 26px; height: 26px; flex: none;
  display: inline-flex; align-items: center; justify-content: center; padding: 0;
  border: 1px solid transparent; border-radius: 6px; background: transparent;
  color: var(--dcdr-text-3); cursor: pointer; transition: all 0.16s var(--dcdr-ease);
}
.cd-close svg { width: 13px; height: 13px; }
.cd-close:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border); background: var(--dcdr-hover); }

.cd-metrics { display: flex; align-items: baseline; gap: 14px; margin-top: 10px; flex-wrap: wrap; }
.cd-metric { font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-text-3); }
.cd-metric b { font-size: 13px; font-weight: 600; color: var(--dcdr-text-1); margin-right: 2px; }
.cd-time { margin-left: auto; font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-text-3); }

.cd-msg {
  margin-top: 10px; font-size: 12.5px; line-height: 1.65; color: var(--dcdr-text-1);
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: 7px; padding: 8px 11px;
  display: -webkit-box; -webkit-line-clamp: 3; line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.cd-sub { display: flex; align-items: center; gap: 16px; margin-top: 9px; flex-wrap: wrap; font-size: 10.5px; color: var(--dcdr-text-3); }
.cd-sub code { font-family: var(--dcdr-font-mono); color: var(--dcdr-text-2); margin-left: 4px; }

/* 时间轴主体 */
.cd-steps { flex: 1; min-height: 0; overflow-y: auto; padding: 16px 20px 24px; }
.cd-step { display: flex; gap: 12px; }
.cd-rail { position: relative; flex: none; width: 10px; display: flex; justify-content: center; }
/* 竖线连到下一个节点, 最后一个节点不画 */
.cd-rail::before {
  content: ''; position: absolute; top: 16px; bottom: -12px; left: 50%;
  width: 1px; background: var(--dcdr-border); transform: translateX(-50%);
}
.cd-step:last-child .cd-rail::before { display: none; }
.cd-dot {
  position: relative; z-index: 1; width: 8px; height: 8px; margin-top: 12px;
  border-radius: 50%; background: var(--dcdr-text-3);
  box-shadow: 0 0 0 3px var(--dcdr-surface);
}
.cd-dot.step-llm { background: var(--dcdr-accent-soft); }
.cd-dot.step-tool { background: var(--dcdr-success); }
.cd-dot.step-rag { background: var(--dcdr-warning); }
.cd-dot.step-memory { background: var(--dcdr-accent-2); }
.cd-dot.step-route { background: var(--dcdr-text-3); }

.cd-card {
  flex: 1; min-width: 0; margin-bottom: 12px;
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: 9px; padding: 11px 13px;
  transition: border-color 0.16s var(--dcdr-ease);
}
.cd-card:hover { border-color: var(--dcdr-border-strong); }
.cd-card-head { display: flex; align-items: center; gap: 8px; }
.cd-seq { font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-3); }
.cd-name { font-size: 12.5px; color: var(--dcdr-text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cd-cost { margin-left: auto; font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-text-2); flex: none; }

/* 字段: 行内紧凑排布, 一个字段一处, 不铺两列大网格 */
.cd-fields { display: flex; flex-wrap: wrap; gap: 6px 16px; margin-top: 9px; }
.cf { display: inline-flex; align-items: baseline; gap: 5px; }
.cf i { font-style: normal; font-family: var(--dcdr-font-mono); font-size: 9.5px; letter-spacing: 0.4px; color: var(--dcdr-text-3); }
.cf b { font-weight: 500; font-size: 11.5px; color: var(--dcdr-text-1); }
.cf b.mono { font-family: var(--dcdr-font-mono); }

.cd-tool { margin-top: 10px; }
.cd-tool-head { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px; color: var(--dcdr-text-3); margin-bottom: 4px; }

.cd-out { margin-top: 10px; }
.cd-out-head { display: flex; align-items: baseline; gap: 8px; font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px; color: var(--dcdr-text-3); margin-bottom: 4px; }
.cd-hint { font-family: var(--dcdr-font-sans); font-size: 10px; letter-spacing: 0; }

.cd-pre {
  margin: 0 0 4px; padding: 8px 10px;
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 6px;
  font-family: var(--dcdr-font-mono); font-size: 11px; line-height: 1.65;
  color: var(--dcdr-text-2); white-space: pre-wrap; word-break: break-word;
  max-height: 220px; overflow-y: auto;
}

/* 折叠区: 统一去掉浏览器默认三角, 用等宽标签 + 前后箭头 */
.cd-raws { display: flex; flex-direction: column; gap: 2px; margin-top: 10px; }
.cd-raw { border-top: 1px dashed var(--dcdr-border); padding-top: 6px; }
.cd-raw > summary {
  display: flex; align-items: center; gap: 6px;
  font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px;
  color: var(--dcdr-text-3); cursor: pointer; user-select: none; list-style: none;
  padding: 3px 0; transition: color 0.16s var(--dcdr-ease);
}
.cd-raw > summary::-webkit-details-marker { display: none; }
.cd-raw > summary::before {
  content: '▸'; font-size: 9px; color: var(--dcdr-text-3);
  transition: transform 0.16s var(--dcdr-ease);
}
.cd-raw[open] > summary::before { transform: rotate(90deg); }
.cd-raw > summary:hover { color: var(--dcdr-text-1); }
.cd-raw > summary:hover::before { color: var(--dcdr-accent-soft); }
.cd-raw .cd-pre { margin-top: 4px; }

.cd-msg-item { margin-top: 6px; }
.cd-msg-head { display: flex; align-items: center; gap: 8px; margin-bottom: 3px; }
.cd-role { font-family: var(--dcdr-font-mono); font-size: 9px; letter-spacing: 0.4px; border-radius: 3px; padding: 1px 5px; }
.cd-role.role-system { color: var(--dcdr-warning); background: color-mix(in srgb, var(--dcdr-warning) 14%, transparent); }
.cd-role.role-user { color: var(--dcdr-success); background: color-mix(in srgb, var(--dcdr-success) 14%, transparent); }
.cd-role.role-assistant { color: var(--dcdr-accent-soft); background: var(--dcdr-accent-wash); }
.cd-role.role-tool { color: var(--dcdr-accent-2); background: color-mix(in srgb, var(--dcdr-accent-2) 14%, transparent); }
.cd-msg-name { font-family: var(--dcdr-font-mono); font-size: 9.5px; color: var(--dcdr-text-3); }

.cd-empty { padding: 40px 0; text-align: center; font-size: 12.5px; color: var(--dcdr-text-3); }

</style>
