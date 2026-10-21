<template>
  <div class="monitor-mode">
    <div class="monitor-header">
      <h2>监控与成本</h2>
      <p class="subtitle">工作流执行监控 / LLM token 成本统计 / 配额使用情况 / 回答反馈 (V3.0-beta F-34 / F-39, V3.1 F-48)</p>
    </div>

    <div class="toolbar">
      <el-select v-model="days" size="small" style="width:130px" @change="reloadAll">
        <el-option label="近 7 天" :value="7" />
        <el-option label="近 30 天" :value="30" />
        <el-option label="近 90 天" :value="90" />
      </el-select>
      <el-select v-if="authStore.isAdmin" v-model="scope" size="small" style="width:140px" @change="reloadAll">
        <el-option label="仅自己" value="" />
        <el-option label="全局 (管理员)" value="global" />
      </el-select>
      <el-button size="small" @click="reloadAll" :loading="loading">刷新</el-button>
    </div>

    <!-- V3.1 F-44.2: 顶部按语义分页签, 避免单页纵向堆叠过长 -->
    <el-tabs v-model="activeTab" class="monitor-tabs">
      <el-tab-pane label="执行监控" name="exec">

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
        <div class="s-value">{{ overview && overview.avgDurationMs != null ? (overview.avgDurationMs / 1000).toFixed(1) + 's' : '-' }}</div>
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
          <template #default="{ row }">{{ row.avgDurationMs == null ? '-' : (row.avgDurationMs / 1000).toFixed(1) + 's' }}</template>
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

      </el-tab-pane>

      <!-- ===== V3.1 F-44.2 成本分析报表: 日/周/月趋势 + 模型占比 + Top 用户 + CSV ===== -->
      <el-tab-pane label="成本分析" name="cost">
        <div class="toolbar">
          <el-radio-group v-model="costGranularity" size="small" @change="loadCostReport">
            <el-radio-button label="day">按天</el-radio-button>
            <el-radio-button label="week">按周</el-radio-button>
            <el-radio-button label="month">按月</el-radio-button>
          </el-radio-group>
          <el-date-picker v-model="costRange" type="daterange" size="small" value-format="YYYY-MM-DD"
                          range-separator="~" start-placeholder="开始日期" end-placeholder="结束日期"
                          :clearable="false" style="width: 250px" @change="loadCostReport" />
          <el-button size="small" @click="loadCostReport" :loading="costLoading">刷新</el-button>
          <span class="cost-hint">
            统计窗口 {{ costReport ? `${costReport.from} ~ ${costReport.to}` : '—' }}
          </span>
        </div>

        <div class="stat-cards">
          <div class="stat-card">
            <div class="s-value">{{ costReport ? fmt(costReport.totalTokens) : '-' }}</div>
            <div class="s-label">总 Token</div>
          </div>
          <div class="stat-card">
            <div class="s-value">{{ costReport ? fmt(costReport.inputTokens) : '-' }}</div>
            <div class="s-label">输入 Token</div>
          </div>
          <div class="stat-card">
            <div class="s-value">{{ costReport ? fmt(costReport.outputTokens) : '-' }}</div>
            <div class="s-label">输出 Token</div>
          </div>
          <div class="stat-card">
            <div class="s-value">{{ costReport ? costReport.callCount : '-' }}</div>
            <div class="s-label">调用次数</div>
          </div>
          <div class="stat-card" :class="{ warn: costReport && !costReport.costDataAvailable }">
            <div class="s-value">{{ costReport && costReport.costDataAvailable ? money(costReport.totalCost) : '—' }}</div>
            <div class="s-label">金额合计</div>
          </div>
        </div>

        <el-alert v-if="costReport && !costReport.costDataAvailable" type="info" :closable="false" show-icon
                  class="cost-alert"
                  title="未配置模型定价，金额维度不可用"
                  description="在 application.yml 的 agent.v3.cost.pricing 中按实际单价填写后, 新产生的调用才会记录金额(存量记录不追溯)。当前仅按 token 口径统计。" />

        <div class="charts">
          <!-- 消耗趋势 (按所选粒度: 日/周/月) -->
          <div class="chart-card">
            <div class="c-title">
              消耗趋势
              <el-tag size="small" type="info" effect="plain">{{ granularityLabel }}</el-tag>
              <el-button size="small" text type="primary" style="margin-left:auto"
                         @click="doExportCsv('trend')" :loading="costExporting">导出 CSV</el-button>
            </div>
            <div class="bar-chart" v-if="trendRows.length">
              <div v-for="p in trendRows" :key="p.period" class="bar-row">
                <span class="bar-date">{{ p.period }}</span>
                <div class="bar-track">
                  <div class="bar-fill in" :style="{ width: pctTrend(p.inputTokens) }" :title="'输入 ' + fmt(p.inputTokens)"></div>
                  <div class="bar-fill out" :style="{ width: pctTrend(p.outputTokens) }" :title="'输出 ' + fmt(p.outputTokens)"></div>
                </div>
                <span class="bar-val">{{ fmt(p.totalTokens) }}</span>
                <span class="bar-money">{{ money(p.cost) }}</span>
              </div>
            </div>
            <div v-else class="empty-tip">窗口内暂无用量记录</div>
          </div>

          <!-- 模型成本占比 -->
          <div class="chart-card">
            <div class="c-title">
              模型成本占比
              <el-button size="small" text type="primary" style="margin-left:auto"
                         @click="doExportCsv('model')" :loading="costExporting">导出 CSV</el-button>
            </div>
            <div class="bar-chart" v-if="(costReport?.byModel || []).length">
              <div v-for="m in costReport.byModel" :key="m.model" class="bar-row">
                <span class="bar-date model" :title="m.model">{{ m.model }}</span>
                <div class="bar-track">
                  <div class="bar-fill in" :style="{ width: pctCostModel(m.inputTokens) }"></div>
                  <div class="bar-fill out" :style="{ width: pctCostModel(m.outputTokens) }"></div>
                </div>
                <span class="bar-val">{{ fmt(m.totalTokens) }}</span>
                <span class="bar-money">{{ ratioText(m) }}</span>
              </div>
            </div>
            <div v-else class="empty-tip">窗口内暂无用量记录</div>
          </div>
        </div>

        <!-- Top 用户 (仅管理员全局视图) -->
        <div class="chart-card" v-if="canSeeTopUsers">
          <div class="c-title">
            Top 用户
            <span class="c-hint">按 token 消耗降序; 需要「全局」视角才会展示</span>
            <el-button size="small" text type="primary" style="margin-left:auto"
                       @click="doExportCsv('user')" :loading="costExporting">导出 CSV</el-button>
          </div>
          <el-table :data="costReport?.byUser || []" size="small">
            <el-table-column label="#" type="index" width="50" />
            <el-table-column label="用户" min-width="150">
              <template #default="{ row }">{{ row.username || row.userId || '—' }}</template>
            </el-table-column>
            <el-table-column prop="calls" label="调用" width="90" />
            <el-table-column label="Token" width="110">
              <template #default="{ row }">{{ fmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column label="金额(元)" width="120">
              <template #default="{ row }">{{ money(row.cost) }}</template>
            </el-table-column>
          </el-table>
          <div v-if="!(costReport?.byUser || []).length" class="empty-tip">窗口内暂无用户维度数据</div>
        </div>

        <!-- 调用来源 -->
        <div class="chart-card">
          <div class="c-title">
            调用来源
            <el-button size="small" text type="primary" style="margin-left:auto"
                       @click="doExportCsv('source')" :loading="costExporting">导出 CSV</el-button>
          </div>
          <el-table :data="costReport?.bySource || []" size="small">
            <el-table-column prop="sourceLabel" label="来源" width="140" />
            <el-table-column prop="calls" label="调用" width="100" />
            <el-table-column label="Token" width="130">
              <template #default="{ row }">{{ fmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column label="金额(元)" width="130">
              <template #default="{ row }">{{ money(row.cost) }}</template>
            </el-table-column>
          </el-table>
          <div v-if="!(costReport?.bySource || []).length" class="empty-tip">窗口内暂无来源维度数据</div>
        </div>
      </el-tab-pane>

      <!-- ===== V3.1 F-48 回答反馈 (点赞/点踩收集): 好评率趋势 + 差评归因 ===== -->
      <el-tab-pane label="回答反馈" name="feedback">
    <div class="chart-card">
      <div class="c-title">
        回答反馈
        <el-tag v-if="stats?.global" size="small" type="warning" effect="plain">全局</el-tag>
        <el-tag v-else size="small" type="info" effect="plain">仅自己</el-tag>
        <span class="c-hint">好评率低于 80% 标红，先看"点踩原因分布"定位问题类型，再看"按模型"定位问题链路</span>
      </div>

      <div class="stat-cards">
        <div class="stat-card">
          <div class="s-value">{{ stats ? stats.total : '-' }}</div>
          <div class="s-label">反馈总数</div>
        </div>
        <div class="stat-card success">
          <div class="s-value">{{ stats ? stats.up : '-' }}</div>
          <div class="s-label">点赞</div>
        </div>
        <div class="stat-card danger">
          <div class="s-value">{{ stats ? stats.down : '-' }}</div>
          <div class="s-label">点踩</div>
        </div>
        <div class="stat-card" :class="{ warn: stats && stats.total > 0 && stats.satisfaction < 0.8 }">
          <div class="s-value">{{ stats ? (stats.satisfaction * 100).toFixed(1) + '%' : '-' }}</div>
          <div class="s-label">好评率</div>
        </div>
      </div>

      <div class="charts" style="margin-top: 16px">
        <!-- 按天反馈趋势 (绿=点赞 / 红=点踩) -->
        <div>
          <div class="c-sub">按天反馈趋势</div>
          <div class="bar-chart" v-if="feedbackDayRows.length">
            <div v-for="d in feedbackDayRows" :key="d.date" class="bar-row">
              <span class="bar-date">{{ d.date.slice(5) }}</span>
              <div class="bar-track">
                <div class="bar-fill up" :style="{ width: pctFb(d.up) }" :title="'点赞 ' + d.up"></div>
                <div class="bar-fill down" :style="{ width: pctFb(d.down) }" :title="'点踩 ' + d.down"></div>
              </div>
              <span class="bar-val">{{ d.total }}</span>
            </div>
          </div>
          <div v-else class="empty-tip">窗口内暂无反馈</div>
        </div>

        <!-- 点踩原因分布 -->
        <div>
          <div class="c-sub">点踩原因分布</div>
          <div class="reason-list" v-if="stats?.reasonCounts?.length">
            <div v-for="r in stats.reasonCounts" :key="r.code" class="reason-row">
              <span class="reason-label" :title="r.code">{{ r.label }}</span>
              <div class="reason-track">
                <div class="reason-fill" :style="{ width: pctReason(r.count) }"></div>
              </div>
              <span class="reason-count">{{ r.count }}</span>
            </div>
          </div>
          <div v-else class="empty-tip">窗口内暂无点踩记录</div>
        </div>
      </div>

      <div class="c-sub" style="margin-top: 16px">按模型好评率</div>
      <el-table :data="stats?.modelStats || []" size="small">
        <el-table-column prop="model" label="模型" min-width="170" />
        <el-table-column prop="total" label="反馈数" width="90" />
        <el-table-column prop="up" label="点赞" width="80" />
        <el-table-column prop="down" label="点踩" width="80" />
        <el-table-column label="好评率" width="100">
          <template #default="{ row }">
            <span :class="{ 'fb-bad': row.satisfaction < 0.8 }">{{ (row.satisfaction * 100).toFixed(0) }}%</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!stats?.modelStats?.length" class="empty-tip">窗口内暂无反馈</div>
    </div>

    <!-- 反馈明细: 差评列表就是待处理的坏 case 清单 -->
    <div class="chart-card">
      <div class="c-title">
        反馈明细
        <el-select v-model="fbRating" size="small" style="width: 120px" @change="onFbFilterChange">
          <el-option label="全部" value="" />
          <el-option label="仅点赞" value="up" />
          <el-option label="仅点踩" value="down" />
        </el-select>
      </div>
      <el-table :data="fbList?.content || []" size="small">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="评价" width="76">
          <template #default="{ row }">
            <el-tag size="small" :type="row.rating === 'up' ? 'success' : 'danger'" effect="plain">
              {{ row.rating === 'up' ? '👍 赞' : '👎 踩' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="140" />
        <el-table-column label="原因" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="c in (row.reasonTags || [])" :key="c" size="small" type="info" effect="plain" class="fb-tag">
              {{ tagLabel(c) }}
            </el-tag>
            <span v-if="!(row.reasonTags || []).length" class="fb-dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="补充说明" min-width="220">
          <template #default="{ row }">
            <span v-if="row.comment" class="fb-comment">{{ row.comment }}</span>
            <span v-else class="fb-dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="上下文" width="180">
          <template #default="{ row }">
            <span class="fb-dim">
              <template v-if="row.webSearch">联网 · </template>
              {{ row.sourceCount }} 来源 · {{ row.messageChars }} 字
            </span>
          </template>
        </el-table-column>
        <el-table-column label="定位" width="150">
          <template #default="{ row }">
            <span class="fb-dim" :title="row.sessionId + ' #seq ' + row.messageSeq">
              {{ shortId(row.sessionId) }}#{{ row.messageSeq }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!fbList?.content?.length" class="empty-tip">窗口内暂无反馈记录（在对话里点👍/👎后出现）</div>
      <el-pagination v-if="(fbList?.totalElements || 0) > 0" small background
                     layout="prev, pager, next, total" :total="fbList.totalElements"
                     :page-size="fbPageSize" :current-page="fbPage + 1" @current-change="onFbPage"
                     style="margin-top: 12px; justify-content: center" />
    </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getWorkflowMonitor, getCostStats, getQuotaUsage, getLlmMonitor, extractMessage,
  getCostReport, exportCostCsv,
  getFeedbackStats, listFeedback, getFeedbackReasonTags
} from '@/api'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const loading = ref(false)
const days = ref(30)
const scope = ref('')
const overview = ref(null)
const cost = ref(null)
const quota = ref(null)
const llm = ref(null)

// ===== V3.1 F-48 回答反馈 =====
const stats = ref(null)
const fbList = ref(null)
const fbRating = ref('')
const fbPage = ref(0)
const fbPageSize = ref(20)
// 原因标签 code → 展示名 (由后端下发, 避免前后端文案不一致)
const tagMap = ref({})

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

// ===== V3.1 F-48 回答反馈 =====
// 按天趋势: 后端已按窗口补齐无数据的日期, 这里倒序让最近的在最上面
const feedbackDayRows = computed(() => (stats.value?.dayStats || []).slice().reverse())
const maxFbDay = computed(() => Math.max(1, ...feedbackDayRows.value.map(d => d.total)))
const maxReason = computed(() => Math.max(1, ...(stats.value?.reasonCounts || []).map(r => r.count)))
const pctFb = (v) => Math.min(90, (v / maxFbDay.value) * 90) + '%'
const pctReason = (v) => Math.min(96, (v / maxReason.value) * 96) + '%'
const tagLabel = (code) => tagMap.value[code] || code
const shortId = (id) => (id ? (id.length > 14 ? id.slice(0, 12) + '…' : id) : '-')

/** 反馈明细 (分页 + 评价类型过滤); 失败静默, 不影响其余监控数据展示 */
async function loadFeedbackList() {
  const params = { page: fbPage.value, size: fbPageSize.value }
  if (fbRating.value) params.rating = fbRating.value
  if (authStore.isAdmin && scope.value) params.scope = scope.value
  try {
    fbList.value = await listFeedback(params)
  } catch (e) {
    fbList.value = null
  }
}

function onFbFilterChange() {
  fbPage.value = 0
  loadFeedbackList()
}

/** el-pagination 是 1 起, 后端分页从 0 起 */
function onFbPage(p) {
  fbPage.value = p - 1
  loadFeedbackList()
}

async function loadAll() {
  loading.value = true
  const params = { days: days.value }
  if (authStore.isAdmin && scope.value) params.scope = scope.value
  try {
    const [wo, cs, qu, lm, st, tags] = await Promise.all([
      getWorkflowMonitor(params),
      getCostStats(params),
      getQuotaUsage(),
      getLlmMonitor(),
      getFeedbackStats(params),
      getFeedbackReasonTags()
    ])
    overview.value = wo
    cost.value = cs
    quota.value = qu
    llm.value = lm
    stats.value = st
    tagMap.value = Object.fromEntries((tags || []).map(t => [t.code, t.label]))
    await loadFeedbackList()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载监控数据失败')
  } finally {
    loading.value = false
  }
}

// ===== V3.1 F-44.2 成本分析 =====
/** 页签: exec 执行监控 / cost 成本分析 / feedback 回答反馈 */
const activeTab = ref('exec')
const costGranularity = ref('day')
const costRange = ref(defaultCostRange())
const costReport = ref(null)
const costLoading = ref(false)
const costExporting = ref(false)

const trendRows = computed(() => (costReport.value?.trend || []).slice().reverse())
const granularityLabel = computed(
  () => ({ day: '按天', week: '按周', month: '按月' }[costGranularity.value] || '按天')
)
/** Top 用户是跨用户数据, 只有管理员的「全局」视角才展示 */
const canSeeTopUsers = computed(() => authStore.isAdmin && scope.value === 'global')

const maxTrendTokens = computed(() => Math.max(1, ...trendRows.value.map(p => p.totalTokens)))
const maxCostModelTokens = computed(
  () => Math.max(1, ...(costReport.value?.byModel || []).map(m => m.totalTokens))
)
const pctTrend = (v) => Math.min(90, (v / maxTrendTokens.value) * 90) + '%'
const pctCostModel = (v) => Math.min(90, (v / maxCostModelTokens.value) * 90) + '%'

/** 金额展示: 未配置定价时统一降级为 "—", 不把"没有定价"显示成"零成本" */
function money(v) {
  if (!costReport.value?.costDataAvailable) return '—'
  return '¥' + Number(v || 0).toFixed(4)
}

/** 模型行占比文案: 有金额时给"金额占比 · 金额", 否则退化为 token 占比 */
function ratioText(m) {
  if (costReport.value?.costDataAvailable && m.costRatio != null) {
    return `${(m.costRatio * 100).toFixed(1)}% · ${money(m.cost)}`
  }
  return `${(m.tokenRatio * 100).toFixed(1)}%`
}

/** 默认统计窗口: 近 30 天 (与后端缺省一致) */
function defaultCostRange() {
  const to = new Date()
  const from = new Date(to.getTime() - 29 * 24 * 3600 * 1000)
  return [fmtDate(from), fmtDate(to)]
}

/** yyyy-MM-dd (本地时区)。注意不能用 toISOString: 后端字段是 LocalDateTime, 带 Z 会反序列化失败 */
function fmtDate(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 组装后端参数: 日期区间转 LocalDateTime, 止日 +1 天以构成左闭右开区间 */
function costParams() {
  const [from, to] = costRange.value || []
  const params = { granularity: costGranularity.value, topN: 10 }
  if (from) params.from = `${from}T00:00:00`
  if (to) {
    const end = new Date(`${to}T00:00:00`)
    end.setDate(end.getDate() + 1)
    params.to = `${fmtDate(end)}T00:00:00`
  }
  if (authStore.isAdmin && scope.value) params.scope = scope.value
  return params
}

async function loadCostReport() {
  costLoading.value = true
  try {
    costReport.value = await getCostReport(costParams())
  } catch (e) {
    costReport.value = null
    ElMessage.error(extractMessage(e) || '加载成本报表失败')
  } finally {
    costLoading.value = false
  }
}

/** 导出 CSV: 后端返回带 BOM 的二进制流, 前端用 Blob 触发下载(不能走 unwrap) */
async function doExportCsv(dimension) {
  costExporting.value = true
  try {
    const blob = await exportCostCsv({ ...costParams(), dimension })
    const url = URL.createObjectURL(new Blob([blob], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `cost-${dimension}-${fmtDate(new Date())}.csv`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '导出失败')
  } finally {
    costExporting.value = false
  }
}

/** 顶部筛选变化时刷新两个页签的数据(执行监控 + 成本分析共用 days/scope) */
function reloadAll() {
  loadAll()
  loadCostReport()
}

onMounted(() => { reloadAll() })
</script>

<style scoped>
.monitor-mode { flex: 1; overflow-y: auto; padding: 24px 28px; background: var(--dcdr-bg-subtle); display: flex; flex-direction: column; gap: 16px; }
.monitor-header h2 { margin: 0 0 4px; font-size: 20px; color: var(--dcdr-text-1); }
.subtitle { margin: 0; font-size: 13px; color: var(--dcdr-text-3); }
.toolbar { display: flex; align-items: center; gap: 10px; }

.quota-card { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 16px 20px; }
.quota-title { font-weight: 600; font-size: 14px; color: var(--dcdr-text-1); margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
.quota-bars { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
.q-label { font-size: 13px; color: var(--dcdr-text-2); margin-bottom: 4px; }
.q-text { font-size: 12px; color: var(--dcdr-text-3); margin-top: 4px; }

.stat-cards { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.stat-card { display: flex; flex-direction: column-reverse; gap: 7px; padding: 15px 16px; background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner); }
.stat-card .s-value { font-family: var(--dcdr-font-mono); font-size: 23px; font-weight: 700; letter-spacing: -0.5px; color: var(--dcdr-text-1); }
.stat-card .s-label { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 0.8px; color: var(--dcdr-text-3); }
.stat-card.success .s-value { color: var(--dcdr-success); }
.stat-card.danger .s-value { color: var(--dcdr-danger); }
.stat-card.warn .s-value { color: var(--dcdr-warning); }

.charts { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.chart-card { background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner); padding: 16px 18px; }
.c-title { font-weight: 600; font-size: 14px; color: var(--dcdr-text-1); margin-bottom: 12px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.c-sub { font-size: 13px; font-weight: 600; color: var(--dcdr-text-2); margin-bottom: 10px; }
.c-hint { font-weight: 400; font-size: 12px; color: #a0a6b5; }
.bar-chart { display: flex; flex-direction: column; gap: 8px; max-height: 280px; overflow-y: auto; }
.bar-row { display: flex; align-items: center; gap: 8px; }
.bar-date { width: 52px; font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-3); text-align: right; flex-shrink: 0; }
.bar-date.model { width: 140px; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track { flex: 1; display: flex; height: 8px; background: var(--dcdr-bg-subtle); border-radius: 6px; overflow: hidden; }
.bar-fill { height: 100%; }
.bar-fill.in { background: linear-gradient(90deg, var(--dcdr-accent), #8b9cf5); border-radius: 6px 0 0 6px; }
.bar-fill.out { background: linear-gradient(90deg, var(--dcdr-accent-2), #9d78c9); border-radius: 0 6px 6px 0; }
.bar-val { width: 56px; font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-2); flex-shrink: 0; }
.empty-tip { text-align: center; color: var(--dcdr-text-3); padding: 32px 0; line-height: 1.8; font-size: 13px; }

/* ===== V3.1 F-48 回答反馈看板 ===== */
.bar-fill.up { background: linear-gradient(90deg, var(--dcdr-success), #95d475); border-radius: 6px 0 0 6px; }
.bar-fill.down { background: linear-gradient(90deg, var(--dcdr-danger), #f89898); border-radius: 0 6px 6px 0; }
.reason-list { display: flex; flex-direction: column; gap: 8px; max-height: 280px; overflow-y: auto; }
.reason-row { display: flex; align-items: center; gap: 8px; }
.reason-label { width: 96px; font-size: 12px; color: var(--dcdr-text-2); text-align: right; flex-shrink: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.reason-track { flex: 1; height: 12px; background: var(--dcdr-bg-subtle); border-radius: 6px; overflow: hidden; }
.reason-fill { height: 100%; background: linear-gradient(90deg, var(--dcdr-warning), #f3b45f); border-radius: 6px; }
.reason-count { width: 40px; font-size: 12px; color: var(--dcdr-text-2); flex-shrink: 0; }
.fb-bad { color: var(--dcdr-danger); font-weight: 600; }
.fb-tag { margin-right: 4px; margin-bottom: 2px; }
.fb-comment { font-size: 12.5px; color: var(--dcdr-text-2); word-break: break-word; }
.fb-dim { font-size: 12px; color: var(--dcdr-text-3); }

/* ===== V3.1 F-44.2 成本分析页签 ===== */
.monitor-tabs { margin-top: 4px; }
/* 内层页签与管理后台统一: 胶囊, 不用 Element Plus 默认下划线 */
.monitor-tabs :deep(.el-tabs__header) { margin: 0; }
.monitor-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }
.monitor-tabs :deep(.el-tabs__active-bar) { display: none; }
.monitor-tabs :deep(.el-tabs__item) {
  height: 26px; line-height: 26px; margin: 6px 3px; padding: 0 12px !important;
  font-size: 12px; color: var(--dcdr-text-3); border-radius: 999px;
  transition: background 0.15s, color 0.15s;
}
.monitor-tabs :deep(.el-tabs__item:hover) { color: var(--dcdr-text-1); }
.monitor-tabs :deep(.el-tabs__item.is-active) {
  color: var(--dcdr-accent-soft); background: var(--dcdr-accent-wash);
  box-shadow: inset 0 0 0 1px var(--dcdr-accent-line);
}
.monitor-tabs :deep(.el-tabs__content) { padding-top: 14px; }
.cost-hint { font-size: 12px; color: var(--dcdr-text-3); }
.cost-alert { margin-top: 4px; }
.bar-money { width: 92px; font-size: 12px; color: var(--dcdr-text-3); text-align: right; flex-shrink: 0; }
</style>
