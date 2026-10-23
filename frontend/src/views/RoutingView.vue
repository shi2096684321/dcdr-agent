<template>
  <div class="routing-mode">
    <!-- 页头: 一句话说明这页解决什么 —— 路由判断准不准、阈值该不该调 -->
    <div class="rt-toolbar">
      <div class="tb-title">
        智能路由
        <span class="tb-sub">每条消息的路由判断结果 · 用于分析准确率与调优置信度阈值</span>
      </div>
      <div class="tb-actions">
        <el-select v-if="authStore.isAdmin" v-model="filterUserId" size="small" style="width:120px" @change="onFilterChange">
          <!-- 取值必须非空: 空串会被 el-select 当作"未选择", 输入框什么都不显示
               (与调用链页同一处修正 —— 两页原本是同一份代码, 不一起改会出现"一页显示、一页空白") -->
          <el-option label="仅自己" value="self" />
          <el-option label="全部用户" value="all" />
        </el-select>
        <el-select v-model="filterType" size="small" style="width:120px" @change="onFilterChange" placeholder="路由类型">
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

      <template v-for="log in logs" :key="log.id">
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

      <div v-if="!loading && !logs.length" class="empty-tip">
        暂无路由记录<br />
        在对话页面直接输入需求（不手动选专家 / 工作流），系统会自动判断路由并记录在此
      </div>
    </div>

    <!-- 分页: 只要有一条数据就常显(不按"是否超过一页"隐藏) —— 否则数据不足 20 条时
         用户完全看不到分页入口, 会以为这一页不支持翻页/没法改每页条数 -->
    <div class="pager" v-if="total > 0">
      <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="total"
                     :page-sizes="[10, 20, 50, 100]" v-model:page-size="pageSize"
                     v-model:current-page="page" @current-change="loadLogs" @size-change="onSizeChange" />
    </div>

    <!-- 路由详情抽屉: 一次路由判断的完整凭据 —— 置信度离阈值多远, 是这页唯一要回答的问题 -->
    <el-drawer v-model="detailVisible" size="540px" :with-header="false" append-to-body class="route-drawer">
      <div v-if="detailLog" class="rd-wrap">
        <!-- 粘性头部: 结论(命中谁 / 直采还是回退)必须在最上方一眼看到 -->
        <header class="rd-head">
          <div class="rd-head-top">
            <span class="rd-title">路由详情</span>
            <span class="rd-verdict" :class="detailLog.highConfidence ? 'is-ok' : 'is-warn'">
              {{ detailLog.highConfidence ? '高置信 · 直采' : '低置信 · 已回退' }}
            </span>
            <button class="rd-close" @click="detailVisible = false" title="关闭">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>
            </button>
          </div>

          <div class="rd-target">
            <span class="rd-type" :class="'rt-' + detailLog.routeType">{{ routeTypeLabel(detailLog.routeType) }}</span>
            <span class="rd-name" :class="{ none: !detailLog.targetName && !detailLog.targetId }">
              {{ detailLog.targetName || detailLog.targetId || '未命中具体目标' }}
            </span>
          </div>

          <!-- 置信度刻度: 数值本身说明不了什么, 离 0.80 阈值多远才说明问题 -->
          <div class="rd-conf" v-if="detailLog.confidence != null">
            <div class="rd-conf-row">
              <span class="rd-conf-value" :class="detailLog.highConfidence ? 'is-ok' : 'is-warn'">{{ formatConf(detailLog.confidence) }}</span>
              <span class="rd-conf-label">置信度 · 阈值 0.80</span>
              <span class="rd-conf-gap" :class="detailLog.highConfidence ? 'is-ok' : 'is-warn'">{{ confGapText }}</span>
            </div>
            <div class="rd-conf-track">
              <i class="rd-conf-fill" :class="detailLog.highConfidence ? 'is-ok' : 'is-warn'" :style="{ width: confPercent + '%' }"></i>
              <span class="rd-conf-threshold"></span>
            </div>
          </div>
          <div class="rd-conf" v-else>
            <span class="rd-conf-label">这条走了直连, 没有产生路由置信度</span>
          </div>

          <div class="rd-metrics">
            <span class="rd-metric"><b>{{ detailLog.totalTokens ?? '—' }}</b> token</span>
            <span class="rd-time">{{ formatTime(detailLog.createdAt) }}</span>
          </div>
        </header>

        <div class="rd-body">
          <section class="rd-sec">
            <div class="rd-label">用户消息</div>
            <p class="rd-text">{{ detailLog.userMessage || '(空)' }}</p>
          </section>

          <section class="rd-sec">
            <div class="rd-label">判断依据</div>
            <p class="rd-text">{{ detailLog.reason || '（后端未记录判断依据）' }}</p>
          </section>

          <section class="rd-sec">
            <div class="rd-label">命中目标</div>
            <div class="rd-fields">
              <span class="cf"><i>名称</i><b>{{ detailLog.targetName || '—' }}</b></span>
              <span class="cf" v-if="detailLog.targetId"><i>ID</i><b class="mono">{{ detailLog.targetId }}</b></span>
              <span class="cf"><i>会话</i><b class="mono">{{ detailLog.sessionId || '—' }}</b></span>
              <span class="cf" v-if="detailLog.userId && authStore.isAdmin"><i>用户</i><b class="mono">{{ detailLog.userId }}</b></span>
            </div>
          </section>

          <section class="rd-sec">
            <div class="rd-label">路由探测成本</div>
            <div class="rd-fields">
              <span class="cf"><i>入</i><b>{{ detailLog.inputTokens ?? '—' }}</b></span>
              <span class="cf"><i>出</i><b>{{ detailLog.outputTokens ?? '—' }}</b></span>
              <span class="cf"><i>合计</i><b>{{ detailLog.totalTokens ?? '—' }}</b></span>
            </div>
            <p class="rd-note">路由判断消耗的 token 单独计量, 不计入用户配额</p>
          </section>

          <details class="rd-raw" v-if="detailLog.systemPrompt">
            <summary>本次提交的 System Prompt</summary>
            <pre class="rd-pre">{{ detailLog.systemPrompt }}</pre>
          </details>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getRoutingLogs, extractMessage } from '@/api'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()

/** 与后端智能路由的高置信阈值一致(0.8); 仅用于详情页的「差多少」文案 */
const ROUTE_CONFIDENCE_THRESHOLD = 0.8
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

/**
 * 筛选条件变化: 必须先回到第 1 页再查。
 * 否则在第 3 页把范围缩小(切"仅自己"/选某个类型)后, 第 3 页很可能已经越界, 表现为"一选就空白"。
 */
function onFilterChange() {
  page.value = 1
  loadLogs()
}

// ===== 详情抽屉 =====
const detailVisible = ref(false)
const detailLog = ref(null)
function openDetail(log) {
  detailLog.value = log
  detailVisible.value = true
}

const highConfCount = computed(() => logs.value.filter(l => l.highConfidence).length)
const lowConfCount = computed(() => logs.value.filter(l => !l.highConfidence).length)

/** 当前页平均置信度(只统计有置信度的记录: 普通对话走直连, 本来就没有这个数) */
const avgConfidence = computed(() => {
  const withConf = logs.value.filter((l) => l.confidence != null)
  if (!withConf.length) return '—'
  const sum = withConf.reduce((a, l) => a + Number(l.confidence || 0), 0)
  return (sum / withConf.length).toFixed(2)
})

/** 抽屉里那条置信度刻度的宽度百分比(置信度是 0~1) */
const confPercent = computed(() => {
  const c = detailLog.value && detailLog.value.confidence
  if (c == null) return 0
  return Math.max(0, Math.min(Number(c) * 100, 100))
})

/** 置信度离 0.80 阈值差多少 —— 比只看一个 0.71 更能说明该不该调阈值 */
const confGapText = computed(() => {
  const c = detailLog.value && detailLog.value.confidence
  if (c == null) return ''
  const gap = Number(c) - ROUTE_CONFIDENCE_THRESHOLD
  return gap >= 0
    ? `高出阈值 ${gap.toFixed(2)}`
    : `低于阈值 ${Math.abs(gap).toFixed(2)}`
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
    // 类型过滤下推到后端: 本地过滤只能筛"当前页 20 条", 翻页后结果会自相矛盾
    if (filterType.value) params.routeType = filterType.value
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
/* 根容器改为"页头 + 指标条 + 列表 + 分页"四段竖排, 只有列表滚: 分页器因此始终贴在底部可见,
   不会因为列表有 20 行(或展开了几条)就要一直滚到最下面才找得到 */
.routing-mode { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 18px 24px 16px; }

/* ===== 页头 ===== */
.rt-toolbar { flex: none; display: flex; align-items: center; gap: 16px; margin-bottom: 14px; }
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
.m-value.is-ok { color: var(--dcdr-success); }
.m-value.is-warn { color: var(--dcdr-warning); }

/* ===== 行式列表 ===== */
.rt-table {
  /* flex:1 + min-height:0: 列表吃掉剩余高度并自己滚(父级是竖排 flex, 少了 min-height:0 会被内容撑开、
     把分页器顶出可视区); overflow-y 交给列表, 表头才能 sticky 在本区域内而不是整页顶部 */
  flex: 1; min-height: 0; overflow-y: auto;
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-inner);
}
.rt-head,
.rt-row {
  display: grid;
  grid-template-columns: 116px 76px 132px 62px minmax(0, 1fr) 56px 44px;
  gap: 12px; align-items: center; padding: 0 14px;
}
.rt-head {
  position: sticky; top: 0; z-index: 1;
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
.pager { flex: none; margin-top: 12px; display: flex; justify-content: center; }

/* ===== 详情抽屉: 一次路由判断的凭据 ===== */
/* body 的 padding/overflow 在 styles/element.css 里处理(drawer 会 teleport, scoped :deep 够不到) */
.rd-wrap { height: 100%; display: flex; flex-direction: column; }

.rd-head {
  flex: none; padding: 16px 20px 14px;
  background: var(--dcdr-surface); border-bottom: 1px solid var(--dcdr-border);
}
.rd-head-top { display: flex; align-items: center; gap: 10px; }
.rd-title { font-size: 14.5px; font-weight: 600; color: var(--dcdr-text-1); }
.rd-verdict { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px; }
.rd-verdict.is-ok { color: var(--dcdr-success); }
.rd-verdict.is-warn { color: var(--dcdr-warning); }
.rd-close {
  margin-left: auto; width: 26px; height: 26px; flex: none;
  display: inline-flex; align-items: center; justify-content: center; padding: 0;
  border: 1px solid transparent; border-radius: 6px; background: transparent;
  color: var(--dcdr-text-3); cursor: pointer; transition: all 0.16s var(--dcdr-ease);
}
.rd-close svg { width: 13px; height: 13px; }
.rd-close:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border); background: var(--dcdr-hover); }

.rd-target { display: flex; align-items: baseline; gap: 8px; margin-top: 12px; }
.rd-type { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px; }
.rd-name { font-size: 15px; font-weight: 600; color: var(--dcdr-text-1); }
.rd-name.none { font-size: 13px; font-weight: 400; color: var(--dcdr-text-3); }

/* 置信度刻度: 数值 + 离阈值多远 + 一条带阈值刻线的进度条 */
.rd-conf { margin-top: 13px; }
.rd-conf-row { display: flex; align-items: baseline; gap: 8px; }
.rd-conf-value { font-family: var(--dcdr-font-mono); font-size: 17px; font-weight: 600; line-height: 1; }
.rd-conf-value.is-ok { color: var(--dcdr-success); }
.rd-conf-value.is-warn { color: var(--dcdr-warning); }
.rd-conf-label { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px; color: var(--dcdr-text-3); }
.rd-conf-gap { margin-left: auto; font-family: var(--dcdr-font-mono); font-size: 10px; }
.rd-conf-gap.is-ok { color: var(--dcdr-success); }
.rd-conf-gap.is-warn { color: var(--dcdr-warning); }
.rd-conf-track {
  position: relative; height: 5px; margin-top: 7px;
  background: var(--dcdr-hover); border-radius: 999px; overflow: hidden;
}
.rd-conf-fill { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 999px; }
.rd-conf-fill.is-ok { background: var(--dcdr-success); }
.rd-conf-fill.is-warn { background: var(--dcdr-warning); }
/* 阈值刻线画在 80% 处, 与上面文案里的 0.80 对应。
   用底色在填充条上"挖"一道缺口, 比叠一根灰线更清楚 —— 灰线压在绿色上几乎看不见 */
.rd-conf-threshold {
  position: absolute; top: 0; bottom: 0; left: 80%; width: 2px;
  background: var(--dcdr-surface); z-index: 1;
}

.rd-metrics { display: flex; align-items: baseline; gap: 12px; margin-top: 12px; }
.rd-metric { font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-text-3); }
.rd-metric b { font-size: 12.5px; font-weight: 600; color: var(--dcdr-text-1); margin-right: 2px; }
.rd-time { margin-left: auto; font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-text-3); }

.rd-body { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 20px 24px; }
.rd-sec { padding: 13px 0; border-bottom: 1px solid var(--dcdr-border); }
.rd-sec:last-of-type { border-bottom: none; }
.rd-label { font-family: var(--dcdr-font-mono); font-size: 9.5px; letter-spacing: 0.6px; color: var(--dcdr-text-3); margin-bottom: 6px; }
.rd-text {
  margin: 0; font-size: 12.5px; line-height: 1.7; color: var(--dcdr-text-1);
  white-space: pre-wrap; word-break: break-word;
}
.rd-fields { display: flex; flex-wrap: wrap; gap: 6px 16px; }
.cf { display: inline-flex; align-items: baseline; gap: 5px; }
.cf i { font-style: normal; font-family: var(--dcdr-font-mono); font-size: 9.5px; letter-spacing: 0.4px; color: var(--dcdr-text-3); }
.cf b { font-weight: 500; font-size: 11.5px; color: var(--dcdr-text-1); }
.cf b.mono { font-family: var(--dcdr-font-mono); }
.rd-note { margin: 8px 0 0; font-size: 10.5px; color: var(--dcdr-text-3); }

.rd-raw { margin-top: 10px; border-top: 1px dashed var(--dcdr-border); padding-top: 8px; }
.rd-raw > summary {
  display: flex; align-items: center; gap: 6px; list-style: none;
  font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.4px;
  color: var(--dcdr-text-3); cursor: pointer; user-select: none;
  transition: color 0.16s var(--dcdr-ease);
}
.rd-raw > summary::-webkit-details-marker { display: none; }
.rd-raw > summary::before { content: '▸'; font-size: 9px; transition: transform 0.16s var(--dcdr-ease); }
.rd-raw[open] > summary::before { transform: rotate(90deg); }
.rd-raw > summary:hover { color: var(--dcdr-text-1); }
.rd-pre {
  margin: 6px 0 0; padding: 8px 10px;
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); border-radius: 6px;
  font-family: var(--dcdr-font-mono); font-size: 11px; line-height: 1.65;
  color: var(--dcdr-text-2); white-space: pre-wrap; word-break: break-word;
  max-height: 240px; overflow-y: auto;
}

</style>
