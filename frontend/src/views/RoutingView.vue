<template>
  <div class="routing-mode">
    <div class="routing-header">
      <h2>智能路由记录</h2>
      <p class="subtitle">查看 V3.0 智能路由层对每条用户消息的判断结果, 用于分析路由准确率与调优置信度阈值</p>
    </div>

    <div class="toolbar">
      <el-button size="small" @click="loadLogs" :loading="loading">刷新</el-button>
      <el-select v-if="authStore.isAdmin" v-model="filterUserId" size="small" style="width:160px" @change="loadLogs">
        <el-option label="仅自己" value="" />
        <el-option label="全部用户" value="all" />
      </el-select>
      <el-select v-model="filterType" size="small" style="width:140px" @change="loadLogs" placeholder="路由类型">
        <el-option label="全部类型" value="" />
        <el-option label="工作流" value="workflow" />
        <el-option label="Agent" value="agent" />
        <el-option label="普通对话" value="chat" />
      </el-select>
      <div class="stat-block" v-if="logs.length">
        <span class="stat-item">共 {{ total }} 条</span>
        <span class="stat-item success">高置信 {{ highConfCount }} 条</span>
        <span class="stat-item warn">低置信 {{ lowConfCount }} 条</span>
      </div>
    </div>

    <div class="log-list" v-loading="loading">
      <div v-for="log in filteredLogs" :key="log.id" class="log-card" :class="{'low-conf': !log.highConfidence}">
        <div class="log-head">
          <el-tag size="small" :type="routeTypeTag(log.routeType)">{{ routeTypeLabel(log.routeType) }}</el-tag>
          <span v-if="log.targetName" class="log-target">{{ log.targetName || log.targetId }}</span>
          <el-tag v-if="log.confidence !== null && log.confidence !== undefined" size="small"
                  :type="log.highConfidence ? 'success' : 'warning'">
            置信度 {{ formatConf(log.confidence) }}
          </el-tag>
          <span class="log-time">{{ formatTime(log.createdAt) }}</span>
        </div>
        <div class="log-message">
          <span class="rl">用户消息:</span> {{ log.userMessage }}
        </div>
        <div class="log-reason" v-if="log.reason">
          <span class="rl">判断依据:</span> {{ log.reason }}
        </div>
      </div>
      <div v-if="!loading && !filteredLogs.length" class="empty-tip">
        暂无路由记录<br />
        在对话页面直接输入需求 (不手动选 Agent / 工作流), 系统会自动判断路由并记录在此
      </div>
    </div>

    <div class="pager" v-if="total > pageSize">
      <el-pagination background layout="prev, pager, next" :total="total"
                     :page-size="pageSize" v-model:current-page="page" @current-change="loadLogs" />
    </div>
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
const pageSize = 20
const filterUserId = ref('')
const filterType = ref('')

const filteredLogs = computed(() => {
  if (!filterType.value) return logs.value
  return logs.value.filter(l => l.routeType === filterType.value)
})

const highConfCount = computed(() => logs.value.filter(l => l.highConfidence).length)
const lowConfCount = computed(() => logs.value.filter(l => !l.highConfidence).length)

function routeTypeLabel(t) {
  return ({ workflow: '工作流', agent: 'Agent', chat: '普通对话' })[t] || t
}
function routeTypeTag(t) {
  if (t === 'workflow') return 'success'
  if (t === 'agent') return 'warning'
  return 'info'
}
function formatConf(c) {
  if (c === null || c === undefined) return '-'
  return Number(c).toFixed(2)
}
function formatTime(t) {
  if (!t) return ''
  try { return new Date(t).toLocaleString('zh-CN') } catch { return t }
}

async function loadLogs() {
  loading.value = true
  try {
    const params = {
      page: page.value - 1,
      size: pageSize
    }
    if (authStore.isAdmin && filterUserId.value) params.userId = filterUserId.value
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
.routing-mode { padding: 20px 24px; max-width: 960px; margin: 0 auto; }
.routing-header { margin-bottom: 16px; }
.routing-header h2 { margin: 0 0 4px; font-size: 20px; color: #1d2129; }
.subtitle { margin: 0; font-size: 13px; color: #86909c; }

.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.stat-block { margin-left: auto; display: flex; gap: 14px; font-size: 12px; color: #86909c; }
.stat-item.success { color: #67c23a; }
.stat-item.warn { color: #e6a23c; }

.log-list { display: flex; flex-direction: column; gap: 10px; min-height: 200px; }
.log-card { background: #fff; border: 1px solid #e8e8e8; border-radius: 8px; padding: 12px 14px; transition: box-shadow .2s; }
.log-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.log-card.low-conf { border-left: 3px solid #e6a23c; }
.log-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.log-target { font-size: 13px; font-weight: 500; color: #667eea; }
.log-time { font-size: 11px; color: #a0a4ab; margin-left: auto; }
.log-message { font-size: 13px; color: #1d2129; line-height: 1.6; word-break: break-word; }
.log-message .rl, .log-reason .rl { color: #86909c; font-size: 12px; }
.log-reason { font-size: 12px; color: #606266; margin-top: 6px; line-height: 1.6; }

.empty-tip { text-align: center; color: #a0a4ab; padding: 60px 20px; font-size: 13px; line-height: 1.8; }

.pager { margin-top: 20px; display: flex; justify-content: center; }
</style>
