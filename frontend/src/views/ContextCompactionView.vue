<template>
  <div class="cc-mode">
    <!-- 页头: 与「调用链」互补 —— 那边看一次请求花了什么, 这边看什么被压掉了、哪次没压成 -->
    <div class="cc-toolbar">
      <div class="tb-title">
        压缩记录
        <span class="tb-sub">上下文压缩时间线 · 触发阈值 / 前后 token / 摘要字数 / 降级原因</span>
      </div>
      <div class="tb-actions">
        <el-select v-if="authStore.isAdmin" v-model="filterUserId" size="small" style="width:112px" @change="onFilterChange">
          <el-option label="仅自己" value="self" />
          <el-option label="全部用户" value="all" />
        </el-select>
        <el-select v-model="filterEvent" size="small" style="width:120px" @change="onFilterChange">
          <el-option v-for="o in EVENT_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input v-model="filterSession" size="small" style="width:150px" placeholder="会话 ID"
                  clearable @keyup.enter="onFilterChange" @clear="onFilterChange" />
        <el-button size="small" @click="onFilterChange">查询</el-button>
        <el-button size="small" @click="load" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- 指标条: 全部由当前页样本算出, 标签写清"本页", 不冒充全量 -->
    <div class="metric-row" v-if="rows.length">
      <div class="metric">
        <span class="m-label">本页记录</span>
        <span class="m-value">{{ rows.length }}</span>
      </div>
      <div class="metric">
        <span class="m-label">本页压缩成功</span>
        <span class="m-value">{{ compactedCount }}</span>
      </div>
      <div class="metric">
        <span class="m-label">本页降级</span>
        <span class="m-value" :class="{ bad: degradedCount > 0 }">{{ degradedCount }}</span>
      </div>
      <div class="metric">
        <span class="m-label">本页节省 token</span>
        <span class="m-value">{{ fmtTokens(savedTokens) }}</span>
      </div>
    </div>

    <div class="cc-table" v-loading="loading">
      <div class="cc-head">
        <span>时间</span>
        <span>事件</span>
        <span>会话</span>
        <span class="ta-r">阈值</span>
        <span class="ta-r">压缩前 → 后</span>
        <span class="ta-r">压 / 留</span>
        <span class="ta-r">摘要</span>
        <span class="ta-r">耗时</span>
        <span class="cc-note-h">说明</span>
      </div>

      <template v-for="r in rows" :key="r.id">
        <div class="cc-row" :class="{ open: expanded.has(r.id) }" @click="toggle(r.id)">
          <span class="cc-time">{{ formatTime(r.createdAt) }}</span>
          <span class="cc-evt" :class="'evt-' + (r.eventType || '')">{{ r.eventLabel || r.eventType }}</span>
          <span class="cc-sess mono" :title="r.sessionId || '会话已删除'">
            {{ r.sessionId ? shortId(r.sessionId) : '已删除' }}
          </span>
          <span class="cc-num">{{ fmtTokens(r.triggerTokens) || '—' }}</span>
          <span class="cc-num">
            <template v-if="r.beforeTokens != null">
              {{ fmtTokens(r.beforeTokens) }}<i class="cc-sep">→</i>{{ fmtTokens(r.afterTokens) }}
            </template>
            <template v-else>—</template>
          </span>
          <span class="cc-num">{{ r.summarizedCount != null ? r.summarizedCount + ' / ' + (r.keptCount ?? '—') : '—' }}</span>
          <span class="cc-num">{{ r.summaryChars != null ? r.summaryChars + ' 字' : '—' }}</span>
          <span class="cc-num">{{ fmtDur(r.durationMs) || '—' }}</span>
          <span class="cc-note" :title="r.reason || ''">{{ r.reason || '—' }}</span>
        </div>

        <!-- 展开区: 列表只放得下一行摘要, 边界/模型/操作人这些排查时才需要的字段放这里 -->
        <div v-if="expanded.has(r.id)" class="cc-detail">
          <div class="cd-row" v-if="r.reason">
            <span class="cd-k">说明</span>
            <p class="cd-v">{{ r.reason }}</p>
          </div>
          <div class="cd-row">
            <span class="cd-k">阈值 / 模型</span>
            <span class="cd-v mono">
              {{ fmtTokens(r.triggerTokens) || '—' }}
              <i class="cc-sep">·</i>{{ r.model || '未调用模型' }}
            </span>
          </div>
          <div class="cd-row">
            <span class="cd-k">边界 seq</span>
            <span class="cd-v mono">{{ r.boundarySeq ?? '—' }}</span>
          </div>
          <div class="cd-row" v-if="r.sessionId">
            <span class="cd-k">会话 ID</span>
            <span class="cd-v mono">{{ r.sessionId }}</span>
          </div>
          <div class="cd-row" v-if="authStore.isAdmin && r.userId">
            <span class="cd-k">用户</span>
            <span class="cd-v mono">{{ shortId(r.userId) }}</span>
          </div>
        </div>
      </template>

      <div v-if="!loading && !rows.length" class="empty-tip">
        暂无压缩记录<br />
        会话历史超过触发阈值、被压缩或降级时, 这里会留下一条
      </div>
    </div>

    <div class="pager" v-if="total > 0">
      <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="total"
                     :page-sizes="[10, 20, 50, 100]" v-model:page-size="pageSize"
                     v-model:current-page="page" @current-change="load" @size-change="onSizeChange" />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getContextCompactions, extractMessage } from '@/api'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const expanded = ref(new Set())

const filterUserId = ref('self')
const filterEvent = ref('all')
const filterSession = ref('')

/**
 * 事件清单与文案的唯一清单。
 * <p>后端 {@code CompactionEvent} 是权威取值, 这里只负责下拉顺序与短名 ——
 * 行内展示直接用后端返回的 eventLabel, 所以新增事件时这里漏加也不会显示成英文。
 */
const EVENT_OPTIONS = [
  { value: 'all', label: '全部事件' },
  { value: 'compacted', label: '压缩成功' },
  { value: 'degraded', label: '降级窗口' },
  { value: 'invalidated', label: '摘要作废' },
  { value: 'cleared', label: '用户清除' },
  { value: 'edited', label: '用户改写' },
]

const compactedCount = computed(() => rows.value.filter((r) => r.eventType === 'compacted').length)
const degradedCount = computed(() => rows.value.filter((r) => r.eventType === 'degraded').length)
/** 本页成功压缩省下的 token; 降级/作废没有 afterTokens, 不参与 */
const savedTokens = computed(() =>
  rows.value.reduce((sum, r) => sum + Math.max(0, (r.beforeTokens || 0) - (r.afterTokens || 0)), 0))

function onSizeChange() {
  page.value = 1
  load()
}

/** 筛选变化必须先回第 1 页, 否则原页码可能已越界, 表现为"一切换就空白" */
function onFilterChange() {
  page.value = 1
  load()
}

function toggle(id) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

async function load() {
  loading.value = true
  try {
    const data = await getContextCompactions({
      page: page.value - 1,
      size: pageSize.value,
      sessionId: filterSession.value.trim() || undefined,
      eventType: filterEvent.value === 'all' ? undefined : filterEvent.value,
      userId: authStore.isAdmin && filterUserId.value === 'all' ? 'all' : undefined,
    })
    rows.value = data.content || []
    total.value = data.totalElements || 0
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载压缩记录失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function formatTime(s) {
  if (!s) return '—'
  // 后端 LocalDateTime 序列化为 2026-11-10T14:32:01; 列表里不需要秒后面的小数
  return String(s).replace('T', ' ').slice(0, 19)
}

function shortId(id) {
  return id && id.length > 8 ? id.slice(0, 8) : (id || '—')
}

/** token 用 k 缩写: 这一列只用来比量级, 精确值在展开区 */
function fmtTokens(n) {
  const v = Number(n)
  if (!v) return ''
  return v >= 1000 ? (v / 1000).toFixed(1) + 'k' : String(v)
}

function fmtDur(ms) {
  const v = Number(ms)
  if (!v) return ''
  return v >= 1000 ? (v / 1000).toFixed(1) + 's' : v + 'ms'
}
</script>

<style scoped>
/* 与调用链页同一布局: 页头 + 指标条 + 列表 + 分页四段竖排, 只有列表滚 */
.cc-mode { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 18px 24px 16px; }

.cc-toolbar { flex: none; display: flex; align-items: center; gap: 16px; margin-bottom: 14px; }
.tb-title { display: flex; align-items: baseline; gap: 10px; font-size: 15px; font-weight: 600; color: var(--dcdr-text-1); }
.tb-sub { font-size: 11.5px; font-weight: 400; color: var(--dcdr-text-3); }
.tb-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }

.metric-row {
  flex: none; display: flex; gap: 10px; margin-bottom: 12px;
}
.metric {
  flex: 1; display: flex; flex-direction: column; gap: 4px; padding: 10px 14px;
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 10px;
}
.m-label { font-size: 10.5px; color: var(--dcdr-text-3); }
.m-value { font-size: 18px; font-weight: 600; color: var(--dcdr-text-1); font-family: var(--dcdr-font-mono); }
.m-value.bad { color: var(--dcdr-warning); }

.cc-table {
  flex: 1; min-height: 0; overflow-y: auto;
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 12px;
}

.cc-head, .cc-row {
  display: grid;
  grid-template-columns: 132px 76px 90px 60px 122px 62px 68px 62px minmax(120px, 1fr);
  align-items: center; gap: 8px; padding: 0 14px;
}
.cc-head {
  position: sticky; top: 0; z-index: 1; height: 34px;
  font-size: 10.5px; color: var(--dcdr-text-3);
  background: var(--dcdr-bg-subtle); border-bottom: 1px solid var(--dcdr-border);
}
.cc-note-h { text-align: left; }
.ta-r { text-align: right; }

.cc-row {
  height: 40px; font-size: 12px; color: var(--dcdr-text-2); cursor: pointer;
  border-bottom: 1px solid var(--dcdr-border);
}
.cc-row:hover { background: var(--dcdr-hover); }
.cc-row.open { background: var(--dcdr-hover); }
.cc-time { font-size: 11px; color: var(--dcdr-text-3); font-family: var(--dcdr-font-mono); }
.cc-sess { font-size: 11px; color: var(--dcdr-text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cc-num { text-align: right; font-family: var(--dcdr-font-mono); font-size: 11.5px; }
.cc-sep { font-style: normal; color: var(--dcdr-text-3); margin: 0 3px; }
.cc-note { font-size: 11px; color: var(--dcdr-text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 事件徽标: 成功不标色(它是常态), 只有"该压没压成"和"摘要被作废"需要抓眼 */
.cc-evt {
  display: inline-flex; align-items: center; justify-content: center; height: 20px; padding: 0 7px;
  font-size: 10.5px; border-radius: 999px; border: 1px solid var(--dcdr-border);
  color: var(--dcdr-text-2); white-space: nowrap;
}
.cc-evt.evt-compacted { color: var(--dcdr-accent-soft); border-color: var(--dcdr-accent-line); }
.cc-evt.evt-degraded { color: var(--dcdr-warning); border-color: var(--dcdr-warning); }
.cc-evt.evt-invalidated { color: var(--dcdr-danger); border-color: var(--dcdr-danger); }

.cc-detail {
  padding: 10px 14px 12px; border-bottom: 1px solid var(--dcdr-border);
  background: var(--dcdr-bg-subtle);
}
.cd-row { display: flex; gap: 10px; padding: 3px 0; font-size: 11.5px; }
.cd-k { flex: none; width: 88px; color: var(--dcdr-text-3); }
.cd-v { flex: 1; min-width: 0; margin: 0; color: var(--dcdr-text-2); line-height: 1.6; word-break: break-all; }

.empty-tip {
  padding: 48px 0; text-align: center; font-size: 12px; line-height: 2; color: var(--dcdr-text-3);
}
.pager { flex: none; display: flex; justify-content: flex-end; padding-top: 12px; }
</style>
