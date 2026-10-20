<template>
  <div class="retrieval-health">
    <!-- 工具栏 -->
    <div class="rh-toolbar">
      <div class="rh-title">
        <span class="rh-h1">检索健康度</span>
        <el-tag size="small" type="info">仅管理员</el-tag>
        <span class="rh-sub">RAG 检索质量的可观测性 —— 看真实流量是否在漂移</span>
      </div>
      <div class="rh-actions">
        <el-radio-group v-model="days" size="default" @change="load">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
          <el-radio-button :value="180">近 180 天</el-radio-button>
        </el-radio-group>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- 扫描被截断时的显式提示: 让使用者知道指标只覆盖了部分日志 -->
    <el-alert
      v-if="data?.truncated"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom:12px"
      :title="`日志量超过扫描上限(${data.scanLimit} 条), 当前指标只覆盖最近的部分记录, 仅供参考`"
    />

    <div v-loading="loading" class="rh-body">
      <!-- KPI 卡片 -->
      <div class="rh-cards">
        <div class="stat-card">
          <div class="s-value">{{ data?.ragCalls ?? '-' }}</div>
          <div class="s-label">检索次数</div>
        </div>
        <div class="stat-card" :class="{ warn: (data?.avgHits ?? 99) < 1 }">
          <div class="s-value">{{ fmt(data?.avgHits, 2) }}</div>
          <div class="s-label">平均命中分块</div>
        </div>
        <!-- 零命中率是最值得警惕的指标: 突增意味着知识盲区扩大或检索退化 -->
        <div class="stat-card" :class="{ warn: (data?.zeroHitRate ?? 0) >= 0.15 }">
          <div class="s-value">{{ pct(data?.zeroHitRate) }}</div>
          <div class="s-label">
            零命中率
            <span v-if="zeroHitDelta" :class="['delta', zeroHitDelta.cls]">{{ zeroHitDelta.text }}</span>
          </div>
        </div>
        <div class="stat-card" :class="{ warn: data?.avgTopScore != null && data.avgTopScore < 0.6 }">
          <div class="s-value">{{ fmt(data?.avgTopScore, 3) }}</div>
          <div class="s-label">
            平均最高相似度
            <span v-if="scoreDelta" :class="['delta', scoreDelta.cls]">{{ scoreDelta.text }}</span>
          </div>
        </div>
        <div class="stat-card" :class="{ warn: (data?.p95CostMs ?? 0) > 3000 }">
          <div class="s-value">{{ data?.p95CostMs != null ? data.p95CostMs + 'ms' : '-' }}</div>
          <div class="s-label">检索耗时 P95</div>
        </div>
      </div>

      <div v-if="data?.prev" class="rh-prev">
        对比上一周期({{ data.days }} 天前): 检索 {{ data.prev.ragCalls }} 次 ·
        零命中 {{ pct(data.prev.zeroHitRate) }} · 平均相似度 {{ fmt(data.prev.avgTopScore, 3) }}
      </div>

      <!-- 按天趋势 -->
      <el-card shadow="never" class="rh-card">
        <template #header>
          <span class="card-title">按天趋势</span>
          <span class="card-hint">零命中率突增的那天, 通常就是问题的起点</span>
        </template>
        <div v-if="!data?.trend?.length" class="rh-empty">当前窗口内没有检索记录</div>
        <div v-else ref="trendRef" class="rh-chart"></div>
      </el-card>

      <!-- 按知识库数量拆分 -->
      <el-card shadow="never" class="rh-card">
        <template #header>
          <span class="card-title">按检索范围(知识库数量)</span>
          <span class="card-hint">范围越大候选越多, 效果通常越差</span>
        </template>
        <el-table :data="data?.byKbCount || []" size="small" style="width:100%">
          <el-table-column prop="kbCount" label="知识库数" width="110" />
          <el-table-column prop="ragCalls" label="检索次数" width="110" />
          <el-table-column label="零命中率" width="140">
            <template #default="{ row }">
              <span :class="{ 'cell-warn': row.zeroHitRate >= 0.15 }">{{ pct(row.zeroHitRate) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="平均最高相似度">
            <template #default="{ row }">{{ fmt(row.avgTopScore, 3) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 零命中问题样本 -->
      <el-card shadow="never" class="rh-card">
        <template #header>
          <span class="card-title">零命中问题(最近 {{ data?.zeroHits?.length || 0 }} 条)</span>
          <span class="card-hint">
            用来判断是"知识盲区(该补文档)"还是"检索退化(该排查切分/minScore)"；
            有代表性的可追加进评测集
          </span>
        </template>
        <el-table :data="data?.zeroHits || []" size="small" style="width:100%">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="question" label="问题" min-width="260" show-overflow-tooltip />
          <el-table-column prop="kbCount" label="知识库数" width="100" />
          <el-table-column label="最高相似度" width="120">
            <template #default="{ row }">{{ fmt(row.topScore, 3) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">{{ row.costMs }}ms</template>
          </el-table-column>
          <el-table-column prop="userId" label="用户" width="140" show-overflow-tooltip />
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getRetrievalHealth, extractMessage } from '@/api'

// ECharts 按需引入: 只注册用到的图表与组件, 避免整包进入产物
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const days = ref(30)
const loading = ref(false)
const data = ref(null)
const trendRef = ref(null)
let chart = null

async function load() {
  loading.value = true
  try {
    data.value = await getRetrievalHealth({ days: days.value })
    await nextTick()
    renderChart()
  } catch (e) {
    // 403 说明当前账号不是管理员(后端也做了校验)
    ElMessage.error(extractMessage(e) || '加载检索健康度失败')
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
    legend: { data: ['零命中率', '平均最高相似度', '检索次数'], right: 0 },
    grid: { left: 56, right: 56, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: trend.map((p) => p.date) },
    yAxis: [
      { type: 'value', name: '占比', max: 1, axisLabel: { formatter: (v) => (v * 100).toFixed(0) + '%' } },
      { type: 'value', name: '相似度', min: 0, max: 1 }
    ],
    series: [
      {
        name: '零命中率',
        type: 'bar',
        data: trend.map((p) => p.zeroHitRate),
        itemStyle: { color: 'var(--dcdr-danger)' },
        barMaxWidth: 22
      },
      {
        name: '平均最高相似度',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        connectNulls: true,
        data: trend.map((p) => p.avgTopScore),
        itemStyle: { color: 'var(--dcdr-accent)' }
      },
      {
        name: '检索次数',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        lineStyle: { type: 'dashed' },
        data: trend.map((p) => p.ragCalls),
        itemStyle: { color: '#909399' }
      }
    ]
  })
  chart.resize()
}

// 与上一周期对比: 给出"在变好还是变差"的方向, 比绝对值更有意义
const zeroHitDelta = computed(() => delta(data.value?.zeroHitRate, data.value?.prev?.zeroHitRate, true))
const scoreDelta = computed(() => delta(data.value?.avgTopScore, data.value?.prev?.avgTopScore, false))

function delta(cur, prev, lowerIsBetter) {
  if (cur == null || prev == null) return null
  const diff = cur - prev
  if (Math.abs(diff) < 0.005) return { text: '持平', cls: '' }
  const better = lowerIsBetter ? diff < 0 : diff > 0
  const sign = diff > 0 ? '+' : ''
  return { text: sign + (lowerIsBetter ? (diff * 100).toFixed(1) + 'pp' : diff.toFixed(3)), cls: better ? 'up' : 'down' }
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
.retrieval-health { flex: 1; overflow-y: auto; padding: 20px 24px; background: var(--dcdr-bg-subtle); }
.rh-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.rh-title { display: flex; align-items: center; gap: 10px; }
.rh-h1 { font-size: 18px; font-weight: 600; color: var(--dcdr-text-1); }
.rh-sub { font-size: 12px; color: var(--dcdr-text-3); }
.rh-actions { display: flex; align-items: center; gap: 12px; }
.rh-body { display: flex; flex-direction: column; gap: 16px; }

/* KPI 卡片: 与 MonitorView 的统计卡片保持同一视觉语言 */
.rh-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
.stat-card {
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 10px;
  padding: 16px; text-align: center; transition: border-color 0.2s;
}
.stat-card.warn { border-color: var(--dcdr-danger); background: var(--dcdr-bg-subtle); }
.stat-card .s-value { font-size: 24px; font-weight: 600; color: var(--dcdr-text-1); }
.stat-card.warn .s-value { color: var(--dcdr-danger); }
.stat-card .s-label { font-size: 12px; color: var(--dcdr-text-3); margin-top: 6px; }
.delta { margin-left: 6px; font-size: 11px; }
.delta.up { color: var(--dcdr-success); }
.delta.down { color: var(--dcdr-danger); }

.rh-prev {
  font-size: 12px; color: var(--dcdr-text-3);
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 10px 14px;
}

.rh-card { border-radius: 10px; }
.card-title { font-weight: 600; color: var(--dcdr-text-1); }
.card-hint { margin-left: 12px; font-size: 12px; color: var(--dcdr-text-3); }
.rh-chart { height: 300px; }
.rh-empty { text-align: center; color: var(--dcdr-text-3); padding: 40px 0; }
.cell-warn { color: var(--dcdr-danger); font-weight: 500; }
</style>
