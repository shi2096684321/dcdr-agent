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
import { getWebHealth, extractMessage } from '@/api'

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
const trendRef = ref(null)
let chart = null

async function load() {
  loading.value = true
  try {
    data.value = await getWebHealth({ days: days.value })
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
.wh-chart { height: 300px; }
.wh-empty { text-align: center; color: var(--dcdr-text-3); padding: 40px 0; }
.cell-warn { color: var(--dcdr-danger); font-weight: 500; }
.cell-code {
  margin-left: 8px; font-family: var(--dcdr-font-mono); font-size: 11px; color: var(--dcdr-text-3);
}
</style>
