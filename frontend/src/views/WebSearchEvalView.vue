<template>
  <div class="wse">
    <!-- 工具栏: 来源选择 + 运行。评测是"跑一次看数字"的动作, 所以主体是触发按钮而不是实时图表 -->
    <div class="wse-toolbar">
      <div class="wse-title">
        <span class="h1">排序评测</span>
        <el-tag size="small" type="info">仅管理员</el-tag>
        <span class="sub">固定 20 条 query 的排序质量 · 只用于前后对比，不是实时监控</span>
      </div>
      <div class="wse-actions">
        <el-select v-model="provider" size="default" style="width:150px" :disabled="running">
          <el-option label="两个源对比" value="both" />
          <el-option label="仅 SearXNG" value="searxng" :disabled="!available.searxng" />
          <el-option label="仅 DeepSeek" value="deepseek" :disabled="!available.deepseek" />
        </el-select>
        <el-button type="primary" :loading="running" :disabled="running" @click="run">
          {{ running ? '运行中…' : '运行评测' }}
        </el-button>
      </div>
    </div>

    <!-- 运行状态: 三态各说一句话, 不要让用户猜"刚才点了到底跑没跑" -->
    <el-alert v-if="running" type="info" :closable="false" show-icon style="margin-bottom:12px"
              :title="`正在评测 ${(state.targets || []).join(' / ')} · 已完成 ${state.done}/${state.total} 次检索`" />
    <el-alert v-else-if="state.status === 'FAILED'" type="error" :closable="false" show-icon
              style="margin-bottom:12px" :title="state.error || '评测失败'" />
    <el-alert v-else-if="state.status === 'DONE'" type="success" :closable="false" show-icon
              style="margin-bottom:12px"
              :title="`最近一次完成：${state.reportFile}（约 ${(state.targets || []).join(' / ')}）`" />

    <div class="wse-body">
      <!-- 左: 历史报告。没有历史就没有对比, 所以列表本身就是这个页面的核心资产 -->
      <aside class="wse-side">
        <div class="side-head">
          历史报告
          <span class="side-count">{{ reports.length }}</span>
        </div>
        <div v-if="!reports.length" class="side-empty">
          还没有报告<br />点右上角「运行评测」生成第一份基线
        </div>
        <button v-for="r in reports" :key="r.file" class="report-item"
                :class="{ active: r.file === selected }" @click="select(r.file)">
          <span class="ri-time">{{ fmtStamp(r.timestamp) }}</span>
          <span class="ri-mrr">
            <span v-for="(m, id) in r.providers" :key="id" class="ri-src">
              <i>{{ id }}</i>{{ num(m.mrr) }}
            </span>
          </span>
        </button>
      </aside>

      <!-- 右: 选中报告 -->
      <section v-loading="detailLoading" class="wse-main">
        <template v-if="detail">
          <div class="detail-head">
            <span class="dh-time">{{ fmtStamp(detail.timestamp) }}</span>
            <span class="dh-meta">{{ detail.cases }} 条用例 · topK {{ detail.topK }}</span>
          </div>

          <p class="cmp-note">
            <b>命中</b> = 这条 query 返回的前 {{ detail.topK }} 条结果里，
            有一条 URL 包含它<b>预先标注的金标片段</b>（如 <code>spring.io/blog</code>）。
            每条 query 只判「找到 / 没找到」，<b>不评价单条结果好坏</b>；
            命中位置越靠前，MRR 越高。
          </p>

          <!-- 对比表: 只放三个数, 多了反而看不出重点 -->
          <table class="cmp">
            <thead>
              <tr>
                <th>指标</th>
                <th v-for="id in providerIds" :key="id">{{ id }}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>命中率 HitRate@{{ detail.topK }}<span class="th-note">query 维度</span></td>
                <td v-for="id in providerIds" :key="id">{{ pct(detail.providers[id].hitRate) }}</td>
              </tr>
              <tr>
                <td>MRR@{{ detail.topK }}<span class="th-note">主指标</span></td>
                <td v-for="id in providerIds" :key="id" class="td-strong">
                  {{ num(detail.providers[id].mrr) }}
                </td>
              </tr>
              <tr>
                <td>平均首命中位次</td>
                <td v-for="id in providerIds" :key="id">{{ num(detail.providers[id].avgRank) }}</td>
              </tr>
              <tr>
                <td>前 {{ detail.topK }} 条无金标</td>
                <td v-for="id in providerIds" :key="id">
                  {{ missCount(id) }} / {{ detail.providers[id].cases.length }} 条 query
                </td>
              </tr>
            </tbody>
          </table>

          <!-- 用例明细: 数字告诉你"差多少", 明细告诉你"差在哪"。
               可以切到"全部"—— 只知道错题是不够的, 还要能看到这套题到底考了什么 -->
          <div class="miss-toolbar">
            <span class="mt-title">用例明细</span>
            <span class="mt-sub">共 {{ detail.cases }} 条 query</span>
            <el-radio-group v-model="caseFilter" size="small" style="margin-left:auto">
              <el-radio-button value="miss">仅未命中</el-radio-button>
              <el-radio-button value="all">全部用例</el-radio-button>
            </el-radio-group>
          </div>
          <div v-for="id in providerIds" :key="'m-' + id" class="miss-block">
            <div class="miss-head">
              {{ id }} ·
              <template v-if="caseFilter === 'miss'">未命中 {{ missCount(id) }} 条</template>
              <template v-else>全部 {{ total(id) }} 条（命中 {{ total(id) - missCount(id) }}）</template>
            </div>
            <div v-if="caseFilter === 'miss' && !missCount(id)" class="miss-none">全部命中</div>
            <div v-for="c in casesOf(id)" :key="c.id" class="miss-row">
              <div class="mr-line">
                <span class="mr-hit" :class="c.rank ? 'ok' : 'bad'">
                  {{ c.rank ? '第 ' + c.rank + ' 位' : '未命中' }}
                </span>
                <span class="mr-id">{{ c.id }}</span>
                <span class="mr-type">{{ c.type }}</span>
                <span class="mr-q" :title="c.query">{{ c.query }}</span>
              </div>
              <div class="mr-line mr-sub">
                <span class="mr-exp">
                  想知道：
                  <code v-for="e in (c.expected || [])" :key="e">{{ e }}</code>
                  <i v-if="!(c.expected || []).length">(旧报告无此字段)</i>
                </span>
                <span v-if="c.error" class="mr-err">异常：{{ c.error }}</span>
                <span v-else-if="c.rank" class="mr-first" :title="c.matchedUrl">
                  命中：{{ shortUrl(c.matchedUrl) }}
                </span>
                <span v-else-if="c.firstUrl" class="mr-first" :title="c.firstUrl">
                  实际首条：{{ shortUrl(c.firstUrl) }}
                </span>
              </div>
            </div>
          </div>
        </template>
        <div v-else class="main-empty">
          选左侧一份报告查看详情<br />
          <span>MRR 越高说明"该被找到的结果排得越靠前"；未命中明细会告诉你它到底返回了什么</span>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getEvalOverview, getEvalReport, listEvalReports, runEval } from '@/api/webSearchEval'

const provider = ref('both')
const available = ref({ searxng: true, deepseek: true })
const state = ref({ status: 'IDLE', targets: [], done: 0, total: 0 })
const reports = ref([])
const selected = ref('')
const detail = ref(null)
/** 明细区筛选: 默认只看未命中(改完代码最关心错的那几条), 但可以切到全部看这套题考了什么 */
const caseFilter = ref('miss')
const detailLoading = ref(false)

let pollTimer = null

const running = computed(() => state.value.status === 'RUNNING')
const providerIds = computed(() => Object.keys(detail.value?.providers || {}))

function num(v) {
  return v == null || v === 0 ? '—' : Number(v).toFixed(3)
}
function pct(v) {
  return v == null || v === 0 ? '—' : (Number(v) * 100).toFixed(1) + '%'
}
/** 文件名里的时间戳形如 2026-11-02-1713, 转成可读格式 */
function fmtStamp(s) {
  if (!s) return '—'
  const m = String(s).match(/^(\d{4}-\d{2}-\d{2})-(\d{2})(\d{2})$/)
  return m ? `${m[1]} ${m[2]}:${m[3]}` : s
}
function shortUrl(u) {
  if (!u) return ''
  try {
    const url = new URL(u)
    const path = url.pathname.length > 40 ? url.pathname.slice(0, 40) + '…' : url.pathname
    return url.host + path
  } catch {
    return u.length > 60 ? u.slice(0, 60) + '…' : u
  }
}
function missCount(id) {
  const cases = detail.value?.providers?.[id]?.cases || []
  return cases.filter((c) => !c.rank).length
}
function misses(id) {
  const cases = detail.value?.providers?.[id]?.cases || []
  return cases.filter((c) => !c.rank)
}
function total(id) {
  return (detail.value?.providers?.[id]?.cases || []).length
}
function casesOf(id) {
  return caseFilter.value === 'miss' ? misses(id) : (detail.value?.providers?.[id]?.cases || [])
}

async function loadOverview() {
  try {
    const data = await getEvalOverview()
    available.value = data.providers || {}
    state.value = data.state || { status: 'IDLE', targets: [], done: 0, total: 0 }
    // 刷新页面时若任务正在跑, 继续轮询 —— 否则用户会以为卡住了
    if (state.value.status === 'RUNNING') {
      startPoll()
    } else {
      stopPoll()
    }
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载评测状态失败')
  }
}

async function loadReports(autoSelectLatest = false) {
  try {
    reports.value = await listEvalReports()
    if (!selected.value || autoSelectLatest) {
      if (reports.value.length) {
        await select(reports.value[0].file)
      }
    }
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载历史报告失败')
  }
}

async function select(file) {
  selected.value = file
  detailLoading.value = true
  try {
    detail.value = await getEvalReport(file)
  } catch (e) {
    detail.value = null
    ElMessage.error(extractMessage(e) || '加载报告详情失败')
  } finally {
    detailLoading.value = false
  }
}

async function run() {
  if (running.value) return
  try {
    state.value = await runEval(provider.value)
    // 提交是异步的: 立刻开始轮询, 跑完自动刷新列表并选中最新那份
    startPoll()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '触发评测失败')
  }
}

function startPoll() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    await loadOverview()
    if (!running.value) {
      stopPoll()
      await loadReports(true)
    }
  }, 2000)
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function extractMessage(e) {
  return e?.response?.data?.message || e?.message
}

onMounted(async () => {
  await loadOverview()
  await loadReports(true)
})

onBeforeUnmount(stopPoll)
</script>

<style scoped>
/* 两栏: 左固定宽度放历史, 右侧滚动看明细。只有右侧滚, 是因为左侧是"选哪份"的索引 */
.wse { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 18px 24px 16px; }

.wse-toolbar { flex: none; display: flex; align-items: center; gap: 16px; margin-bottom: 14px; }
.wse-title { display: flex; align-items: baseline; gap: 10px; }
.wse-title .h1 { font-size: 15px; font-weight: 600; color: var(--dcdr-text-1); }
.wse-title .sub { font-size: 11.5px; color: var(--dcdr-text-3); }
.wse-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }

.wse-body { flex: 1; display: flex; gap: 14px; min-height: 0; }

.wse-side { width: 232px; flex: none; display: flex; flex-direction: column; overflow-y: auto;
  border: 1px solid var(--dcdr-border); border-radius: 8px; background: var(--dcdr-bg-subtle); }
.side-head { position: sticky; top: 0; z-index: 1; display: flex; align-items: center; gap: 6px;
  padding: 10px 12px; font-size: 11px; letter-spacing: .4px; color: var(--dcdr-text-3);
  border-bottom: 1px solid var(--dcdr-border); background: var(--dcdr-bg-subtle); }
.side-count { font-family: var(--dcdr-font-mono); color: var(--dcdr-text-2); }
.side-empty { padding: 18px 12px; font-size: 11.5px; line-height: 1.7; color: var(--dcdr-text-3); }

.report-item { display: flex; flex-direction: column; gap: 4px; width: 100%; text-align: left;
  padding: 9px 12px; background: none; border: 0; border-bottom: 1px solid var(--dcdr-border);
  cursor: pointer; transition: background-color .14s var(--dcdr-ease); }
.report-item:hover { background: var(--dcdr-hover); }
.report-item.active { background: var(--dcdr-hover); box-shadow: inset 2px 0 0 var(--dcdr-accent); }
.ri-time { font-size: 12px; color: var(--dcdr-text-1); font-family: var(--dcdr-font-mono); }
.ri-mrr { display: flex; flex-wrap: wrap; gap: 8px; font-size: 10.5px; color: var(--dcdr-text-2); }
.ri-src i { font-style: normal; color: var(--dcdr-text-3); margin-right: 3px; }
.ri-src { font-family: var(--dcdr-font-mono); }

.wse-main { flex: 1; min-width: 0; overflow-y: auto; padding-right: 4px; }

.detail-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; }
.dh-time { font-family: var(--dcdr-font-mono); font-size: 12.5px; color: var(--dcdr-text-1); }
.dh-meta { font-size: 11.5px; color: var(--dcdr-text-3); }

.cmp-note { margin: 0 0 10px; font-size: 11.5px; line-height: 1.7; color: var(--dcdr-text-3); }
.cmp-note b { color: var(--dcdr-text-2); font-weight: 600; }
.cmp-note code { padding: 0 4px; border-radius: 3px; background: var(--dcdr-bg-subtle);
  border: 1px solid var(--dcdr-border); font-family: var(--dcdr-font-mono); font-size: 10.5px; }

.cmp { width: 100%; border-collapse: collapse; font-size: 12.5px; margin-bottom: 18px; }
.cmp th, .cmp td { padding: 8px 12px; text-align: left; border-bottom: 1px solid var(--dcdr-border); }
.cmp th { font-size: 10.5px; letter-spacing: .5px; color: var(--dcdr-text-3); font-weight: 500;
  background: var(--dcdr-bg-subtle); }
.cmp td:first-child { color: var(--dcdr-text-2); }
.cmp td { color: var(--dcdr-text-1); font-family: var(--dcdr-font-mono); }
/* MRR 是主指标, 加粗一档让它在四个数字里有明确的视觉优先级 */
.cmp .td-strong { font-weight: 700; color: var(--dcdr-text-1); }
.th-note { margin-left: 6px; font-size: 9.5px; color: var(--dcdr-accent-soft);
  border: 1px solid var(--dcdr-accent-soft); border-radius: 3px; padding: 0 4px; }

.miss-toolbar { display: flex; align-items: center; gap: 8px; margin: 0 0 10px; }
.mt-title { font-size: 12px; font-weight: 600; color: var(--dcdr-text-1); }
.mt-sub { font-size: 11px; color: var(--dcdr-text-3); }

.miss-block { margin-bottom: 16px; }
.miss-head { font-size: 11.5px; color: var(--dcdr-text-2); margin-bottom: 6px; }
.miss-none { font-size: 11.5px; color: var(--dcdr-success); padding: 4px 0 4px 2px; }
.miss-row { display: flex; flex-direction: column; gap: 3px; padding: 7px 0;
  border-bottom: 1px dashed var(--dcdr-border); font-size: 11.5px; }
.mr-line { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
/* 第二行整体压暗一档: 它是"证据", 不该和 query 抢第一眼的注意力 */
.mr-sub { padding-left: 68px; font-size: 10.5px; color: var(--dcdr-text-3); }
.mr-exp code { margin-left: 4px; padding: 0 4px; border-radius: 3px;
  background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border);
  font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-accent-soft); }
.mr-exp i { font-style: normal; opacity: .7; }
/* 命中状态放最左: 扫一眼就能数出"这 20 条对了几条" */
.mr-hit { flex: none; width: 58px; font-size: 10px; font-family: var(--dcdr-font-mono);
  text-align: center; border-radius: 3px; padding: 1px 0; }
.mr-hit.ok { color: var(--dcdr-success); border: 1px solid var(--dcdr-success); }
.mr-hit.bad { color: var(--dcdr-text-3); border: 1px solid var(--dcdr-border); }
.mr-id { font-family: var(--dcdr-font-mono); font-size: 10.5px; color: var(--dcdr-text-3); flex: none; width: 180px; }
.mr-type { flex: none; font-size: 10px; color: var(--dcdr-text-3); border: 1px solid var(--dcdr-border);
  border-radius: 3px; padding: 0 4px; }
.mr-q { color: var(--dcdr-text-1); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mr-first { flex: 1; min-width: 0; max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--dcdr-text-3); font-family: var(--dcdr-font-mono); font-size: 10.5px; }
.mr-err { flex: none; color: var(--dcdr-danger); font-size: 10.5px; }

.main-empty { height: 100%; display: flex; flex-direction: column; align-items: center;
  justify-content: center; gap: 8px; font-size: 12.5px; color: var(--dcdr-text-3); text-align: center; }
.main-empty span { font-size: 11px; color: var(--dcdr-text-3); opacity: .8; }
</style>
