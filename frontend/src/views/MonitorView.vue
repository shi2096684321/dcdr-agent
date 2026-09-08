<template>
  <div class="monitor-mode">
    <div class="monitor-header">
      <h2>监控与成本</h2>
      <p class="subtitle">工作流执行监控 / LLM token 成本统计 / 配额使用情况 (V3.0-beta F-34 / F-39)</p>
    </div>

    <div class="toolbar">
      <el-select v-model="days" size="small" style="width:130px" @change="loadAll">
        <el-option label="近 7 天" :value="7" />
        <el-option label="近 30 天" :value="30" />
        <el-option label="近 90 天" :value="90" />
      </el-select>
      <el-select v-if="authStore.isAdmin" v-model="scope" size="small" style="width:140px" @change="loadAll">
        <el-option label="仅自己" value="" />
        <el-option label="全局 (管理员)" value="global" />
      </el-select>
      <el-button size="small" @click="loadAll" :loading="loading">刷新</el-button>
    </div>

    <!-- 配额卡片 (F-39) -->
    <div class="quota-card" v-if="quota">
      <div class="quota-title">
        我的 Token 配额
        <el-tag v-if="quota.quotaEnabled" size="small" type="danger" effect="plain">已启用限额</el-tag>
        <el-tag v-else size="small" type="info" effect="plain">仅统计未限额</el-tag>
      </div>
      <div class="quota-bars">
        <div class="quota-item">
          <div class="q-label">今日</div>
          <el-progress :percentage="dailyPercent" :status="dailyPercent >= 100 ? 'exception' : undefined" />
          <div class="q-text">{{ fmt(quota.dailyUsed) }} / {{ quota.dailyLimit ? fmt(quota.dailyLimit) : '不限' }}</div>
        </div>
        <div class="quota-item">
          <div class="q-label">本月</div>
          <el-progress :percentage="monthlyPercent" :status="monthlyPercent >= 100 ? 'exception' : undefined" />
          <div class="q-text">{{ fmt(quota.monthlyUsed) }} / {{ quota.monthlyLimit ? fmt(quota.monthlyLimit) : '不限' }}</div>
        </div>
      </div>
    </div>

    <!-- 执行监控卡片 -->
    <div class="stat-cards">
      <div class="stat-card">
        <div class="s-value">{{ overview ? overview.totalExecutions : '-' }}</div>
        <div class="s-label">总执行次数</div>
      </div>
      <div class="stat-card success">
        <div class="s-value">{{ overview?.successCount ?? '-' }}</div>
        <div class="s-label">成功</div>
      </div>
      <div class="stat-card danger">
        <div class="s-value">{{ overview?.failedCount ?? '-' }}</div>
        <div class="s-label">失败</div>
      </div>
      <div class="stat-card warn">
        <div class="s-value">{{ overview?.pausedCount ?? '-' }}</div>
        <div class="s-label">暂停待审批</div>
      </div>
      <div class="stat-card">
        <div class="s-value">{{ overview ? (overview.successRate == null ? '-' : (overview.successRate * 100).toFixed(1) + '%') : '-' }}</div>
        <div class="s-label">成功率</div>
      </div>
      <div class="stat-card">
        <div class="s-value">{{ overview ? (overview.avgDurationMs / 1000).toFixed(1) + 's' : '-' }}</div>
        <div class="s-label">平均耗时</div>
      </div>
      <div class="stat-card">
        <div class="s-value">{{ cost ? fmt(cost.totalTokens) : '-' }}</div>
        <div class="s-label">总 Token</div>
      </div>
      <div class="stat-card">
        <div class="s-value">{{ cost ? fmt(cost.callCount) : '-' }}</div>
        <div class="s-label">LLM 调用次数</div>
      </div>
    </div>

    <div class="charts">
      <!-- 按天 token 用量 -->
      <div class="chart-card">
        <div class="c-title">按天 Token 用量</div>
        <div class="bar-chart" v-if="dailyRows.length">
          <div v-for="d in dailyRows" :key="d.date" class="bar-row">
            <span class="bar-date">{{ d.date.slice(5) }}</span>
            <div class="bar-track">
              <div class="bar-fill in" :style="{ width: pct(d.inputTokens) }" :title="'输入 ' + fmt(d.inputTokens)"></div>
              <div class="bar-fill out" :style="{ width: pct(d.outputTokens) }" :title="'输出 ' + fmt(d.outputTokens)"></div>
            </div>
            <span class="bar-val">{{ fmt(d.totalTokens) }}</span>
          </div>
        </div>
        <div v-else class="empty-tip">窗口内暂无用量记录</div>
      </div>

      <!-- 按模型用量 -->
      <div class="chart-card">
        <div class="c-title">按模型用量</div>
        <div class="bar-chart" v-if="modelRows.length">
          <div v-for="m in modelRows" :key="m.model" class="bar-row">
            <span class="bar-date model">{{ m.model }}</span>
            <div class="bar-track">
              <div class="bar-fill in" :style="{ width: pctModel(m.inputTokens) }"></div>
              <div class="bar-fill out" :style="{ width: pctModel(m.outputTokens) }"></div>
            </div>
            <span class="bar-val">{{ fmt(m.totalTokens) }}</span>
          </div>
        </div>
        <div v-else class="empty-tip">窗口内暂无用量记录</div>
      </div>
    </div>

    <!-- 按工作流聚合 -->
    <div class="chart-card">
      <div class="c-title">按工作流聚合</div>
      <el-table :data="overview?.byWorkflow || []" size="small">
        <el-table-column prop="workflowName" label="工作流" min-width="160">
          <template #default="{ row }">{{ row.workflowName || row.workflowId }}</template>
        </el-table-column>
        <el-table-column prop="executions" label="执行" width="70" />
        <el-table-column prop="successCount" label="成功" width="70" />
        <el-table-column prop="failedCount" label="失败" width="70" />
        <el-table-column label="成功率" width="90">
          <template #default="{ row }">{{ row.successRate == null ? '-' : (row.successRate * 100).toFixed(0) + '%' }}</template>
        </el-table-column>
        <el-table-column label="平均耗时" width="100">
          <template #default="{ row }">{{ (row.avgDurationMs / 1000).toFixed(1) }}s</template>
        </el-table-column>
        <el-table-column label="Token" width="100">
          <template #default="{ row }">{{ fmt(row.totalTokens) }}</template>
        </el-table-column>
        <el-table-column label="最近执行" min-width="150">
          <template #default="{ row }">{{ fmtTime(row.lastExecutedAt) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="!overview?.byWorkflow?.length" class="empty-tip">窗口内暂无工作流执行记录</div>
    </div>

    <!-- LLM 服务调用与熔断 (F-40.2/F-40.4, 内置监控面板) -->
    <div class="chart-card">
      <div class="c-title">LLM 服务调用与熔断</div>
      <div class="stat-cards">
        <div class="stat-card">
          <div class="s-value">{{ llm ? llm.totalCalls : '-' }}</div>
          <div class="s-label">LLM 调用总数</div>
        </div>
        <div class="stat-card" :class="{ danger: (llm?.totalErrors || 0) > 0 }">
          <div class="s-value">{{ llm ? llm.totalErrors : '-' }}</div>
          <div class="s-label">失败总数</div>
        </div>
        <div class="stat-card" :class="{ danger: (llm?.overallErrorRate || 0) > 0.05 }">
          <div class="s-value">{{ llm ? (llm.overallErrorRate * 100).toFixed(1) + '%' : '-' }}</div>
          <div class="s-label">总体失败率</div>
        </div>
      </div>
      <el-table :data="llmRows" size="small" style="margin-top: 12px">
        <el-table-column prop="provider" label="模型" min-width="150" />
        <el-table-column prop="calls" label="调用" width="90" />
        <el-table-column label="失败" width="90">
          <template #default="{ row }">
            <span :class="{ 'llm-err': (row.errors || 0) > 0 }">{{ row.errors }}</span>
          </template>
        </el-table-column>
        <el-table-column label="失败率" width="100">
          <template #default="{ row }">{{ ((row.errorRate || 0) * 100).toFixed(1) }}%</template>
        </el-table-column>
        <el-table-column label="平均耗时" width="110">
          <template #default="{ row }">{{ (row.latencyMeanSeconds || 0).toFixed(2) }}s</template>
        </el-table-column>
        <el-table-column label="熔断状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="cbType(row.cbState)" effect="plain">{{ row.cbState }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!llmRows.length" class="empty-tip">暂无 LLM 调用记录（发一条对话后出现）</div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getWorkflowMonitor, getCostStats, getQuotaUsage, getLlmMonitor, extractMessage } from '@/api'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const loading = ref(false)
const days = ref(30)
const scope = ref('')
const overview = ref(null)
const cost = ref(null)
const quota = ref(null)
const llm = ref(null)

const dailyRows = computed(() => (cost.value?.daily || []).slice().reverse())
const modelRows = computed(() => cost.value?.byModel || [])
const maxTotal = computed(() => Math.max(1, ...dailyRows.value.map(d => d.totalTokens)))
const maxModelTotal = computed(() => Math.max(1, ...modelRows.value.map(m => m.totalTokens)))

// LLM 服务状态 (合并熔断快照)
const cbMap = computed(() => {
  const m = {}
  ;(llm.value?.circuitBreaker || []).forEach(c => { m[c.provider] = c.state })
  return m
})
const llmRows = computed(() =>
  (llm.value?.providers || []).map(p => ({ ...p, cbState: cbMap.value[p.provider] || 'CLOSED' }))
)
const cbType = (s) => (s === 'OPEN' ? 'danger' : s === 'HALF_OPEN' ? 'warning' : 'success')

const pct = (v) => Math.min(90, (v / maxTotal.value) * 90) + '%'
const pctModel = (v) => Math.min(90, (v / maxModelTotal.value) * 90) + '%'
const fmt = (v) => {
  const n = Number(v || 0)
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}
const fmtTime = (t) => (t ? new Date(t).toLocaleString('zh-CN') : '-')

const dailyPercent = computed(() => {
  if (!quota.value || !quota.value.dailyLimit) return 0
  return Math.min(100, Math.round((quota.value.dailyUsed / quota.value.dailyLimit) * 100))
})
const monthlyPercent = computed(() => {
  if (!quota.value || !quota.value.monthlyLimit) return 0
  return Math.min(100, Math.round((quota.value.monthlyUsed / quota.value.monthlyLimit) * 100))
})

async function loadAll() {
  loading.value = true
  const params = { days: days.value }
  if (authStore.isAdmin && scope.value) params.scope = scope.value
  try {
    const [wo, cs, qu, lm] = await Promise.all([
      getWorkflowMonitor(params),
      getCostStats(params),
      getQuotaUsage(),
      getLlmMonitor()
    ])
    overview.value = wo
    cost.value = cs
    quota.value = qu
    llm.value = lm
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载监控数据失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => { loadAll() })
</script>

<style scoped>
.monitor-mode { flex: 1; overflow-y: auto; padding: 24px 28px; background: #f5f7fa; display: flex; flex-direction: column; gap: 16px; }
.monitor-header h2 { margin: 0 0 4px; font-size: 20px; color: #303133; }
.subtitle { margin: 0; font-size: 13px; color: #909399; }
.toolbar { display: flex; align-items: center; gap: 10px; }

.quota-card { background: #fff; border: 1px solid #e8e8e8; border-radius: 10px; padding: 16px 20px; }
.quota-title { font-weight: 600; font-size: 14px; color: #303133; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
.quota-bars { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
.q-label { font-size: 13px; color: #606266; margin-bottom: 4px; }
.q-text { font-size: 12px; color: #909399; margin-top: 4px; }

.stat-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; }
.stat-card { background: #fff; border: 1px solid #e8e8e8; border-radius: 10px; padding: 14px 16px; text-align: center; }
.stat-card .s-value { font-size: 22px; font-weight: 700; color: #303133; }
.stat-card .s-label { font-size: 12px; color: #909399; margin-top: 4px; }
.stat-card.success .s-value { color: #67c23a; }
.stat-card.danger .s-value { color: #f56c6c; }
.stat-card.warn .s-value { color: #e6a23c; }

.charts { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.chart-card { background: #fff; border: 1px solid #e8e8e8; border-radius: 10px; padding: 16px 20px; }
.c-title { font-weight: 600; font-size: 14px; color: #303133; margin-bottom: 12px; }
.bar-chart { display: flex; flex-direction: column; gap: 8px; max-height: 280px; overflow-y: auto; }
.bar-row { display: flex; align-items: center; gap: 8px; }
.bar-date { width: 52px; font-size: 12px; color: #909399; text-align: right; flex-shrink: 0; }
.bar-date.model { width: 140px; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track { flex: 1; display: flex; height: 12px; background: #f5f7fa; border-radius: 6px; overflow: hidden; }
.bar-fill { height: 100%; }
.bar-fill.in { background: linear-gradient(90deg, #667eea, #8b9cf5); border-radius: 6px 0 0 6px; }
.bar-fill.out { background: linear-gradient(90deg, #764ba2, #9d78c9); border-radius: 0 6px 6px 0; }
.bar-val { width: 56px; font-size: 12px; color: #606266; flex-shrink: 0; }
.empty-tip { text-align: center; color: #909399; padding: 32px 0; line-height: 1.8; font-size: 13px; }
</style>
