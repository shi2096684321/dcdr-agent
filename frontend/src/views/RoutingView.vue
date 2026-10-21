<template>
  <div class="routing-mode">
    <!-- 页头: 一句话说明这页解决什么 —— 路由判断准不准、阈值该不该调 -->
    <div class="rt-toolbar">
      <div class="tb-title">
        智能路由
        <span class="tb-sub">每条消息的路由判断结果 · 用于分析准确率与调优置信度阈值</span>
      </div>
      <div class="tb-actions">
        <el-select v-if="authStore.isAdmin" v-model="filterUserId" size="small" style="width:120px" @change="loadLogs">
          <!-- 取值必须非空: 空串会被 el-select 当作"未选择", 输入框什么都不显示
               (与调用链页同一处修正 —— 两页原本是同一份代码, 不一起改会出现"一页显示、一页空白") -->
          <el-option label="仅自己" value="self" />
          <el-option label="全部用户" value="all" />
        </el-select>
        <el-select v-model="filterType" size="small" style="width:120px" @change="loadLogs" placeholder="路由类型">
          <el-option label="全部类型" value="" />
          <el-option label="工作流" value="workflow" />
          <el-option label="专家" value="agent" />
          <el-option label="普通对话" value="chat" />
        </el-select>
        <el-button size="small" @click="loadLogs" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- 指标条: 置信度是这一页唯一要盯的数, 放在最前 -->
    <div class="metric-row" v-if="logs.length">
      <div class="metric">
        <span class="m-label">总记录</span>
        <span class="m-value">{{ total }}</span>
      </div>
      <div class="metric">
        <span class="m-label">本页平均置信度</span>
        <span class="m-value">{{ avgConfidence }}</span>
      </div>
      <div class="metric">
        <span class="m-label">高置信</span>
        <span class="m-value is-ok">{{ highConfCount }}</span>
      </div>
      <div class="metric">
        <span class="m-label">低置信 / 回退</span>
        <span class="m-value" :class="{ 'is-warn': lowConfCount > 0 }">{{ lowConfCount }}</span>
      </div>
      <div class="metric">
        <span class="m-label">本页判定成本</span>
        <span class="m-value">{{ totalTokens }}</span>
      </div>
    </div>

    <!-- 行式列表: 一行一条判断, 点行展开「判断依据」——那才是调阈值时要读的东西 -->
    <div class="rt-table" v-loading="loading">
      <div class="rt-head">
        <span>时间</span>
        <span>路由</span>
        <span>命中目标</span>
        <span class="ta-r">置信度</span>
        <span>用户消息</span>
        <span class="ta-r">Token</span>
        <span class="ta-r">判定</span>
      </div>

      <template v-for="log in filteredLogs" :key="log.id">
        <div class="rt-row" :class="{ open: expanded.has(log.id), low: !log.highConfidence }" @click="toggle(log.id)">
          <span class="rt-time">{{ formatTime(log.createdAt) }}</span>
          <span class="rt-type" :class="'rt-' + log.routeType">{{ routeTypeLabel(log.routeType) }}</span>
          <span class="rt-target" :class="{ none: !log.targetName }">{{ log.targetName || log.targetId || '—' }}</span>
          <span class="rt-conf" :class="confClass(log)">{{ formatConf(log.confidence) }}</span>
          <span class="rt-msg" :title="log.userMessage">
            <svg class="rt-caret" :class="{ open: expanded.has(log.id) }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg>
            {{ log.userMessage || '—' }}
          </span>
          <span class="rt-num">{{ log.totalTokens || '—' }}</span>
          <span class="rt-verdict" :class="log.highConfidence ? 'is-ok' : 'is-warn'">
            {{ log.highConfidence ? '直采' : '回退' }}
          </span>
        </div>

        <div v-if="expanded.has(log.id)" class="rt-detail">
          <div class="rd-row">
            <span class="rd-k">判断依据</span>
            <p class="rd-reason">{{ log.reason || '（后端未记录判断依据）' }}</p>
          </div>
          <div class="rd-meta">
            <span class="rd-item" v-if="log.confidence != null">置信度 {{ formatConf(log.confidence) }} · 阈值 0.80</span>
            <span class="rd-item" v-if="log.targetId">目标 <code>{{ log.targetId }}</code></span>
            <span class="rd-item" v-if="log.userId && authStore.isAdmin">用户 {{ shortId(log.userId) }}</span>
            <button v-if="authStore.isAdmin" class="rd-btn" @click.stop="openDetail(log)">查看详情</button>
          </div>
        </div>
      </template>

      <div v-if="!loading && !filteredLogs.length" class="empty-tip">
        暂无路由记录<br />
        在对话页面直接输入需求（不手动选专家 / 工作流），系统会自动判断路由并记录在此
      </div>
    </div>

    <div class="pager" v-if="total > pageSize">
      <el-pagination background layout="total, sizes, prev, pager, next" :total="total"
                     :page-sizes="[10, 20, 50, 100]" v-model:page-size="pageSize"
                     v-model:current-page="page" @current-change="loadLogs" @size-change="onSizeChange" />
    </div>

    <!-- 路由详情抽屉: 单次路由判断的完整信息(置信度/目标/token 消耗/判断依据) -->
    <el-drawer v-model="detailVisible" size="560px" title="路由详情">
      <template v-if="detailLog">
        <div class="d-grid">
          <span class="d-k">路由类型</span><span class="d-v">{{ routeTypeLabel(detailLog.routeType) }}</span>
          <span class="d-k">命中目标</span><span class="d-v">{{ detailLog.targetName || detailLog.targetId || '-' }}</span>
          <span class="d-k" v-if="detailLog.targetId">目标 ID</span><span class="d-v mono" v-if="detailLog.targetId">{{ detailLog.targetId }}</span>
          <span class="d-k">置信度</span><span class="d-v">{{ formatConf(detailLog.confidence) }} <span :class="detailLog.highConfidence ? 'tag-ok' : 'tag-warn'">{{ detailLog.highConfidence ? '高置信 · 直采' : '低置信 · 已回退' }}</span></span>
          <span class="d-k">会话 ID</span><span class="d-v mono">{{ detailLog.sessionId || '-' }}</span>
          <template v-if="detailLog.userId && authStore.isAdmin">
            <span class="d-k">用户</span><span class="d-v mono">{{ detailLog.userId }}</span>
          </template>
          <span class="d-k">时间</span><span class="d-v">{{ formatTime(detailLog.createdAt) }}</span>
          <span class="d-k">Token 消耗</span><span class="d-v">入 {{ detailLog.inputTokens ?? '-' }} / 出 {{ detailLog.outputTokens ?? '-' }} / 共 {{ detailLog.totalTokens ?? '-' }} <span class="d-note">(路由探测成本, 不计入用户配额)</span></span>
        </div>
        <div class="d-block">
          <div class="d-k">用户消息</div>
          <pre class="d-pre">{{ detailLog.userMessage || '(空)' }}</pre>
        </div>
        <div class="d-block">
          <div class="d-k">判断依据</div>
          <pre class="d-pre">{{ detailLog.reason || '(无)' }}</pre>
        </div>
        <details class="d-raw" v-if="detailLog.systemPrompt">
          <summary>本次提交的 System Prompt</summary>
          <pre class="d-pre">{{ detailLog.systemPrompt }}</pre>
        </details>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getRoutingLogs, extractMessage } from '@/api'
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
const filterType = ref('')
const expanded = ref(new Set())

// ===== 详情抽屉 =====
const detailVisible = ref(false)
const detailLog = ref(null)
function openDetail(log) {
  detailLog.value = log
  detailVisible.value = true
}

const filteredLogs = computed(() => {
  if (!filterType.value) return logs.value
  return logs.value.filter(l => l.routeType === filterType.value)
})

const highConfCount = computed(() => logs.value.filter(l => l.highConfidence).length)
const lowConfCount = computed(() => logs.value.filter(l => !l.highConfidence).length)

/** 当前页平均置信度(只统计有置信度的记录: 普通对话走直连, 本来就没有这个数) */
const avgConfidence = computed(() => {
  const withConf = logs.value.filter((l) => l.confidence != null)
  if (!withConf.length) return '—'
  const sum = withConf.reduce((a, l) => a + Number(l.confidence || 0), 0)
  return (sum / withConf.length).toFixed(2)
})

const totalTokens = computed(() =>
  logs.value.reduce((a, l) => a + Number(l.totalTokens || 0), 0)
)

function toggle(id) {
  const next = new Set(expanded.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expanded.value = next
}

function routeTypeLabel(t) {
  return ({ workflow: '工作流', agent: '专家', chat: '普通对话' })[t] || t
}
function confClass(log) {
  if (log.confidence == null) return 'none'
  return log.highConfidence ? 'is-ok' : 'is-warn'
}
function formatConf(c) {
  if (c === null || c === undefined) return '—'
  return Number(c).toFixed(2)
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
    const params = {
      page: page.value - 1,
      size: pageSize.value
    }
    // 只有"全部用户"需要带参数: 后端仅在 userId=all 时查全部, 其余取值(含不传)一律查自己
    if (authStore.isAdmin && filterUserId.value === 'all') params.userId = filterUserId.value
    const data = await getRoutingLogs(params)
    logs.value = Array.isArray(data) ? data : (data.content || [])
    total.value = Array.isArray(data) ? data.length : (data.totalElements || data.length)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载路由记录失败')
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
.routing-mode { flex: 1; overflow-y: auto; padding: 18px 24px 24px; }

/* ===== 页头 ===== */
.rt-toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 14px; }
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
.m-value.is-ok { color: var(--dcdr-success); }
.m-value.is-warn { color: var(--dcdr-warning); }

/* ===== 行式列表 ===== */
.rt-table {
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-inner); overflow: hidden; min-height: 180px;
}
.rt-head,
.rt-row {
  display: grid;
  grid-template-columns: 116px 76px 132px 62px minmax(0, 1fr) 56px 44px;
  gap: 12px; align-items: center; padding: 0 14px;
}
.rt-head {
  height: 34px; font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.6px;
  color: var(--dcdr-text-3);
  border-bottom: 1px solid var(--dcdr-border); background: var(--dcdr-bg-subtle);
}
.rt-row {
  height: 40px; font-size: 12.5px; cursor: pointer;
  border-bottom: 1px solid var(--dcdr-border);
  transition: background-color 0.14s var(--dcdr-ease);
}
.rt-row:hover { background: var(--dcdr-hover); }
/* 展开态只用中性提亮: 整行涂强调色会把下面紧邻的展开区压暗, 层级反而乱 */
.rt-row.open { background: var(--dcdr-hover); border-bottom-color: transparent; }
/* 低置信: 左侧一道琥珀竖线, 扫列表时不用读数字也能看出来 */
.rt-row.low { box-shadow: inset 2px 0 0 var(--dcdr-warning); }

.rt-time { font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-text-3); }
.rt-type { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px; }
.rt-workflow { color: var(--dcdr-success); }
.rt-agent { color: var(--dcdr-accent-2); }
.rt-chat { color: var(--dcdr-text-3); }
.rt-target { color: var(--dcdr-text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rt-target.none { color: var(--dcdr-text-3); }
.rt-conf { font-family: var(--dcdr-font-mono); font-size: 11.5px; text-align: right; font-weight: 600; }
.rt-conf.is-ok { color: var(--dcdr-success); }
.rt-conf.is-warn { color: var(--dcdr-warning); }
.rt-conf.none { color: var(--dcdr-text-3); font-weight: 400; }
.rt-msg { display: flex; align-items: center; gap: 5px; min-width: 0; color: var(--dcdr-text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rt-caret { width: 10px; height: 10px; flex: none; color: var(--dcdr-text-3); transition: transform 0.16s var(--dcdr-ease); }
.rt-caret.open { transform: rotate(90deg); color: var(--dcdr-accent-soft); }
.rt-num { font-family: var(--dcdr-font-mono); font-size: 11px; color: var(--dcdr-text-3); text-align: right; }
.rt-verdict { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px; text-align: right; }
.rt-verdict.is-ok { color: var(--dcdr-success); }
.rt-verdict.is-warn { color: var(--dcdr-warning); }

/* ===== 展开区 ===== */
.rt-detail {
  padding: 12px 14px 14px; border-bottom: 1px solid var(--dcdr-border);
  background: var(--dcdr-surface); display: flex; flex-direction: column; gap: 8px;
}
.rd-row { display: flex; gap: 10px; align-items: baseline; }
.rd-k { flex: none; width: 56px; font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.5px; color: var(--dcdr-text-3); }
.rd-reason { flex: 1; min-width: 0; font-size: 12.5px; color: var(--dcdr-text-1); line-height: 1.65; }
.rd-meta { display: flex; align-items: center; gap: 14px; margin-left: 66px; }
.rd-item { font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-3); }
.rd-item code { font-family: var(--dcdr-font-mono); color: var(--dcdr-text-2); }
.rd-btn {
  margin-left: auto; height: 26px; padding: 0 12px; border-radius: 6px; cursor: pointer;
  font-size: 11.5px; color: var(--dcdr-accent-soft);
  background: var(--dcdr-accent-wash); border: 1px solid var(--dcdr-accent-line);
  transition: border-color 0.15s var(--dcdr-ease);
}
.rd-btn:hover { border-color: var(--dcdr-accent); }

.empty-tip { text-align: center; color: var(--dcdr-text-3); padding: 60px 20px; font-size: 12.5px; line-height: 1.9; }
.pager { margin-top: 16px; display: flex; justify-content: center; }

/* ===== 详情抽屉 ===== */
.d-grid { display: grid; grid-template-columns: 100px 1fr; gap: 9px 10px; font-size: 12.5px; align-items: baseline; }
.d-k { color: var(--dcdr-text-3); }
.d-v { color: var(--dcdr-text-1); word-break: break-all; }
.d-v.mono { font-family: var(--dcdr-font-mono); }
.d-note { font-size: 10.5px; color: var(--dcdr-text-3); }
.tag-ok { color: var(--dcdr-success); font-family: var(--dcdr-font-mono); font-size: 10px; }
.tag-warn { color: var(--dcdr-warning); font-family: var(--dcdr-font-mono); font-size: 10px; }
.d-block { margin-top: 14px; }
.d-pre { margin: 4px 0 0; background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); border-radius: 6px; padding: 8px 10px; font-size: 11.5px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; font-family: var(--dcdr-font-mono); }
.d-raw { margin-top: 12px; }
.d-raw summary { font-size: 12px; color: var(--dcdr-accent-soft); cursor: pointer; user-select: none; }
</style>
