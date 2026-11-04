<template>
  <div class="web-health">
    <!-- 工具栏 -->
    <div class="wh-toolbar">
      <div class="wh-title">
        <span class="wh-h1">联网健康度</span>
        <el-tag size="small" type="info">仅管理员</el-tag>
        <span class="wh-sub">联网搜索与抓取的可观测性 —— 看真实流量是否在漂移</span>
      </div>
      <div class="wh-actions">
        <el-radio-group v-model="days" size="default" @change="load">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
          <el-radio-button :value="180">近 180 天</el-radio-button>
        </el-radio-group>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="data?.truncated"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom:12px"
      :title="`日志量超过扫描上限(${data.scanLimit} 条), 当前指标只覆盖最近的部分记录, 仅供参考`"
    />

    <div v-loading="loading" class="wh-body">
      <!-- KPI 卡片 -->
      <div class="wh-cards">
        <div class="stat-card">
          <div class="s-value">{{ data?.webCalls ?? '-' }}</div>
          <div class="s-label">
            联网调用
            <span class="s-sub">搜索 {{ data?.searchCalls ?? 0 }} · 抓取 {{ data?.extractCalls ?? 0 }}</span>
          </div>
        </div>
        <!-- 空结果率是第一指标: 它一涨, 回答就悄悄变成"模型自由发挥", 而对话界面毫无异常 -->
        <div class="stat-card" :class="{ warn: (data?.emptyRate ?? 0) >= 0.2 }">
          <div class="s-value">{{ pct(data?.emptyRate) }}</div>
          <div class="s-label">
            空结果率
            <span v-if="emptyDelta" :class="['delta', emptyDelta.cls]">{{ emptyDelta.text }}</span>
          </div>
        </div>
        <!-- 清洗存活率: 上游给了不少却被自家规则扔掉时它会掉 -->
        <div class="stat-card" :class="{ warn: data?.avgKeepRatio != null && data.avgKeepRatio < 0.5 }">
          <div class="s-value">{{ pct(data?.avgKeepRatio) }}</div>
          <div class="s-label">
            清洗存活率
            <span v-if="keepDelta" :class="['delta', keepDelta.cls]">{{ keepDelta.text }}</span>
          </div>
        </div>
        <div class="stat-card" :class="{ warn: data?.extractSuccessRate != null && data.extractSuccessRate < 0.7 }">
          <div class="s-value">{{ pct(data?.extractSuccessRate) }}</div>
          <div class="s-label">
            抓取成功率
            <span v-if="extractDelta" :class="['delta', extractDelta.cls]">{{ extractDelta.text }}</span>
          </div>
        </div>
        <!-- 薄页: 抓取成功率会骗人 —— 抓回登录页也算成功, 但模型只拿到一句"请先登录" -->
        <div class="stat-card" :class="{ warn: (data?.thinRate ?? 0) >= 0.3 }">
          <div class="s-value">{{ pct(data?.thinRate) }}</div>
          <div class="s-label">薄页占比</div>
        </div>
        <div class="stat-card" :class="{ warn: (data?.p95CostMs ?? 0) > 15000 }">
          <div class="s-value">{{ data?.p95CostMs != null ? data.p95CostMs + 'ms' : '-' }}</div>
          <div class="s-label">耗时 P95(含抓取)</div>
        </div>
      </div>

      <div v-if="data?.prev" class="wh-prev">
        对比上一周期({{ data.days }} 天前): 联网 {{ data.prev.webCalls }} 次 ·
        空结果 {{ pct(data.prev.emptyRate) }} · 存活率 {{ pct(data.prev.avgKeepRatio) }} ·
        抓取成功 {{ pct(data.prev.extractSuccessRate) }}
      </div>

      <!-- 按天趋势 -->
      <el-card shadow="never" class="wh-card">
        <template #header>
          <span class="card-title">按天趋势</span>
          <span class="card-hint">空结果率突增的那天, 通常就是问题的起点</span>
        </template>
        <div v-if="!data?.trend?.length" class="wh-empty">当前窗口内没有联网记录</div>
        <!-- 联网用量与成本: 与健康度是两件事(量 vs 效果), 单独一块, 不混进上面的 KPI -->
        <div v-if="usage" class="ub-block">
          <div class="ub-head">
            <span class="ub-title">联网用量与成本</span>
            <span class="ub-sub">
              近 {{ usage.days }} 天 · DeepSeek 按「成功请求」计费（失败/未启用不计），自建 SearXNG 零单价
            </span>
            <el-tag v-if="!usage.pricingConfigured" size="small" type="warning">未配置单价</el-tag>
            <el-tag v-else-if="pricingUnmatched" size="small" type="warning">单价未匹配到来源</el-tag>
            <el-tag v-if="usage.truncated" size="small" type="info">样本已截断</el-tag>
          </div>
          <div class="ub-cards">
            <div class="stat-card">
              <div class="s-value">{{ usage.searchCalls }}</div>
              <div class="s-label">
                搜索次数<span class="s-sub">web_search · 计费 {{ usage.billableSearchCalls }}</span>
              </div>
            </div>
            <div class="stat-card">
              <div class="s-value">{{ usage.extractCalls }}</div>
              <div class="s-label">
                抓取次数<span class="s-sub">web_extract · 计费 {{ usage.billableExtractCalls }}</span>
              </div>
            </div>
            <div class="stat-card">
              <div class="s-value">{{ usage.estimatedCost == null ? '—' : '¥ ' + usage.estimatedCost }}</div>
              <div class="s-label">
                估算费用
                <span v-if="pricingUnmatched" class="s-sub">单价 key 与来源名对不上，检查配置</span>
                <span v-else-if="usage.estimatedCost == null" class="s-sub">未配置单价，只统计次数</span>
              </div>
            </div>
          </div>
          <table v-if="usage.providers.length" class="ub-table">
            <thead>
              <tr><th>源</th><th class="ta-r">搜索 总数（计费）</th><th class="ta-r">抓取 总数（计费）</th>
                <th class="ta-r">单价 ¥/次（搜索 / 抓取）</th><th class="ta-r">费用</th></tr>
            </thead>
            <tbody>
              <tr v-for="p in usage.providers" :key="p.provider">
                <td><code>{{ p.provider }}</code></td>
                <td class="ta-r">{{ p.searchCalls }}（{{ p.billableSearch }}）</td>
                <td class="ta-r">{{ p.extractCalls }}（{{ p.billableExtract }}）</td>
                <td class="ta-r">{{ priceOf(p.searchPrice) }} / {{ priceOf(p.extractPrice) }}</td>
                <td class="ta-r">{{ p.cost == null ? '—' : '¥ ' + p.cost }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="ub-empty">
            窗口内还没有联网调用记录。历史数据没有 provider 字段的记为 none 且不计费。
          </div>

          <!-- 按天花费: 总费用看不出"哪天花得多", 搜索占比高时尤其需要单独一列 -->
          <div v-if="dailyRows.length" class="ub-daily">
            <div class="ub-daily-head">
              <span class="ub-title">每日花费</span>
              <span class="ub-sub">
                搜索费用合计
                <b class="ub-daily-total">{{ searchCostTotal == null ? '—' : fmtCost(searchCostTotal) }}</b>
              </span>
              <span class="ub-sub">计费/总 = 实际计费次数 / 全部调用次数</span>
            </div>
            <table class="ub-table">
              <thead>
                <tr>
                  <th>日期</th>
                  <th class="ta-r">搜索 计费/总</th>
                  <th class="ta-r">搜索费用</th>
                  <th class="ta-r">抓取费用</th>
                  <th class="ta-r">合计</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in dailyRows" :key="d.date">
                  <td>{{ d.date }}</td>
                  <td class="ta-r">{{ d.billableSearch }} / {{ d.searchCalls }}</td>
                  <td class="ta-r ub-daily-cost">{{ fmtCost(d.searchCost) }}</td>
                  <td class="ta-r">{{ fmtCost(d.extractCost) }}</td>
                  <td class="ta-r">{{ fmtCost(d.cost) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-else ref="trendRef" class="wh-chart"></div>
      </el-card>

      <!-- 空结果成因分布: 整份视图最有用的一节 -->
      <el-card shadow="never" class="wh-card">
        <template #header>
          <span class="card-title">空结果成因</span>
          <span class="card-hint">
            「上游无返回」要查 SearXNG 与引擎配额；「结果全被过滤」是我们自己的清洗规则过严
            —— 两者对调用方的表现一模一样，只能靠这里区分
          </span>
        </template>
        <div v-if="!data?.byReason?.length" class="wh-empty">当前窗口内没有空结果记录</div>
        <el-table v-else :data="data.byReason" size="small" style="width:100%">
          <el-table-column label="成因" min-width="200">
            <template #default="{ row }">
              <span :class="{ 'cell-warn': row.reason === 'all_filtered' }">
                {{ row.label || row.reason }}
              </span>
              <span class="cell-code">{{ row.reason }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="calls" label="次数" width="100" />
          <el-table-column label="占比" width="110">
            <template #default="{ row }">{{ pct(row.share) }}</template>
          </el-table-column>
          <el-table-column label="平均上游条数" width="130">
            <template #default="{ row }">{{ row.avgRawCount ?? '-' }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 清洗存活率异常 -->
      <el-card shadow="never" class="wh-card">
        <template #header>
          <span class="card-title">清洗存活率异常(最近 {{ data?.weakSamples?.length || 0 }} 条)</span>
          <span class="card-hint">
            上游给了 ≥10 条却只留下 ≤2 条 —— 最可能是清洗规则误伤(丢掉的是真实文章)，
            也可能查询词本身就模糊(引擎只能给聚合页)
          </span>
        </template>
        <div v-if="!data?.weakSamples?.length" class="wh-empty">没有异常样本</div>
        <el-table v-else :data="data.weakSamples" size="small" style="width:100%">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="query" label="搜索词" min-width="260" show-overflow-tooltip />
          <el-table-column prop="rawCount" label="上游" width="80" />
          <el-table-column prop="results" label="保留" width="80" />
          <el-table-column label="存活率" width="100">
            <template #default="{ row }">
              <span class="cell-warn">{{ pct(row.keepRatio) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="userId" label="用户" width="140" show-overflow-tooltip />
        </el-table>
      </el-card>

      <!-- 薄页样本 -->
      <el-card shadow="never" class="wh-card">
        <template #header>
          <span class="card-title">薄页样本(最近 {{ data?.thinSamples?.length || 0 }} 条)</span>
          <span class="card-hint">
            抓取显示成功但正文极短 —— 通常是登录页 / 验证码页 / 纯 JS 渲染的空壳页
          </span>
        </template>
        <div v-if="!data?.thinSamples?.length" class="wh-empty">没有薄页样本</div>
        <el-table v-else :data="data.thinSamples" size="small" style="width:100%">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="query" label="搜索词 / 抓取地址" min-width="280" show-overflow-tooltip />
          <el-table-column label="抓取" width="100">
            <template #default="{ row }">{{ row.extractOk }}/{{ row.extractOk + row.extractFailed }}</template>
          </el-table-column>
          <el-table-column prop="contentChars" label="正文字数" width="110" />
          <el-table-column prop="thinPages" label="薄页数" width="90" />
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getWebHealth, getWebUsage, extractMessage } from '@/api'

// ECharts 按需引入: 只注册用到的图表与组件, 避免整包进入产物
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

/**
 * 图表配色取 theme.css 里的实际色值而非 var(--dcdr-*)。
 * <p>ECharts 用 canvas 渲染, 不认 CSS 变量 —— 传进去会退化成默认色板,
 * 表现为"图能出来但颜色不对", 而且不报错。
 */
const C = {
  danger: '#ff8fa3',
  accent: '#2ea7ff',
  success: '#13ddc4',
  muted: '#909399'
}

const days = ref(30)
const loading = ref(false)
const data = ref(null)
/** 联网用量与成本(次数 + 按源估算费用); 与 data 分开, 一块挂了不影响另一块 */
const usage = ref(null)
const trendRef = ref(null)
let chart = null

function priceOf(v) {
  return v == null ? '未配' : Number(v).toString()
}

/**
 * 配了单价但没有任何来源命中 —— 通常是 key 与 provider 名对不上
 * (比如把 deepseek 写成 deepseek-pro)。
 * 这种状态后端只能给出 ¥0.00, 必须显式提示, 否则会被当成"联网不要钱"。
 * 只有 none(历史数据无 provider)时不提示: 那是数据缺失, 不是配置错误。
 */
const pricingUnmatched = computed(() => {
  const u = usage.value
  if (!u || !u.pricingConfigured || u.pricingMatched) return false
  return (u.providers || []).some(p => p.provider !== 'none')
})

/** 每日花费倒序: 最近的一天先看, 与执行历史的方向一致 */
const dailyRows = computed(() => (usage.value?.daily || []).slice().reverse())

/** 窗口内搜索费用合计; 未配置单价时后端返回 null, 这里也保持 null 而不是 0 */
const searchCostTotal = computed(() => {
  const costs = dailyRows.value.map(d => d.searchCost).filter(v => v != null)
  if (!costs.length) return null
  return costs.reduce((sum, v) => sum + Number(v), 0)
})

/** 费用可能小到 0.005, 两位小数会全变成 0.01/0; 按金额大小切换精度 */
function fmtCost(v) {
  if (v == null) return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  if (n === 0) return '¥ 0'
  return '¥ ' + n.toFixed(n < 0.01 ? 4 : 2)
}

async function load() {
  loading.value = true
  try {
    // 并行拉: 两个接口都扫同一批日志, 串行会白白多等一轮
    const [health, use] = await Promise.all([
      getWebHealth({ days: days.value }),
      getWebUsage({ days: days.value })
    ])
    data.value = health
    usage.value = use
    await nextTick()
    renderChart()
  } catch (e) {
    // 403 说明当前账号不是管理员(后端也做了校验)
    ElMessage.error(extractMessage(e) || '加载联网健康度失败')
  } finally {
    loading.value = false
  }
}

function renderChart() {
  if (!trendRef.value || !data.value?.trend?.length) return
  if (!chart) {
    chart = echarts.init(trendRef.value)
  }
  const trend = data.value.trend
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['空结果率', '清洗存活率', '抓取成功率'], right: 0 },
    grid: { left: 56, right: 32, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: trend.map((p) => p.date) },
    yAxis: {
      type: 'value',
      name: '占比',
      max: 1,
      axisLabel: { formatter: (v) => (v * 100).toFixed(0) + '%' }
    },
    series: [
      {
        name: '空结果率',
        type: 'bar',
        data: trend.map((p) => p.emptyRate),
        itemStyle: { color: C.danger },
        barMaxWidth: 22
      },
      {
        name: '清洗存活率',
        type: 'line',
        smooth: true,
        connectNulls: true,
        data: trend.map((p) => p.avgKeepRatio),
        itemStyle: { color: C.accent }
      },
      {
        name: '抓取成功率',
        type: 'line',
        smooth: true,
        connectNulls: true,
        data: trend.map((p) => p.extractSuccessRate),
        itemStyle: { color: C.success }
      }
    ]
  })
  chart.resize()
}

// 与上一周期对比: 给出"在变好还是变差"的方向, 比绝对值更有意义
const emptyDelta = computed(() => delta(data.value?.emptyRate, data.value?.prev?.emptyRate, true))
const keepDelta = computed(() => delta(data.value?.avgKeepRatio, data.value?.prev?.avgKeepRatio, false))
const extractDelta = computed(() =>
  delta(data.value?.extractSuccessRate, data.value?.prev?.extractSuccessRate, false)
)

function delta(cur, prev, lowerIsBetter) {
  if (cur == null || prev == null) return null
  const diff = cur - prev
  if (Math.abs(diff) < 0.005) return { text: '持平', cls: '' }
  const better = lowerIsBetter ? diff < 0 : diff > 0
  const sign = diff > 0 ? '+' : ''
  return { text: sign + (diff * 100).toFixed(1) + 'pp', cls: better ? 'up' : 'down' }
}

const fmt = (v, n) => (v == null ? '-' : Number(v).toFixed(n))
const pct = (v) => (v == null ? '-' : (v * 100).toFixed(1) + '%')
const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

function onResize() {
  chart?.resize()
}

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})

// 切换窗口天数后, 数据维度变了, 图表需要重绘
watch(days, () => {
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.web-health { flex: 1; overflow-y: auto; padding: 20px 24px; background: var(--dcdr-bg-subtle); }
.wh-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.wh-title { display: flex; align-items: center; gap: 10px; }
.wh-h1 { font-size: 18px; font-weight: 600; color: var(--dcdr-text-1); }
.wh-sub { font-size: 12px; color: var(--dcdr-text-3); }
.wh-actions { display: flex; align-items: center; gap: 12px; }
.wh-body { display: flex; flex-direction: column; gap: 16px; }

/* KPI 卡片: 与检索健康度保持同一视觉语言 */
.wh-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
.stat-card {
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 10px;
  padding: 16px; text-align: center; transition: border-color 0.2s;
}
.stat-card.warn { border-color: var(--dcdr-danger); background: var(--dcdr-bg-subtle); }
.stat-card .s-value { font-size: 24px; font-weight: 600; color: var(--dcdr-text-1); }
.stat-card.warn .s-value { color: var(--dcdr-danger); }
.stat-card .s-label { font-size: 12px; color: var(--dcdr-text-3); margin-top: 6px; }
.stat-card .s-sub { display: block; font-size: 11px; color: var(--dcdr-text-3); margin-top: 2px; }
.delta { margin-left: 6px; font-size: 11px; }
.delta.up { color: var(--dcdr-success); }
.delta.down { color: var(--dcdr-danger); }

.wh-prev {
  font-size: 12px; color: var(--dcdr-text-3);
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 10px 14px;
}

.wh-card { border-radius: 10px; }
.card-title { font-weight: 600; color: var(--dcdr-text-1); }
.card-hint { margin-left: 12px; font-size: 12px; color: var(--dcdr-text-3); }
.ub-block { margin: 6px 0 18px; padding: 14px 16px; border: 1px solid var(--dcdr-border);
  border-radius: 8px; background: var(--dcdr-bg-subtle); }
.ub-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.ub-title { font-size: 12.5px; font-weight: 600; color: var(--dcdr-text-1); }
.ub-sub { font-size: 11px; color: var(--dcdr-text-3); }
.ub-cards { display: flex; gap: 10px; margin-bottom: 12px; }
.ub-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.ub-table th, .ub-table td { padding: 6px 10px; border-bottom: 1px solid var(--dcdr-border); text-align: left; }
.ub-table th { font-size: 10.5px; font-weight: 500; color: var(--dcdr-text-3); }
.ub-table td { color: var(--dcdr-text-1); font-family: var(--dcdr-font-mono); }
.ub-table .ta-r { text-align: right; }
.ub-empty { font-size: 11.5px; color: var(--dcdr-text-3); padding: 4px 0; }
/* 每日花费单独一块, 与"按源汇总"用虚线上分隔 */
.ub-daily { margin-top: 16px; }
.ub-daily-head { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px;
  margin-bottom: 6px; padding-top: 12px; border-top: 1px dashed var(--dcdr-border); }
.ub-daily-total { color: var(--dcdr-accent); font-weight: 600; }
.ub-daily-cost { color: var(--dcdr-accent); font-weight: 600; }

.wh-chart { height: 300px; }
.wh-empty { text-align: center; color: var(--dcdr-text-3); padding: 40px 0; }
.cell-warn { color: var(--dcdr-danger); font-weight: 500; }
.cell-code {
  margin-left: 8px; font-family: var(--dcdr-font-mono); font-size: 11px; color: var(--dcdr-text-3);
}
</style>
