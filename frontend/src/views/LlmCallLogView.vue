<template>
  <div class="llm-log-mode">
    <!-- 页头: 与外层 hub 页签呼应, 不重复"运维台"字样, 只说明这一页看什么 -->
    <div class="log-toolbar">
      <div class="tb-title">
        调用链
        <span class="tb-sub">每次请求的完整链路 · 路由判断 / 生成轮 / 工具步骤 / token / 耗时</span>
      </div>
      <div class="tb-actions">
        <el-select v-if="authStore.isAdmin" v-model="filterUserId" size="small" style="width:132px" @change="onFilterChange">
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
          <!-- title 挂上失败原因: 列表里不用展开就能看到"这次为什么红" -->
          <span class="lt-state" :class="endClass(log.endSignal)"
                :title="log.errorMessage ? `${log.failedStep || ''} ${log.errorMessage}`.trim() : ''">
            {{ endLabel(log.endSignal) }}
          </span>
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

    <!-- 分页: 只要有一条数据就常显(不按"是否超过一页"隐藏) —— 否则数据不足 20 条时
         用户完全看不到分页入口, 会以为这一页不支持翻页/没法改每页条数 -->
    <div class="pager" v-if="total > 0">
      <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="total"
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

            <!-- 耗时构成: 先回答"这几秒花在模型、工具还是检索上", 再往下看逐步明细 -->
            <div v-if="phaseStats.length" class="cd-phase">
              <div class="cd-phase-bar">
                <span v-for="p in phaseStats" :key="p.kind" class="cd-phase-seg" :class="'ph-' + p.kind"
                      :style="{ width: p.pct + '%' }" :title="`${p.label} ${fmtDur(p.ms)}`"></span>
              </div>
              <div class="cd-phase-legend">
                <span v-for="p in phaseStats" :key="p.kind" class="cd-phase-item">
                  <i class="cd-phase-dot" :class="'ph-' + p.kind"></i>{{ p.label }}
                  <b>{{ fmtDur(p.ms) }}</b><em>{{ p.pct }}%</em>
                </span>
              </div>
            </div>

            <!-- 失败原因: 否则"错误"两个字之外什么都没有, 得回服务端翻日志 -->
            <div v-if="detailLog.errorMessage || detailLog.failedStep" class="cd-fail">
              <span class="cd-fail-tag">失败</span>
              <span v-if="detailLog.failedStep" class="cd-fail-step">{{ detailLog.failedStep }}</span>
              <span class="cd-fail-msg">{{ detailLog.errorMessage || '未记录错误信息' }}</span>
            </div>

            <div class="cd-msg">{{ detailLog.userMessage || '(空)' }}</div>
            <div class="cd-sub">
              <span v-if="detailLog.sessionId">会话 <code>{{ detailLog.sessionId }}</code></span>
              <span v-if="detailLog.userId && authStore.isAdmin">用户 <code>{{ detailLog.userId }}</code></span>
              <span v-if="detailLog.chain">链路 <code>{{ detailLog.chain }}</code></span>
            </div>
          </header>

          <!-- 时间轴: 按"请求准备 → 各轮生成(含该轮的工具/检索) → 失败节点"分段, 段头可折叠 -->
          <div class="cd-steps">
            <div v-for="g in stepGroups" :key="g.key" class="cd-group">
              <button class="cd-group-head" @click="toggleGroup(g.key)">
                <span class="cd-group-caret" :class="{ open: isGroupOpen(g.key) }">▸</span>
                <span class="cd-group-title">{{ g.title }}</span>
                <span v-if="g.tag" class="cd-group-tag">{{ g.tag }}</span>
                <span class="cd-group-meta">
                  <span v-if="g.inTokens || g.outTokens" class="cd-group-tokens">
                    {{ fmtTokens(g.inTokens) }}↑ / {{ fmtTokens(g.outTokens) }}↓
                  </span>
                  <span class="cd-group-cost">{{ fmtDur(g.costMs) || '—' }}</span>
                </span>
              </button>

              <div v-show="isGroupOpen(g.key)" class="cd-group-body">
                <div v-for="sv in g.steps" :key="sv.no" class="cd-step">
                  <div class="cd-rail">
                    <span class="cd-dot" :class="stepClass(sv.raw.kind)"></span>
                  </div>
                  <div class="cd-card" :class="{ 'is-failed': sv.raw.kind === 'error' }">
                    <div class="cd-card-head">
                      <span class="step-badge" :class="stepClass(sv.raw.kind)">{{ stepLabel(sv.raw) }}</span>
                      <span class="cd-seq">#{{ sv.no }}</span>
                      <span class="cd-name">{{ sv.title }}</span>
                      <span class="cd-cost">{{ fmtDur(sv.cost) }}</span>
                    </div>

                    <!-- 关键信息做成 chips(模型/token/上下文/命中…), 次要字段收进「其余字段」 -->
                    <div v-if="sv.chips.length" class="cd-chips">
                      <span v-for="(c, ci) in sv.chips" :key="ci" class="cd-chip"
                            :class="[c.tone ? 'is-' + c.tone : '', { mono: c.mono }]">{{ c.text }}</span>
                    </div>

                    <p v-if="sv.quote" class="cd-quote">{{ sv.quote }}</p>
                    <p v-if="sv.error" class="cd-error-msg">{{ sv.error }}</p>

                    <!-- 单步耗时条: 按累计耗时定位, 宽度即该步占整条链路的比例(瀑布) -->
                    <div v-if="sv.cost > 0" class="cd-bar">
                      <span class="cd-bar-fill" :class="stepClass(sv.raw.kind)"
                            :style="{ marginLeft: sv.barLeft + '%', width: sv.barWidth + '%' }"></span>
                    </div>

                    <div v-for="(c, ci) in sv.calls" :key="'c' + ci" class="cd-tool">
                      <div class="cd-tool-head">工具调用 · {{ c.name }}</div>
                      <pre v-if="c.args" class="cd-pre">{{ c.args }}</pre>
                      <pre v-if="c.resultPreview" class="cd-pre">{{ c.resultPreview }}</pre>
                    </div>

                    <div v-if="sv.output" class="cd-out">
                      <div class="cd-out-head">
                        本轮模型输出
                        <span v-if="sv.outputChars" class="cd-hint">{{ fmtTokens(sv.outputChars) }} 字</span>
                        <span v-if="sv.outputChars > 500" class="cd-hint">仅前 500 字，完整正文见该会话消息</span>
                      </div>
                      <pre class="cd-pre">{{ sv.output }}</pre>
                    </div>

                    <!-- 原始数据只留三个入口: 实际请求体已包含在原始 JSON 里, 不再单独占一项 -->
                    <div class="cd-raws">
                      <details v-if="sv.systemPrompt" class="cd-raw">
                        <summary>System Prompt</summary>
                        <pre class="cd-pre">{{ sv.systemPrompt }}</pre>
                      </details>
                      <details v-if="sv.messages.length" class="cd-raw">
                        <summary>消息列表 · {{ sv.messages.length }} 条</summary>
                        <div v-for="(mm, mi) in sv.messages" :key="mi" class="cd-msg-item">
                          <div class="cd-msg-head">
                            <span class="cd-role" :class="'role-' + mm.role">{{ mm.role }}</span>
                            <span v-if="mm.name" class="cd-msg-name">{{ mm.name }}</span>
                            <span v-if="mm.toolCallId" class="cd-msg-name">tool_call_id: {{ mm.toolCallId }}</span>
                          </div>
                          <pre class="cd-pre">{{ mm.content || '(空)' }}</pre>
                          <pre v-if="mm.toolCalls" class="cd-pre">{{ mm.toolCalls }}</pre>
                        </div>
                      </details>
                      <details v-if="sv.others.length" class="cd-raw">
                        <summary>其余字段 · {{ sv.others.length }}</summary>
                        <div class="cd-fields">
                          <span v-for="(e, ei) in sv.others" :key="ei" class="cf">
                            <i>{{ e.label }}</i><b :class="{ mono: e.mono }">{{ e.value }}</b>
                          </span>
                        </div>
                      </details>
                      <details class="cd-raw">
                        <summary>原始 JSON（含实际请求体）</summary>
                        <pre class="cd-pre">{{ JSON.stringify(sv.raw, null, 2) }}</pre>
                      </details>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="!stepGroups.length" class="cd-empty">这次请求没有记录步骤明细</div>
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

/**
 * 筛选条件变化: 必须先回到第 1 页再查。
 * 否则在第 3 页把范围从"全部用户"切回"仅自己"后, 第 3 页很可能已经越界, 表现为"一切换就空白"。
 */
function onFilterChange() {
  page.value = 1
  loadLogs()
}

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
  // 每次打开都回到"全展开": 上一条日志的折叠状态(按分组 key 记)对新日志没有意义
  collapsedGroups.value = new Set()
  try {
    detailLog.value = await getLlmCallLogDetail(log.id)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载调用链详情失败')
  } finally {
    detailLoading.value = false
  }
}

// ===== 详情派生数据: 瀑布位置 / 耗时构成 / 轮次分组 =====

/** 折叠的分组(默认全展开: 一次请求通常只有 2~5 段, 收起来反而要多点几下) */
const collapsedGroups = ref(new Set())
function toggleGroup(key) {
  const next = new Set(collapsedGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedGroups.value = next
}
function isGroupOpen(key) {
  return !collapsedGroups.value.has(key)
}

/**
 * 步骤视图: 给每步补上"整条链路中的位置"。
 * <p>
 * 步骤是<b>串行</b>执行的, 所以用累计 costMs 就能还原时间轴 —— 不需要后端额外记录每步起始时间戳。
 * 分母取「总耗时」与「各步之和」的较大者: 总耗时含网络往返与收尾, 通常更大, 用它才能如实反映占比。
 */
const stepView = computed(() => {
  const steps = (detailLog.value && detailLog.value.steps) || []
  const total = Number(detailLog.value && detailLog.value.totalDurationMs) || 0
  const sum = steps.reduce((a, s) => a + (Number(s.costMs) || 0), 0)
  const denom = Math.max(total, sum) || 1
  let acc = 0
  return steps.map((s, i) => {
    const card = buildStepCard(s, i + 1)
    card.barLeft = +(acc / denom * 100).toFixed(2)
    // 给极短的步骤一个最小可见宽度, 否则 5ms 的步骤在条上就是"不存在"
    card.barWidth = card.cost > 0 ? Math.max(+(card.cost / denom * 100).toFixed(2), 0.8) : 0
    acc += card.cost
    return card
  })
})

const phaseTotalMs = computed(() => {
  const sum = stepView.value.reduce((a, s) => a + s.cost, 0)
  return Math.max(Number(detailLog.value && detailLog.value.totalDurationMs) || 0, sum)
})

/** 耗时构成: 模型 / 工具 / 检索 / 记忆, 差额记为「其他」(网络往返、排队、SSE 收尾) */
const phaseStats = computed(() => {
  const total = phaseTotalMs.value
  if (!total) return []
  const defs = [
    { kind: 'llm', label: '模型' },
    { kind: 'tool', label: '工具' },
    { kind: 'rag', label: '检索' },
    { kind: 'memory', label: '记忆' }
  ]
  const stats = defs
    .map((d) => {
      const ms = stepView.value
        .filter((s) => s.raw.kind === d.kind)
        .reduce((a, s) => a + s.cost, 0)
      return { ...d, ms, pct: +(ms / total * 100).toFixed(1) }
    })
    .filter((p) => p.ms > 0)
  const rest = total - stats.reduce((a, p) => a + p.ms, 0)
  if (rest > 0) {
    stats.push({ kind: 'other', label: '其他', ms: rest, pct: +(rest / total * 100).toFixed(1) })
  }
  return stats
})

/**
 * 轮次分组: 「请求准备 → 路由判断 → 检索与记忆 → 第 1 轮生成(含该轮工具) → 第 2 轮生成 …」
 * <p>
 * 原先所有步骤摊成一条等价直线, 用户看不出"为什么调了两次模型" —— 而这正是 agentic 调用链
 * 最需要解释的事。以 LLM 轮次为骨架分组后, 每段自带小计耗时与 token, 故事线就出来了。
 */
const stepGroups = computed(() => {
  const groups = []
  let pending = []
  let current = null

  const flushPending = () => {
    if (!pending.length) return
    const kinds = new Set(pending.map((s) => s.raw.kind))
    const title = kinds.has('rag') || kinds.has('memory') ? '检索与记忆' : '请求准备'
    groups.push(makeGroup('p' + groups.length, title, pending, false))
    pending = []
  }

  for (const sv of stepView.value) {
    if (sv.raw.kind === 'llm') {
      flushPending()
      const isRound = /^round-\d+$/.test(sv.raw.purpose || '') || sv.raw.purpose === 'chatSync'
      current = makeGroup('l' + sv.no, purposeTitle(sv.raw.purpose), [sv], isRound)
      groups.push(current)
      continue
    }
    // 轮次内的工具/检索归该轮; 第一轮之前(或路由之后)的准备动作先攒着, 合成「检索与记忆」
    if (current && current.round) current.steps.push(sv)
    else pending.push(sv)
  }
  flushPending()

  for (const g of groups) {
    g.costMs = g.steps.reduce((a, s) => a + s.cost, 0)
    const llmSteps = g.steps.filter((s) => s.raw.kind === 'llm')
    g.inTokens = llmSteps.reduce((a, s) => a + (Number(s.raw.inTokens) || 0), 0)
    g.outTokens = llmSteps.reduce((a, s) => a + (Number(s.raw.outTokens) || 0), 0)
    g.tag = `${g.steps.length} 步`
  }
  return groups
})

function makeGroup(key, title, steps, round) {
  return { key, title, steps: [...steps], round, costMs: 0, inTokens: 0, outTokens: 0, tag: '' }
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


/** 步骤字段值 → 可读文本(「其余字段」折叠区共用) */
function fieldValue(k, v, s) {
  if (k === 'costMs') return fmtDur(v) || '-'
  if (k === 'inTokens' || k === 'outTokens') return v == null ? '-' : fmtTokens(v)
  if (k === 'interrupted') return v ? '是' : '否'
  // 中文名取自后端，原值仍留在括号里：它是按日志/库侧聚合时要 grep 的东西，只留中文反而查不下去
  if (k === 'ragSource') return s.ragSourceLabel ? `${s.ragSourceLabel}（${v}）` : v
  if (k === 'outputChars') return fmtTokens(v) + ' 字'
  if (v !== null && typeof v === 'object') return JSON.stringify(v)
  return (v == null || v === '') ? '-' : v
}

/**
 * 每类步骤在卡片头部(chips)已经展示过的字段 —— 剩下的才进「其余字段」。
 * <p>
 * 用白名单而非黑名单: 后端新增字段时默认落进折叠区(仍可见, 但不挤占首屏),
 * 不会因为前端忘了配置就把字段静默丢掉。
 */
const STEP_HEAD_FIELDS = {
  llm: ['kind', 'seq', 'purpose', 'model', 'inTokens', 'outTokens', 'costMs', 'interrupted',
    'messageCount', 'toolSpecCount', 'temperature', 'finishReason',
    'systemPrompt', 'messages', 'wireRequest', 'outputPreview', 'outputChars'],
  tool: ['kind', 'seq', 'tools', 'costMs', 'interrupted', 'calls'],
  rag: ['kind', 'seq', 'query', 'kbCount', 'hits', 'costMs', 'topScore', 'ragSource', 'ragSourceLabel'],
  memory: ['kind', 'seq', 'query', 'hits', 'chars', 'costMs'],
  prompt: ['kind', 'seq', 'systemPromptFingerprint', 'systemPromptChars'],
  citation: ['kind', 'seq', 'citedCount', 'availableCount', 'outOfRange'],
  error: ['kind', 'seq', 'failedStep', 'message']
}

function leftoverFields(s) {
  const consumed = new Set(STEP_HEAD_FIELDS[s.kind] || ['kind', 'seq', 'costMs'])
  const out = []
  for (const k of Object.keys(s)) {
    // 后端为某字段另发了中文名时(约定 key + 'Label'), 由原值那一行合并展示 —— 否则会出现两个「检索来源」
    if (consumed.has(k) || k.endsWith('Label')) continue
    out.push({ label: STEP_LABELS[k] || k, value: fieldValue(k, s[k], s), mono: k === 'model' })
  }
  return out
}

/** purpose → 人话; 轮次标题与失败步骤名共用同一套说法 */
function purposeTitle(purpose) {
  if (!purpose) return '模型调用'
  if (purpose === 'routing') return '路由判断'
  if (purpose === 'chatSync') return '同步生成'
  const m = /^round-(\d+)$/.exec(purpose)
  if (m) return `第 ${m[1]} 轮生成`
  return purpose
}

/**
 * 把一个步骤整理成"卡片模型": 标题 + 关键 chips + 明细块。
 * <p>
 * 原先所有字段平铺成 label:value, 模型/token 与「温度」「注入工具数」同等权重, 得逐行找重点。
 * 这里按 kind 把最该看的几项提到 chips(模型、token、命中、相似度、错误), 其余进折叠区。
 */
function buildStepCard(s, no) {
  const card = {
    raw: s,
    no,
    title: '',
    chips: [],
    quote: '',
    error: '',
    cost: Number(s.costMs) || 0,
    calls: Array.isArray(s.calls) ? s.calls : [],
    output: s.outputPreview || '',
    outputChars: Number(s.outputChars) || 0,
    systemPrompt: s.systemPrompt || '',
    messages: Array.isArray(s.messages) ? s.messages : [],
    others: leftoverFields(s),
    barLeft: 0,
    barWidth: 0
  }
  const markInterrupted = () => { if (s.interrupted) card.chips.push({ text: '已中断', tone: 'warn' }) }

  switch (s.kind) {
    case 'llm':
      card.title = purposeTitle(s.purpose)
      if (s.model) card.chips.push({ text: s.model, mono: true, tone: 'model' })
      if (s.inTokens != null || s.outTokens != null) {
        card.chips.push({ text: `${fmtTokens(s.inTokens)}↑ / ${fmtTokens(s.outTokens)}↓`, tone: 'tokens' })
      }
      if (s.messageCount != null) card.chips.push({ text: `${s.messageCount} 条上下文` })
      if (s.toolSpecCount != null) card.chips.push({ text: `${s.toolSpecCount} 个工具` })
      if (s.temperature != null) card.chips.push({ text: `温度 ${s.temperature}` })
      if (s.finishReason && s.finishReason !== 'stop') {
        card.chips.push({ text: `结束 ${s.finishReason}`, tone: 'warn' })
      }
      markInterrupted()
      break
    case 'tool':
      card.title = s.tools || '工具执行'
      card.chips.push({ text: `${card.calls.length} 次调用` })
      markInterrupted()
      break
    case 'rag':
      card.title = s.ragSourceLabel ? `知识库检索 · ${s.ragSourceLabel}` : '知识库检索'
      card.chips.push({ text: `命中 ${s.hits ?? 0} 条`, tone: (s.hits || 0) > 0 ? '' : 'warn' })
      if (s.kbCount != null) card.chips.push({ text: `${s.kbCount} 个知识库` })
      if (s.topScore != null && Number(s.topScore) > 0) {
        card.chips.push({ text: `最高相似度 ${Number(s.topScore).toFixed(3)}` })
      }
      card.quote = s.query || ''
      break
    case 'memory':
      card.title = '长期记忆召回'
      card.chips.push({ text: `召回 ${s.hits ?? 0} 条` })
      if (s.chars != null) card.chips.push({ text: `注入 ${fmtTokens(s.chars)} 字` })
      card.quote = s.query || ''
      break
    case 'prompt':
      card.title = 'System Prompt 指纹'
      if (s.systemPromptFingerprint) card.chips.push({ text: s.systemPromptFingerprint, mono: true })
      if (s.systemPromptChars != null) card.chips.push({ text: `${fmtTokens(s.systemPromptChars)} 字` })
      break
    case 'citation':
      card.title = '引用忠实度校验'
      card.chips.push({ text: `标注 ${s.citedCount ?? 0} 处` })
      card.chips.push({ text: `可用来源 ${s.availableCount ?? 0} 条` })
      if (Array.isArray(s.outOfRange) && s.outOfRange.length) {
        card.chips.push({ text: `越界编号 ${s.outOfRange.join(', ')}`, tone: 'bad' })
      }
      break
    case 'error':
      card.title = s.failedStep || '请求失败'
      card.error = s.message || '未记录错误信息'
      break
    default:
      card.title = s.kind || '步骤'
  }
  return card
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
/**
 * 步骤类型短标签(纯文字, 不用 emoji: 等宽字体下 emoji 宽度不稳, 表格会抖)。
 * <p>
 * 这里必须逐类列全: 早先只认 rag/memory/route, 于是 prompt(提示词指纹) 与 citation(引用校验)
 * 都被兜底成 "LLM", 时间轴上看着像多调了两次模型 —— 比不显示更误导。
 */
function stepLabel(s) {
  if (s.kind === 'rag') return 'RAG'
  if (s.kind === 'memory') return '记忆'
  if (s.kind === 'route') return '路由'
  if (s.kind === 'prompt') return '提示词'
  if (s.kind === 'citation') return '引用'
  if (s.kind === 'error') return '失败'
  return s.kind === 'tool' ? '工具' : 'LLM'
}
function stepClass(kind) {
  if (kind === 'rag') return 'step-rag'
  if (kind === 'memory') return 'step-memory'
  if (kind === 'route') return 'step-route'
  if (kind === 'prompt') return 'step-prompt'
  if (kind === 'citation') return 'step-citation'
  if (kind === 'error') return 'step-error'
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
/* 根容器改为"页头 + 指标条 + 列表 + 分页"四段竖排, 只有列表滚: 分页器因此始终贴在底部可见,
   不会因为列表有 20 行(或展开了几条)就要一直滚到最下面才找得到 */
.llm-log-mode { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 18px 24px 16px; }

/* ===== 页头 ===== */
.log-toolbar { flex: none; display: flex; align-items: center; gap: 16px; margin-bottom: 14px; }
.tb-title { display: flex; align-items: baseline; gap: 10px; font-size: 15px; font-weight: 600; color: var(--dcdr-text-1); }
.tb-sub { font-size: 11.5px; font-weight: 400; color: var(--dcdr-text-3); }
.tb-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }

/* ===== 指标条 ===== */
.metric-row { flex: none; display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
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
  /* flex:1 + min-height:0: 列表吃掉剩余高度并自己滚(父级是竖排 flex, 少了 min-height:0 会被内容撑开、
     把分页器顶出可视区); overflow-y 交给列表, 表头才能 sticky 在本区域内而不是整页顶部 */
  flex: 1; min-height: 0; overflow-y: auto;
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-inner);
}
.lt-head,
.lt-row {
  display: grid;
  grid-template-columns: 116px 104px minmax(0, 1fr) 48px 48px 116px 62px 62px;
  gap: 12px; align-items: center; padding: 0 14px;
}
.lt-head {
  position: sticky; top: 0; z-index: 1;
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
.step-prompt { color: var(--dcdr-text-2); background: var(--dcdr-hover); }
.step-citation { color: var(--dcdr-accent-2); background: color-mix(in srgb, var(--dcdr-accent-2) 12%, transparent); }
.step-error { color: var(--dcdr-danger); background: color-mix(in srgb, var(--dcdr-danger) 14%, transparent); }
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
.pager { flex: none; margin-top: 12px; display: flex; justify-content: center; }

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

/* ===== 耗时构成条 ===== */
.cd-phase { margin-top: 12px; }
.cd-phase-bar { display: flex; height: 8px; border-radius: 4px; overflow: hidden; background: var(--dcdr-hover); }
.cd-phase-seg { display: block; height: 100%; }
.ph-llm { background: var(--dcdr-accent); }
.ph-tool { background: var(--dcdr-success); }
.ph-rag { background: var(--dcdr-warning); }
.ph-memory { background: var(--dcdr-accent-2); }
.ph-other { background: var(--dcdr-border-strong); }
.cd-phase-legend { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 7px; }
.cd-phase-item { display: inline-flex; align-items: center; gap: 5px; font-size: 10.5px; color: var(--dcdr-text-3); }
.cd-phase-item b { font-family: var(--dcdr-font-mono); font-size: 11px; font-weight: 600; color: var(--dcdr-text-2); }
.cd-phase-item em { font-style: normal; font-family: var(--dcdr-font-mono); font-size: 9.5px; color: var(--dcdr-text-3); }
.cd-phase-dot { flex: none; width: 7px; height: 7px; border-radius: 2px; }

/* ===== 失败横幅 ===== */
.cd-fail {
  display: flex; align-items: baseline; gap: 8px; margin-top: 10px;
  padding: 8px 11px; border-radius: 7px; font-size: 12px; line-height: 1.6;
  color: var(--dcdr-danger); background: color-mix(in srgb, var(--dcdr-danger) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--dcdr-danger) 28%, transparent);
}
.cd-fail-tag {
  flex: none; font-family: var(--dcdr-font-mono); font-size: 9px; letter-spacing: 0.5px;
  padding: 1px 5px; border-radius: 3px; color: #fff; background: var(--dcdr-danger);
}
.cd-fail-step { flex: none; font-weight: 600; }
.cd-fail-msg { color: var(--dcdr-text-1); word-break: break-word; }

/* ===== 轮次分组 ===== */
.cd-group { margin-bottom: 6px; }
.cd-group-head {
  width: 100%; display: flex; align-items: center; gap: 8px;
  padding: 7px 8px 7px 2px; background: transparent; border: none;
  border-bottom: 1px dashed var(--dcdr-border); cursor: pointer;
  font-size: 12px; color: var(--dcdr-text-2); text-align: left;
}
.cd-group-caret { flex: none; font-size: 9px; color: var(--dcdr-text-3); transition: transform 0.16s var(--dcdr-ease); }
.cd-group-caret.open { transform: rotate(90deg); color: var(--dcdr-accent-soft); }
.cd-group-title { flex: none; font-weight: 600; color: var(--dcdr-text-1); }
.cd-group-tag {
  flex: none; font-family: var(--dcdr-font-mono); font-size: 9.5px; color: var(--dcdr-text-3);
  background: var(--dcdr-hover); padding: 1px 6px; border-radius: 4px;
}
.cd-group-meta {
  margin-left: auto; flex: none; display: flex; align-items: center; gap: 10px;
  font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-3);
}
.cd-group-cost { font-size: 11px; color: var(--dcdr-text-2); }
.cd-group-body { padding-top: 10px; }

/* ===== 卡片内的关键字段(chips) ===== */
.cd-chips { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; }
.cd-chip {
  font-size: 10.5px; line-height: 1.75; padding: 0 7px; border-radius: 5px;
  color: var(--dcdr-text-2); background: var(--dcdr-hover); border: 1px solid transparent;
}
.cd-chip.mono { font-family: var(--dcdr-font-mono); }
.cd-chip.is-model { color: var(--dcdr-accent-soft); background: var(--dcdr-accent-wash); border-color: var(--dcdr-accent-line); }
.cd-chip.is-tokens { font-family: var(--dcdr-font-mono); color: var(--dcdr-text-1); background: var(--dcdr-surface); border-color: var(--dcdr-border); }
.cd-chip.is-warn { color: var(--dcdr-warning); background: color-mix(in srgb, var(--dcdr-warning) 12%, transparent); }
.cd-chip.is-bad { color: var(--dcdr-danger); background: color-mix(in srgb, var(--dcdr-danger) 12%, transparent); }

.cd-quote {
  margin: 8px 0 0; padding-left: 8px; border-left: 2px solid var(--dcdr-border-strong);
  font-size: 11.5px; color: var(--dcdr-text-2); line-height: 1.6; word-break: break-word;
}
.cd-error-msg { margin: 8px 0 0; font-size: 12px; color: var(--dcdr-danger); line-height: 1.65; word-break: break-word; }

/* 单步瀑布条: 位置=开始时间, 宽度=该步占整条链路的比例 */
.cd-bar { position: relative; height: 4px; margin-top: 9px; border-radius: 2px; background: var(--dcdr-hover); overflow: hidden; }
.cd-bar-fill { position: absolute; top: 0; left: 0; height: 100%; border-radius: 2px; opacity: 0.85; }
.cd-bar-fill.step-llm { background: var(--dcdr-accent); }
.cd-bar-fill.step-tool { background: var(--dcdr-success); }
.cd-bar-fill.step-rag { background: var(--dcdr-warning); }
.cd-bar-fill.step-memory { background: var(--dcdr-accent-2); }
.cd-bar-fill.step-prompt, .cd-bar-fill.step-citation { background: var(--dcdr-text-3); }

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
.cd-dot.step-prompt { background: var(--dcdr-text-3); }
.cd-dot.step-citation { background: var(--dcdr-accent-2); }
.cd-dot.step-error { background: var(--dcdr-danger); }

.cd-card {
  flex: 1; min-width: 0; margin-bottom: 12px;
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: 9px; padding: 11px 13px;
  transition: border-color 0.16s var(--dcdr-ease);
}
.cd-card:hover { border-color: var(--dcdr-border-strong); }
/* 失败节点: 时间轴上要有明确的"红色终点", 而不是让人从头翻找哪一步不对劲 */
.cd-card.is-failed {
  border-color: color-mix(in srgb, var(--dcdr-danger) 35%, transparent);
  background: color-mix(in srgb, var(--dcdr-danger) 5%, var(--dcdr-surface-faint));
}
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
  /* 抽屉里本来就有一层滚动: 原文块再各自滚会变成"滚动条套滚动条", 尽量一次看全 */
  max-height: 320px; overflow-y: auto;
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
