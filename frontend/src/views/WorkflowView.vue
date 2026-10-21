<template>
  <div class="wf-mode">
    <!-- 左侧: 工作流列表 -->
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed}">
      <div class="sidebar-header">
        <button class="new-btn gradient-bg" @click="openCreateDialog">+ 新建工作流</button>
        <button class="collapse-btn" @click="sidebarCollapsed = true" title="收起">«</button>
      </div>
      <div class="wf-list">
        <div v-for="w in workflows" :key="w.id"
             class="wf-item" :class="{active: w.id === currentWfId}"
             @click="selectWf(w.id)">
          <div class="wf-icon">{{ modeIcon(w.mode) }}</div>
          <div class="wf-info">
            <div class="wf-name">
              {{ w.name }}
              <el-tag v-if="!w.enabled" size="small" type="info" class="tag">已停用</el-tag>
            </div>
            <div class="wf-meta">
              <span>{{ modeLabel(w.mode) }}</span>
              <span>·</span>
              <span v-if="w.mode === 'dag'">{{ (w.dag?.nodes || []).length }} 节点</span>
              <span v-else>{{ (w.steps || []).length }} 步</span>
            </div>
          </div>
        </div>
        <div v-if="!workflows.length && !loading" class="empty-tip">
          暂无工作流<br/>点击上方按钮创建
        </div>
      </div>
    </aside>
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false">»</button>

    <!-- 右侧: 工作流详情 / 执行 -->
    <div class="wf-main">
      <template v-if="currentWf">
        <div class="wf-toolbar">
          <div class="wf-toolbar-left">
            <h2>{{ currentWf.name }}</h2>
            <el-tag size="small" :type="modeTagType(currentWf.mode)">{{ modeLabel(currentWf.mode) }}</el-tag>
            <el-tag size="small" v-if="currentWf.mode === 'dag'">{{ (currentWf.dag?.nodes || []).length }} 节点</el-tag>
            <el-tag size="small" v-else>{{ (currentWf.steps || []).length }} 步</el-tag>
            <span class="wf-desc-full" v-if="currentWf.description" :title="currentWf.description">{{ currentWf.description }}</span>
          </div>
          <div class="wf-toolbar-right">
            <el-button size="small" @click="onEditClick">编辑</el-button>
            <el-button type="danger" size="small" @click="removeWf">删除</el-button>
          </div>
        </div>

        <el-tabs v-model="activeTab" class="wf-tabs" @tab-change="onTabChange">
          <!-- 步骤可视化 (DAG 工作流展示节点图概览) -->
          <el-tab-pane label="步骤定义" name="steps">
            <div v-if="currentWf.mode === 'dag'" class="dag-summary">
              <div class="dag-summary-head">
                <div>
                  <b>DAG 节点图</b>
                  <span class="dag-summary-meta">{{ (currentWf.dag?.nodes || []).length }} 节点 · {{ (currentWf.dag?.edges || []).length }} 条边</span>
                </div>
                <el-button size="small" type="primary" @click="openDagEditor">打开 DAG 编辑器</el-button>
              </div>
              <div v-if="(currentWf.dag?.nodes || []).length" class="dag-node-list">
                <div v-for="n in currentWf.dag.nodes" :key="n.id" class="dag-node-card">
                  <span class="dag-node-type" :class="'t-' + n.type">{{ dagNodeTypeLabel(n.type) }}</span>
                  <span class="dag-node-name">{{ n.data?.label || n.id }}</span>
                  <span class="dag-node-sub">{{ dagNodeSub(n) }}</span>
                </div>
              </div>
              <div v-else class="dag-empty">该 DAG 工作流暂无节点定义</div>
              <div v-if="(currentWf.dag?.edges || []).length" class="dag-edge-section">
                <div class="dag-edge-title">连线 ({{ currentWf.dag.edges.length }})</div>
                <div v-for="(e, i) in currentWf.dag.edges" :key="i" class="dag-edge-row">
                  <code>{{ e.source }}</code> <span class="dag-arrow">→</span> <code>{{ e.target }}</code>
                  <el-tag v-if="e.label" size="small" type="warning">{{ e.label }}</el-tag>
                </div>
              </div>
            </div>
            <div v-else class="steps-viz">
              <div v-for="(step, idx) in (currentWf.steps || [])" :key="idx" class="step-card">
                <div class="step-no">{{ idx + 1 }}</div>
                <div class="step-body">
                  <div class="step-head">
                    <span class="step-name">{{ step.name }}</span>
                    <el-tag size="small" type="info">{{ agentName(step.agentId) }}</el-tag>
                    <el-tag v-if="step.condition" size="small" type="warning">条件: {{ step.condition }}</el-tag>
                    <el-tag v-if="step.approval" size="small" type="danger">人工审批</el-tag>
                    <el-tag v-if="step.retry" size="small" type="success">
                      重试×{{ step.retry.maxAttempts || '默认' }}
                    </el-tag>
                  </div>
                  <div class="step-input">{{ step.input || '(无输入)' }}</div>
                </div>
                <div v-if="idx < (currentWf.steps || []).length - 1" class="step-arrow">↓</div>
              </div>
            </div>
          </el-tab-pane>

          <!-- 执行 -->
          <el-tab-pane label="执行" name="run">
            <div class="run-panel">
              <div class="run-input-row">
                <el-input v-model="runInput" type="textarea" :rows="3"
                          placeholder="输入工作流的起始内容 (替换 ${user_message})" />
                <el-button type="primary" :loading="running" @click="runWorkflow" class="run-btn">
                  {{ running ? '执行中...' : '执行工作流' }}
                </el-button>
              </div>

              <!-- 执行结果 -->
              <div v-if="executionResult" class="run-result">
                <div class="run-summary">
                  <el-tag :type="statusTagType(executionResult.status)">{{ statusLabel(executionResult.status) }}</el-tag>
                  <span class="run-duration">总耗时 {{ executionResult.totalDurationMs }}ms</span>
                </div>
                <div v-for="(r, idx) in executionResult.steps" :key="idx" class="step-result">
                  <div class="step-result-head">
                    <span class="step-no">{{ idx + 1 }}</span>
                    <span class="step-name">{{ r.name }}</span>
                    <el-tag size="small" :type="statusTagType(r.status)">{{ statusLabel(r.status) }}</el-tag>
                    <span class="step-duration" v-if="r.durationMs">{{ r.durationMs }}ms</span>
                  </div>
                  <div class="step-result-input" v-if="r.input">
                    <span class="rl">输入:</span> {{ r.input }}
                  </div>
                  <div class="step-result-output" v-if="r.output">
                    <span class="rl">输出:</span>
                    <pre>{{ r.output }}</pre>
                  </div>
                  <div class="step-result-error" v-if="r.error">
                    <span class="rl">错误:</span> {{ r.error }}
                  </div>
                </div>
                <div v-if="executionResult.finalOutput" class="final-output">
                  <div class="fo-label">最终输出</div>
                  <pre>{{ executionResult.finalOutput }}</pre>
                </div>
              </div>
            </div>
          </el-tab-pane>

          <!-- V3.0 F-33 执行历史 (搜索/筛选/分页 + 断点续跑 / 人工审批) -->
          <el-tab-pane label="执行历史" name="history">
            <div class="history-panel">
              <!-- 搜索 + 筛选工具栏 -->
              <div class="history-toolbar">
                <el-input v-model="execFilters.keyword" placeholder="搜索输入内容/错误信息..." clearable
                          size="small" class="exec-search" @keyup.enter="searchExecutions" @clear="searchExecutions" />
                <el-select v-model="execFilters.status" placeholder="状态" clearable size="small"
                           class="exec-filter" @change="searchExecutions">
                  <el-option label="运行中" value="running" />
                  <el-option label="待审批" value="paused" />
                  <el-option label="成功" value="success" />
                  <el-option label="失败" value="failed" />
                  <el-option label="已取消" value="cancelled" />
                </el-select>
                <el-select v-model="execFilters.workflowId" placeholder="工作流" clearable filterable size="small"
                           class="exec-filter-wf" @change="searchExecutions">
                  <el-option v-for="w in workflows.filter(x => !x.isTemplate)" :key="w.id" :label="w.name" :value="w.id" />
                </el-select>
                <el-select v-model="execFilters.triggerType" placeholder="触发方式" clearable size="small"
                           class="exec-filter" @change="searchExecutions">
                  <el-option label="手动" value="manual" />
                  <el-option label="对话" value="chat" />
                  <el-option label="Webhook" value="webhook" />
                  <el-option label="API" value="api" />
                  <el-option label="定时" value="schedule" />
                  <el-option label="事件" value="event" />
                </el-select>
                <el-button size="small" type="primary" @click="searchExecutions">搜索</el-button>
                <el-button size="small" @click="resetExecFilters">重置</el-button>
                <el-button size="small" @click="loadExecutions" :loading="loadingExecs">刷新</el-button>
                <span v-if="execTotal > 0" class="exec-total">共 {{ execTotal }} 条</span>
              </div>
              <div v-if="executions.length" class="exec-list">
                <div v-for="ex in executions" :key="ex.id" class="exec-card">
                  <div class="exec-head">
                    <el-tag size="small" :type="execStatusTagType(ex.status)">{{ execStatusLabel(ex.status) }}</el-tag>
                    <span class="exec-trigger">{{ triggerLabel(ex.triggerType) }}</span>
                    <span class="exec-time">{{ formatTime(ex.startedAt) }}</span>
                    <span class="exec-wf" v-if="ex.workflowName">{{ ex.workflowName }}</span>
                  </div>
                  <div class="exec-input" v-if="ex.input">
                    <span class="rl">输入:</span> {{ ex.input.slice(0, 200) }}{{ ex.input.length > 200 ? '...' : '' }}
                  </div>
                  <div class="exec-error" v-if="ex.error">
                    <span class="rl">错误:</span> {{ ex.error }}
                  </div>
                  <div class="exec-actions">
                    <el-button v-if="ex.status === 'failed'" size="small" type="warning" :loading="ex._busy"
                               @click="resumeExec(ex)">断点续跑</el-button>
                    <el-button v-if="ex.status === 'paused'" size="small" type="success" :loading="ex._busy"
                               @click="approveExec(ex)">审批通过</el-button>
                    <el-button v-if="ex.status === 'running' || ex.status === 'paused'" size="small" type="danger"
                               :loading="ex._busy" @click="cancelExec(ex)">取消</el-button>
                    <el-button size="small" link @click="viewExecDetail(ex)">详情</el-button>
                  </div>
                </div>
              </div>
              <div v-else-if="!loadingExecs" class="empty-tip" style="padding: 30px;">
                {{ hasExecFilters ? '没有符合筛选条件的执行记录' : '暂无执行记录' }}
              </div>
              <!-- 分页 -->
              <div class="exec-pager" v-if="execTotalPages > 1">
                <el-pagination background layout="prev, pager, next, jumper, total" :total="execTotal"
                               :page-size="execPageSize" v-model:current-page="execPage"
                               @current-change="loadExecutions" />
              </div>
            </div>
          </el-tab-pane>

          <!-- V3.0 F-31 Webhook 管理 -->
          <el-tab-pane label="Webhook" name="webhook">
            <div class="webhook-panel">
              <div class="webhook-toolbar">
                <el-button size="small" type="primary" @click="openWebhookDialog">+ 新建 Webhook</el-button>
                <el-button size="small" @click="loadWebhooks">刷新</el-button>
              </div>
              <div v-if="webhooks.length" class="webhook-list">
                <div v-for="wh in webhooks" :key="wh.id" class="webhook-card">
                  <div class="webhook-head">
                    <span class="webhook-name">{{ wh.name || '(未命名)' }}</span>
                    <el-tag v-if="!wh.enabled" size="small" type="info">已禁用</el-tag>
                    <el-tag v-else size="small" type="success">启用</el-tag>
                    <span class="webhook-triggers">触发 {{ wh.triggerCount }} 次</span>
                  </div>
                  <div class="webhook-url-row">
                    <span class="rl">触发 URL:</span>
                    <code class="webhook-url">{{ wh.url }}</code>
                  </div>
                  <div class="webhook-meta" v-if="wh.workflowName">绑定工作流: {{ wh.workflowName }}</div>
                  <div class="webhook-meta" v-if="wh.lastTriggered">最后触发: {{ formatTime(wh.lastTriggered) }}</div>
                  <div class="webhook-actions">
                    <el-button size="small" @click="editWebhook(wh)">编辑</el-button>
                    <el-button size="small" type="danger" @click="removeWebhook(wh)">删除</el-button>
                  </div>
                </div>
              </div>
              <div v-else class="empty-tip" style="padding: 30px;">暂无 Webhook, 点击上方按钮创建</div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </template>
      <div v-else class="empty-detail">
        <svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h4.5c1.6 0 2.6 1 3.5 2.6L13 11M4 18h4.5c1.6 0 2.6-1 3.5-2.6L13 13"/><path d="M17 6l3 6-3 6"/></svg>
        <p>选择左侧工作流查看详情, 或新建一个工作流</p>
      </div>
    </div>

    <!-- 创建/编辑工作流对话框 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑工作流' : '新建工作流'" width="780px" @closed="resetForm" :close-on-click-modal="false">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如: 内容创作流" maxlength="100" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="工作流用途说明" />
        </el-form-item>
        <el-form-item label="编排模式">
          <el-radio-group v-model="form.mode">
            <el-radio value="sequential">串行 (顺序执行, 可引用前序输出)</el-radio>
            <el-radio value="parallel">并行 (全部并发, 互不引用)</el-radio>
            <el-radio value="conditional">条件 (按条件分支)</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>

        <el-divider content-position="left">步骤定义</el-divider>
        <div v-for="(s, idx) in form.steps" :key="idx" class="form-step">
          <div class="form-step-head">
            <span class="form-step-no">步骤 {{ idx + 1 }}</span>
            <el-button v-if="form.steps.length > 1" link type="danger" @click="removeStep(idx)">删除</el-button>
          </div>
          <el-form-item label="名称" label-width="70px">
            <el-input v-model="s.name" placeholder="步骤名 (唯一, 用作变量引用)" />
          </el-form-item>
          <el-form-item label="专家" label-width="70px">
            <el-select v-model="s.agentId" placeholder="选择执行该步的专家" filterable style="width:100%">
              <el-option v-for="a in agents" :key="a.id" :label="a.name + (a.isTemplate ? ' (模板)' : '')" :value="a.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="输入" label-width="70px">
            <el-input v-model="s.input" type="textarea" :rows="2"
                      placeholder="支持 ${user_message} / ${prev_step_name} 等变量" />
          </el-form-item>
          <el-form-item v-if="form.mode === 'conditional'" label="条件" label-width="70px">
            <el-input v-model="s.condition" placeholder='条件表达式, 如: contains(${prev}, "YES") 或 not:equals(text,"") ; 留空则无条件执行' />
          </el-form-item>
          <!-- V3.0 F-33 人工审批节点 -->
          <el-form-item label="审批节点" label-width="70px">
            <el-switch v-model="s.approval" />
            <span class="form-hint">开启后, 执行到该步会暂停等待人工审批通过再继续</span>
          </el-form-item>
          <!-- V3.0 F-33 步骤重试策略 -->
          <el-collapse v-model="s._retryOpen" class="step-retry-collapse">
            <el-collapse-item title="重试策略 (留空用默认策略)" name="retry">
              <div class="retry-form">
                <el-form-item label="最大次数" label-width="80px">
                  <el-input-number v-model="s.retryMaxAttempts" :min="1" :max="10" controls-position="right" size="small" />
                </el-form-item>
                <el-form-item label="退避策略" label-width="80px">
                  <el-select v-model="s.retryBackoff" size="small" style="width:140px">
                    <el-option label="指数退避" value="exponential" />
                    <el-option label="线性递增" value="linear" />
                    <el-option label="固定间隔" value="fixed" />
                  </el-select>
                </el-form-item>
                <el-form-item label="初始延迟" label-width="80px">
                  <el-input-number v-model="s.retryInitialDelay" :min="0" :step="500" controls-position="right" size="small" />
                  <span class="form-hint">ms</span>
                </el-form-item>
                <el-form-item label="退避乘数" label-width="80px">
                  <el-input-number v-model="s.retryMultiplier" :min="1" :step="0.5" :precision="1" controls-position="right" size="small" />
                </el-form-item>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
        <el-button type="primary" plain @click="addStep" class="add-step-btn">+ 添加步骤</el-button>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- V3.0 F-31 Webhook 创建/编辑对话框 -->
    <el-dialog v-model="webhookDialogVisible" :title="webhookEditing ? '编辑 Webhook' : '新建 Webhook'" width="560px" :close-on-click-modal="false">
      <el-form :model="webhookForm" label-width="100px">
        <el-form-item label="绑定工作流" required>
          <el-select v-model="webhookForm.workflowId" placeholder="选择工作流" filterable style="width:100%">
            <el-option v-for="w in workflows.filter(x => !x.isTemplate)" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="webhookForm.name" placeholder="如: 内容发布触发器" maxlength="100" />
        </el-form-item>
        <el-form-item label="Secret">
          <el-input v-model="webhookForm.secret" placeholder="可选, 用于签名验证 (留空则不验证)" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="webhookForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="webhookDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="webhookSaving" @click="submitWebhookForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 执行详情(可拉伸) -->
    <el-dialog
      v-model="execDetailVisible"
      :title="detailTitle"
      :width="dialogSize.width + 'px'"
      class="exec-detail-dialog"
      append-to-body
    >
      <div class="exec-detail" v-html="detailHtml"></div>
      <span class="exec-resize-handle" @mousedown.stop="startResize" title="拖拽调整大小"></span>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getWorkflows, createWorkflow, updateWorkflow, deleteWorkflow, executeWorkflow,
  getWorkflowExecutions, getWorkflowExecution, resumeWorkflowExecution, approveWorkflowExecution, cancelWorkflowExecution,
  getWebhooks, createWebhook, updateWebhook, deleteWebhook,
  getAgents, extractMessage
} from '@/api'
import { useMarkdown } from '@/composables/useMarkdown'

const loading = ref(false)
const workflows = ref([])
const currentWfId = ref('')
const currentWf = computed(() => workflows.value.find(w => w.id === currentWfId.value) || null)
const sidebarCollapsed = ref(false)
const activeTab = ref('steps')

const agents = ref([])

// 执行
const runInput = ref('')
const running = ref(false)
const executionResult = ref(null)

// 跨视图联动: DAG 工作流编辑/概览跳转到 DAG 编辑器 (App.vue 监听后切换 mode)
const emit = defineEmits(['open-dag'])

// DAG 工作流: 编辑按钮跳转 DAG 编辑器; 其余走步骤表单
function onEditClick() {
  if (currentWf.value && currentWf.value.mode === 'dag') {
    openDagEditor()
  } else {
    openEditDialog()
  }
}
function openDagEditor() {
  if (!currentWf.value) return
  emit('open-dag', currentWf.value.id)
}
const dagNodeTypeLabel = (t) => ({ agent: '专家', condition: '条件', router: '路由', approval: '审批', merge: '合并' })[t] || t
function dagNodeSub(n) {
  if (n.type === 'agent') return n.data?.input ? `输入: ${n.data.input}` : '专家节点'
  if (n.type === 'condition') return n.data?.condition || '条件节点'
  if (n.type === 'router') return '分析并指派执行者'
  if (n.type === 'approval') return '人工审批门禁'
  if (n.type === 'merge') return '分支合并'
  return ''
}

// 对话框
const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const form = reactive({
  id: '', name: '', description: '', mode: 'sequential', enabled: true, steps: []
})

function modeIcon(mode) {
  if (mode === 'dag') return '◈'
  if (mode === 'parallel') return '⇶'
  if (mode === 'conditional') return '⑂'
  return '→'
}
function modeLabel(mode) {
  if (mode === 'dag') return 'DAG'
  if (mode === 'parallel') return '并行'
  if (mode === 'conditional') return '条件'
  return '串行'
}
function modeTagType(mode) {
  if (mode === 'dag') return 'primary'
  if (mode === 'parallel') return 'success'
  if (mode === 'conditional') return 'warning'
  return 'info'
}
function statusTagType(s) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  if (s === 'skipped') return 'info'
  return 'warning'
}
function statusLabel(s) {
  return ({ success: '成功', failed: '失败', skipped: '跳过', running: '运行中', partial: '部分成功' })[s] || s
}
function agentName(id) {
  const a = agents.value.find(x => x.id === id)
  return a ? a.name : id
}

async function loadWorkflows() {
  loading.value = true
  try {
    workflows.value = await getWorkflows()
    if (!currentWfId.value && workflows.value.length) {
      currentWfId.value = workflows.value[0].id
    }
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载工作流列表失败')
  } finally {
    loading.value = false
  }
}

async function loadAgents() {
  try {
    agents.value = await getAgents()
  } catch (e) {
    console.warn('[WorkflowView] 加载 Agent 列表失败:', e)
  }
}

function selectWf(id) {
  currentWfId.value = id
  activeTab.value = 'steps'
  executionResult.value = null
}

function openCreateDialog() {
  editing.value = false
  resetForm()
  form.steps = [newStep()]
  dialogVisible.value = true
}

function openEditDialog() {
  if (!currentWf.value) return
  editing.value = true
  const w = currentWf.value
  form.id = w.id
  form.name = w.name
  form.description = w.description || ''
  form.mode = w.mode
  form.enabled = w.enabled
  form.steps = (w.steps || []).map(stepToForm)
  dialogVisible.value = true
}

function resetForm() {
  form.id = ''
  form.name = ''
  form.description = ''
  form.mode = 'sequential'
  form.enabled = true
  form.steps = []
}

function addStep() {
  form.steps.push(newStep())
}
function newStep() {
  return {
    name: 'step' + (form.steps.length + 1),
    agentId: '',
    input: '${user_message}',
    condition: '',
    approval: false,
    _retryOpen: [],
    retryMaxAttempts: null,
    retryBackoff: '',
    retryInitialDelay: null,
    retryMultiplier: null
  }
}
// 把后端返回的步骤转为表单可编辑结构 (补齐 V3.0 字段默认值)
function stepToForm(s) {
  return {
    ...s,
    approval: !!s.approval,
    _retryOpen: [],
    retryMaxAttempts: s.retry ? s.retry.maxAttempts : null,
    retryBackoff: s.retry ? (s.retry.backoff || '') : '',
    retryInitialDelay: s.retry ? s.retry.initialDelayMs : null,
    retryMultiplier: s.retry ? s.retry.multiplier : null
  }
}
function removeStep(idx) {
  form.steps.splice(idx, 1)
}
// 构造 retry payload; 全部留空则返回 null (用系统默认策略)
function buildRetry(s) {
  if (!s.retryMaxAttempts && !s.retryBackoff && s.retryInitialDelay === null && s.retryMultiplier === null) {
    return null
  }
  return {
    maxAttempts: s.retryMaxAttempts || null,
    backoff: s.retryBackoff || null,
    initialDelayMs: s.retryInitialDelay !== null ? s.retryInitialDelay : null,
    multiplier: s.retryMultiplier !== null ? s.retryMultiplier : null
  }
}

async function submitForm() {
  if (!form.name || !form.name.trim()) {
    ElMessage.warning('请填写工作流名称')
    return
  }
  if (!form.steps.length) {
    ElMessage.warning('至少需要一步')
    return
  }
  for (const s of form.steps) {
    if (!s.name || !s.name.trim()) { ElMessage.warning('每个步骤必须有名称'); return }
    if (!s.agentId) { ElMessage.warning('步骤 ' + s.name + ' 未选择专家'); return }
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      description: form.description || null,
      mode: form.mode,
      steps: form.steps.map(s => ({
        name: s.name.trim(),
        agentId: s.agentId,
        input: s.input || null,
        condition: form.mode === 'conditional' ? (s.condition || null) : null,
        // V3.0 F-33: 人工审批节点
        approval: s.approval ? true : null,
        // V3.0 F-33: 步骤重试策略 (任一字段填写即下发)
        retry: buildRetry(s)
      })),
      enabled: form.enabled
    }
    if (editing.value) {
      await updateWorkflow(form.id, payload)
      ElMessage.success('工作流已更新')
    } else {
      const created = await createWorkflow(payload)
      ElMessage.success('工作流已创建')
      currentWfId.value = created.id
    }
    dialogVisible.value = false
    await loadWorkflows()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeWf() {
  if (!currentWf.value) return
  try {
    await ElMessageBox.confirm(`确定删除工作流 "${currentWf.value.name}" 吗?`, '提示', { type: 'warning' })
    await deleteWorkflow(currentWf.value.id)
    ElMessage.success('已删除')
    if (currentWfId.value === currentWf.value.id) currentWfId.value = ''
    await loadWorkflows()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(extractMessage(e) || '删除失败')
  }
}

async function runWorkflow() {
  if (!currentWf.value) return
  running.value = true
  executionResult.value = null
  try {
    executionResult.value = await executeWorkflow(currentWf.value.id, runInput.value)
    ElMessage.success('工作流执行完成')
  } catch (e) {
    ElMessage.error(extractMessage(e) || '执行失败')
  } finally {
    running.value = false
  }
}

// ===== V3.0-alpha F-33 执行历史 (搜索/筛选/分页 + 断点续跑 / 人工审批) =====
const executions = ref([])
const loadingExecs = ref(false)
// 分页/筛选状态 (后端分页, 页码从 0 开始; el-pagination 从 1 开始, 提交时 -1)
const execPage = ref(1)
const execPageSize = 10
const execTotal = ref(0)
const execTotalPages = ref(0)
const execFilters = reactive({ keyword: '', status: '', workflowId: '', triggerType: '' })
const hasExecFilters = computed(() =>
  execFilters.keyword || execFilters.status || execFilters.workflowId || execFilters.triggerType
)

function execStatusTagType(s) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  if (s === 'paused') return 'warning'
  if (s === 'cancelled') return 'info'
  return 'primary'
}
function execStatusLabel(s) {
  return ({ success: '成功', failed: '失败', paused: '待审批', cancelled: '已取消', running: '运行中' })[s] || s
}
function triggerLabel(t) {
  return ({ manual: '手动', api: 'API', webhook: 'Webhook', chat: '对话', schedule: '定时', event: '事件' })[t] || t
}
function formatTime(t) {
  if (!t) return ''
  try { return new Date(t).toLocaleString('zh-CN') } catch { return t }
}

async function loadExecutions() {
  loadingExecs.value = true
  try {
    const params = {
      page: execPage.value - 1,
      size: execPageSize
    }
    // 空值不下发, 避免后端把空串当筛选条件
    if (execFilters.keyword) params.keyword = execFilters.keyword.trim()
    if (execFilters.status) params.status = execFilters.status
    if (execFilters.workflowId) params.workflowId = execFilters.workflowId
    if (execFilters.triggerType) params.triggerType = execFilters.triggerType
    const data = await getWorkflowExecutions(params)
    // 兼容两种响应: 分页对象 {content,...} / 旧版数组
    executions.value = Array.isArray(data) ? data : (data.content || [])
    execTotal.value = Array.isArray(data) ? data.length : (data.totalElements || 0)
    execTotalPages.value = Array.isArray(data) ? 0 : (data.totalPages || 0)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载执行记录失败')
  } finally {
    loadingExecs.value = false
  }
}

/** 搜索/筛选条件变化: 回到第 1 页再查询 */
function searchExecutions() {
  execPage.value = 1
  loadExecutions()
}

/** 重置全部筛选条件 */
function resetExecFilters() {
  execFilters.keyword = ''
  execFilters.status = ''
  execFilters.workflowId = ''
  execFilters.triggerType = ''
  searchExecutions()
}

async function resumeExec(ex) {
  ex._busy = true
  try {
    await resumeWorkflowExecution(ex.id)
    ElMessage.success('断点续跑已触发')
    await loadExecutions()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '续跑失败')
  } finally {
    ex._busy = false
  }
}

async function approveExec(ex) {
  let comment = ''
  try {
    const r = await ElMessageBox.prompt('请输入审批意见 (可选)', '人工审批', {
      confirmButtonText: '通过', cancelButtonText: '取消', inputType: 'textarea'
    })
    comment = r.value || ''
  } catch { return }
  ex._busy = true
  try {
    await approveWorkflowExecution(ex.id, comment)
    ElMessage.success('审批已通过, 继续执行')
    await loadExecutions()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '审批失败')
  } finally {
    ex._busy = false
  }
}

async function cancelExec(ex) {
  try {
    await ElMessageBox.confirm('确定取消该执行吗?', '提示', { type: 'warning' })
    ex._busy = true
    await cancelWorkflowExecution(ex.id)
    ElMessage.success('已取消')
    await loadExecutions()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(extractMessage(e) || '取消失败')
  } finally {
    ex._busy = false
  }
}

// 执行详情弹框(可拉伸)
const execDetailVisible = ref(false)
const detailHtml = ref('')
const detailTitle = ref('')
const dialogSize = reactive({ width: 640, height: 70 })
const resizing = ref(false)
function startResize(e) {
  e.preventDefault()
  const startX = e.clientX
  const startY = e.clientY
  const startW = dialogSize.width
  const startH = dialogSize.height
  resizing.value = true
  const onMove = (ev) => {
    dialogSize.width = Math.max(420, Math.min(1200, startW + (ev.clientX - startX)))
    dialogSize.height = Math.max(40, Math.min(92, startH + ((ev.clientY - startY) / window.innerHeight) * 100))
  }
  const onUp = () => {
    resizing.value = false
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    document.body.style.userSelect = ''
  }
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}

async function viewExecDetail(ex) {
  try {
    const detail = await getWorkflowExecution(ex.id)
    const stepsRaw = detail.steps ?? (detail.stepsStateJson ? JSON.parse(detail.stepsStateJson) : [])
    const { renderMarkdown } = useMarkdown()
    const esc = (v) =>
      String(v ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))
    // 自动识别 Markdown: 含换行且命中常见语法符号时, 渲染为富文本, 否则按纯文本(保留换行)
    const MD_HINT = /(^|\n)\s{0,3}(#{1,6}\s|[-*+]\s|>\s|\d+\.\s|```|\[.+\]\(.+\))|(\*\*|__|\b_.*_\b)|(\|.*\|.*\n\|[-: |]+\|)/
    const looksLikeMd = (t) => t && t.includes('\n') && MD_HINT.test(t)
    const renderField = (t) => {
      const text = String(t ?? '')
      if (looksLikeMd(text)) return renderMarkdown(text)
      // 纯文本: 转义后保留换行
      return `<div class="ed-plain">${esc(text)}</div>`
    }
    const statusTag = (s) => {
      const map = { success: 'var(--dcdr-success)', failed: 'var(--dcdr-danger)', running: '#409eff', pending: '#909399', skipped: 'var(--dcdr-warning)' }
      const color = map[s] || '#909399'
      return `<span style="display:inline-block;padding:1px 8px;border-radius:10px;font-size:12px;color:#fff;background:${color}">${esc(s)}</span>`
    }
    const stepHtml = stepsRaw.length
      ? stepsRaw
          .map((s, i) => {
            const agent = [s.agentName, s.agentId].filter(Boolean).join(' / ')
            const meta = [
              s.durationMs != null ? `耗时 ${s.durationMs}ms` : '',
              s.attempts != null && s.attempts > 1 ? `重试 ${s.attempts} 次` : ''
            ].filter(Boolean).join(' · ')
            return `<div class="ed-step">
  <div class="ed-step-head"><span class="ed-no">${i + 1}</span>
    <span class="ed-name">${esc(s.name)}</span>${statusTag(s.status)}${s.error ? `<span class="ed-err">${esc(s.error)}</span>` : ''}</div>
  ${agent ? `<div class="ed-agent">${esc(agent)}</div>` : ''}
  ${meta ? `<div class="ed-meta">${esc(meta)}</div>` : ''}
  ${s.input ? `<div class="ed-io"><span class="ed-io-label">输入</span><div class="ed-md">${renderField(s.input)}</div></div>` : ''}
  ${s.output ? `<div class="ed-io"><span class="ed-io-label">输出</span><div class="ed-md">${renderField(s.output)}</div></div>` : ''}
</div>`
          })
          .join('')
      : '<div class="ed-empty">（无步骤详情）</div>'
    const overview = `<div class="ed-overview">
  <span>状态 ${statusTag(detail.status)}</span>
  ${detail.triggerType ? `<span>触发：${esc(detail.triggerType)}</span>` : ''}
  ${detail.startedAt ? `<span>开始：${esc(detail.startedAt)}</span>` : ''}
  ${detail.completedAt ? `<span>完成：${esc(detail.completedAt)}</span>` : ''}
</div>`
    detailHtml.value = `<div class="exec-detail">${overview}<div class="ed-steps">${stepHtml}</div></div>`
    detailTitle.value = `执行详情 · ${detail.workflowName || detail.workflowId || ''}`
    dialogSize.width = 640
    dialogSize.height = 70
    execDetailVisible.value = true
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载详情失败')
  }
}

// ===== V3.0-alpha F-31 Webhook 管理 =====
const webhooks = ref([])
const webhookDialogVisible = ref(false)
const webhookEditing = ref(false)
const webhookSaving = ref(false)
const webhookForm = reactive({ id: '', workflowId: '', name: '', secret: '', enabled: true })

async function loadWebhooks() {
  try {
    webhooks.value = await getWebhooks()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载 Webhook 列表失败')
  }
}

function openWebhookDialog() {
  webhookEditing.value = false
  webhookForm.id = ''
  webhookForm.workflowId = currentWf.value ? currentWf.value.id : ''
  webhookForm.name = ''
  webhookForm.secret = ''
  webhookForm.enabled = true
  webhookDialogVisible.value = true
}

function editWebhook(wh) {
  webhookEditing.value = true
  webhookForm.id = wh.id
  webhookForm.workflowId = wh.workflowId
  webhookForm.name = wh.name || ''
  webhookForm.secret = wh.secret || ''
  webhookForm.enabled = wh.enabled
  webhookDialogVisible.value = true
}

async function submitWebhookForm() {
  if (!webhookForm.workflowId) { ElMessage.warning('请选择绑定的工作流'); return }
  webhookSaving.value = true
  try {
    const payload = {
      workflowId: webhookForm.workflowId,
      name: webhookForm.name || null,
      secret: webhookForm.secret || null,
      enabled: webhookForm.enabled
    }
    if (webhookEditing.value) {
      await updateWebhook(webhookForm.id, payload)
      ElMessage.success('Webhook 已更新')
    } else {
      await createWebhook(payload)
      ElMessage.success('Webhook 已创建')
    }
    webhookDialogVisible.value = false
    await loadWebhooks()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '保存失败')
  } finally {
    webhookSaving.value = false
  }
}

async function removeWebhook(wh) {
  try {
    await ElMessageBox.confirm(`确定删除 Webhook "${wh.name || wh.id}" 吗?`, '提示', { type: 'warning' })
    await deleteWebhook(wh.id)
    ElMessage.success('已删除')
    await loadWebhooks()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(extractMessage(e) || '删除失败')
  }
}

// 切换工作流时按需加载执行历史 / Webhook (执行历史每次进入都刷新, 保证看到最新状态)
function onTabChange(tab) {
  if (tab === 'history') loadExecutions()
  if (tab === 'webhook' && !webhooks.value.length) loadWebhooks()
}

onMounted(() => {
  loadWorkflows()
  loadAgents()
})
</script>

<style scoped>
.wf-mode { flex: 1; display: flex; overflow: hidden; }
.sidebar { width: 280px; background: var(--dcdr-bg-subtle); border-right: 1px solid var(--dcdr-border); display: flex; flex-direction: column; transition: width 0.2s; }
.sidebar.collapsed { width: 0; overflow: hidden; }
.sidebar-expand { position: absolute; left: 0; top: 56px; width: 20px; height: 40px; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-left: none; border-radius: 0 6px 6px 0; cursor: pointer; z-index: 10; }
.sidebar-header { padding: 12px; display: flex; gap: 8px; align-items: center; }
.new-btn { flex: 1; padding: 8px 12px; border: none; border-radius: 8px; color: #fff; font-size: 13px; cursor: pointer; }
.collapse-btn { width: 28px; height: 32px; border: 1px solid var(--dcdr-border); background: var(--dcdr-surface); border-radius: 6px; cursor: pointer; color: var(--dcdr-text-3); }
.wf-list { flex: 1; overflow-y: auto; padding: 0 8px 12px; }
.wf-item { display: flex; gap: 10px; padding: 10px; border-radius: 8px; cursor: pointer; margin-bottom: 4px; }
.wf-item:hover { background: var(--dcdr-bg); }
.wf-item.active { background: linear-gradient(135deg, var(--dcdr-accent-wash) 0%, var(--dcdr-accent-wash) 100%); }
.wf-icon { width: 32px; height: 32px; border-radius: 8px; background: var(--dcdr-bg); display: flex; align-items: center; justify-content: center; font-size: 16px; flex-shrink: 0; }
.wf-info { flex: 1; min-width: 0; }
.wf-name { font-size: 13px; font-weight: 500; color: var(--dcdr-text-1); display: flex; align-items: center; gap: 4px; }
.wf-name .tag { transform: scale(0.85); }
.wf-meta { font-size: 11px; color: var(--dcdr-text-3); margin-top: 2px; display: flex; gap: 4px; }
.empty-tip { text-align: center; color: var(--dcdr-text-3); font-size: 13px; padding: 40px 12px; line-height: 1.8; }

.wf-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: var(--dcdr-surface); padding: 12px 20px 0; }
.wf-toolbar { display: flex; align-items: center; justify-content: space-between; padding-bottom: 12px; border-bottom: 1px solid var(--dcdr-border); }
.wf-toolbar-left { display: flex; align-items: center; gap: 8px; }
.wf-toolbar-left h2 { margin: 0; font-size: 18px; }
.wf-desc-full { font-size: 12px; color: var(--dcdr-text-3); max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-toolbar-right { display: flex; gap: 8px; }
.wf-tabs { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.wf-tabs :deep(.el-tabs__content) { flex: 1; overflow-y: auto; padding-bottom: 20px; }

.steps-viz { padding: 12px 0; display: flex; flex-direction: column; align-items: center; }

/* DAG 工作流概览 (步骤定义 Tab) */
.dag-summary { padding: 12px 0; max-width: 720px; }
.dag-summary-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.dag-summary-meta { font-size: 12px; color: var(--dcdr-text-3); margin-left: 10px; }
.dag-node-list { display: flex; flex-direction: column; gap: 8px; }
.dag-node-card { display: flex; align-items: center; gap: 10px; background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 10px 14px; }
.dag-node-type { font-size: 11px; font-weight: 600; color: #fff; border-radius: 4px; padding: 2px 8px; flex-shrink: 0; }
.dag-node-type.t-agent { background: var(--dcdr-accent); }
.dag-node-type.t-condition { background: var(--dcdr-warning); }
.dag-node-type.t-router { background: #8b5cf6; }
.dag-node-type.t-approval { background: var(--dcdr-danger); }
.dag-node-type.t-merge { background: var(--dcdr-success); }
.dag-node-name { font-size: 13px; font-weight: 500; color: var(--dcdr-text-1); }
.dag-node-sub { font-size: 12px; color: var(--dcdr-text-3); margin-left: auto; max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dag-empty { padding: 30px 0; text-align: center; color: var(--dcdr-text-3); font-size: 13px; }
.dag-edge-section { margin-top: 16px; }
.dag-edge-title { font-size: 13px; font-weight: 600; color: var(--dcdr-text-1); margin-bottom: 8px; }
.dag-edge-row { display: flex; align-items: center; gap: 6px; font-size: 12px; padding: 4px 0; }
.dag-edge-row code { background: var(--dcdr-bg); border-radius: 4px; padding: 1px 6px; color: var(--dcdr-text-2); }
.dag-arrow { color: var(--dcdr-text-3); }
.step-card { display: flex; align-items: flex-start; gap: 12px; width: 100%; max-width: 640px; background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 12px 16px; }
.step-no { width: 28px; height: 28px; border-radius: 50%; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; flex-shrink: 0; }
.step-body { flex: 1; min-width: 0; }
.step-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.step-name { font-size: 14px; font-weight: 500; }
.step-input { font-size: 12px; color: var(--dcdr-text-3); word-break: break-word; }
.step-arrow { color: var(--dcdr-text-3); font-size: 18px; margin: 4px 0; }

.run-panel { padding: 12px 0; }
.run-input-row { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.run-btn { align-self: flex-start; }
.run-result { margin-top: 16px; }
.run-summary { display: flex; align-items: center; gap: 12px; padding: 8px 0 12px; border-bottom: 1px solid var(--dcdr-border); margin-bottom: 12px; }
.run-duration { font-size: 12px; color: var(--dcdr-text-3); }
.step-result { background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 10px 14px; margin-bottom: 10px; }
.step-result-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.step-result-head .step-no { width: 22px; height: 22px; font-size: 11px; }
.step-result-head .step-name { font-size: 13px; }
.step-duration { font-size: 11px; color: var(--dcdr-text-3); margin-left: auto; }
.step-result-input, .step-result-output, .step-result-error { font-size: 12px; line-height: 1.6; margin-top: 4px; }
.step-result-input .rl, .step-result-output .rl, .step-result-error .rl { color: var(--dcdr-text-3); }
.step-result-output pre, .final-output pre { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 4px; padding: 8px; margin: 4px 0 0; white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 12px; max-height: 200px; overflow-y: auto; }
.step-result-error { color: var(--dcdr-danger); }
.final-output { margin-top: 16px; }
.fo-label { font-size: 13px; font-weight: 600; color: var(--dcdr-accent); margin-bottom: 6px; }

.empty-detail { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--dcdr-text-3); gap: 12px; }
.empty-icon { width: 54px; height: 54px; color: var(--dcdr-text-3); opacity: 0.5; }

.form-step { background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 12px 12px 0; margin-bottom: 12px; }
.form-step-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.form-step-no { font-size: 13px; font-weight: 600; color: var(--dcdr-accent); }
.add-step-btn { width: 100%; margin-top: 4px; }

/* V3.0 F-33 步骤表单: 审批/重试 */
.form-hint { font-size: 12px; color: var(--dcdr-text-3); margin-left: 8px; }
.step-retry-collapse { margin: 8px 0 4px 70px; border: none; }
.step-retry-collapse :deep(.el-collapse-item__header) { font-size: 13px; color: var(--dcdr-text-3); height: 32px; line-height: 32px; background: var(--dcdr-bg-subtle); padding-left: 10px; border-radius: 4px; }
.step-retry-collapse :deep(.el-collapse-item__wrap) { border-bottom: none; }
.retry-form { padding: 8px 0 0; }
.retry-form :deep(.el-form-item) { margin-bottom: 10px; }

/* V3.0 F-33 执行历史 */
.history-panel { padding: 12px 0; }
.history-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.exec-search { width: 220px; }
.exec-filter { width: 110px; }
.exec-filter-wf { width: 160px; }
.exec-total { font-size: 12px; color: var(--dcdr-text-3); margin-left: auto; }
.exec-pager { margin-top: 16px; display: flex; justify-content: center; }
.exec-list { display: flex; flex-direction: column; gap: 10px; }
.exec-card { background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 12px 14px; }
.exec-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; flex-wrap: wrap; }
.exec-trigger { font-size: 12px; color: var(--dcdr-text-3); }
.exec-time { font-size: 11px; color: var(--dcdr-text-3); }
.exec-wf { font-size: 12px; color: var(--dcdr-accent); font-weight: 500; }
.exec-input { font-size: 12px; color: var(--dcdr-text-2); line-height: 1.6; margin-top: 4px; word-break: break-word; }
.exec-input .rl, .exec-error .rl { color: var(--dcdr-text-3); }
.exec-error { font-size: 12px; color: var(--dcdr-danger); margin-top: 4px; }
.exec-actions { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }

/* V3.0 F-31 Webhook */
.webhook-panel { padding: 12px 0; }
.webhook-toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.webhook-list { display: flex; flex-direction: column; gap: 10px; }
.webhook-card { background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 12px 14px; }
.webhook-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.webhook-name { font-size: 14px; font-weight: 500; }
.webhook-triggers { font-size: 11px; color: var(--dcdr-text-3); margin-left: auto; }
.webhook-url-row { font-size: 12px; margin-bottom: 6px; display: flex; align-items: center; gap: 6px; }
.webhook-url-row .rl { color: var(--dcdr-text-3); flex-shrink: 0; }
.webhook-url { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 4px; padding: 2px 8px; font-size: 11px; word-break: break-all; color: var(--dcdr-accent); }
.webhook-meta { font-size: 12px; color: var(--dcdr-text-3); margin-top: 2px; }
.webhook-actions { display: flex; gap: 8px; margin-top: 10px; }
</style>

<!-- 非 scoped: 执行详情弹窗通过 teleport 挂到 body, 需全局样式才能生效 -->
<style>
/* 可拉伸弹框: body 高度跟随拖拽, 内容区滚动 */
.exec-detail-dialog .el-dialog__body { padding: 12px 16px; overflow: hidden; }
.exec-detail-dialog .exec-detail {
  max-height: calc(v-bind('dialogSize.height') * 1vh - 90px);
  overflow-y: auto;
}
.exec-detail-dialog .resize-handle,
.exec-detail-dialog .exec-resize-handle {
  position: absolute; right: 2px; bottom: 2px; width: 16px; height: 16px;
  cursor: nwse-resize; z-index: 10;
  background: linear-gradient(135deg, transparent 50%, #c0c4cc 50%, #c0c4cc 60%, transparent 60%, transparent 72%, #c0c4cc 72%, #c0c4cc 82%, transparent 82%);
}
.exec-detail-dialog .exec-resize-handle:hover { filter: brightness(0.85); }
.exec-detail .ed-overview {
  display: flex; flex-wrap: wrap; gap: 12px; align-items: center;
  font-size: 12px; color: var(--dcdr-text-3); padding-bottom: 10px; margin-bottom: 10px; border-bottom: 1px solid var(--dcdr-border);
}
.exec-detail .ed-steps { display: flex; flex-direction: column; gap: 12px; }
.exec-detail .ed-step {
  background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 12px 14px;
}
.exec-detail .ed-step-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
.exec-detail .ed-no {
  width: 24px; height: 24px; border-radius: 50%; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%);
  color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 600; font-size: 12px; flex-shrink: 0;
}
.exec-detail .ed-name { font-size: 14px; font-weight: 500; color: var(--dcdr-text-1); }
.exec-detail .ed-err { color: var(--dcdr-danger); font-size: 12px; }
.exec-detail .ed-agent { font-size: 12px; color: var(--dcdr-accent); margin-bottom: 4px; }
.exec-detail .ed-meta { font-size: 11px; color: var(--dcdr-text-3); margin-bottom: 6px; }
.exec-detail .ed-io { margin-top: 6px; }
.exec-detail .ed-io-label {
  display: inline-block; font-size: 11px; font-weight: 600; color: var(--dcdr-text-3); margin-bottom: 2px;
}
.exec-detail .ed-io pre {
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 6px; padding: 8px 10px; margin: 0;
  white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 12px; line-height: 1.6; color: var(--dcdr-text-1);
  max-height: 220px; overflow-y: auto;
}
/* 自动识别的 Markdown 渲染容器 */
.exec-detail .ed-md {
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 6px; padding: 8px 12px; margin: 0;
  font-size: 13px; line-height: 1.7; color: var(--dcdr-text-1); max-height: 320px; overflow-y: auto; word-break: break-word;
}
.exec-detail .ed-md > :first-child { margin-top: 0; }
.exec-detail .ed-md > :last-child { margin-bottom: 0; }
.exec-detail .ed-md h1, .exec-detail .ed-md h2, .exec-detail .ed-md h3,
.exec-detail .ed-md h4, .exec-detail .ed-md h5, .exec-detail .ed-md h6 {
  margin: 10px 0 6px; font-weight: 600; line-height: 1.4; color: var(--dcdr-text-1);
}
.exec-detail .ed-md h1 { font-size: 17px; }
.exec-detail .ed-md h2 { font-size: 15px; }
.exec-detail .ed-md h3 { font-size: 14px; }
.exec-detail .ed-md h4, .exec-detail .ed-md h5, .exec-detail .ed-md h6 { font-size: 13px; }
.exec-detail .ed-md p { margin: 6px 0; }
.exec-detail .ed-md ul, .exec-detail .ed-md ol { margin: 6px 0; padding-left: 22px; }
.exec-detail .ed-md li { margin: 2px 0; }
.exec-detail .ed-md strong, .exec-detail .ed-md b { font-weight: 600; color: var(--dcdr-text-1); }
.exec-detail .ed-md em { font-style: italic; }
.exec-detail .ed-md a { color: #1677ff; text-decoration: none; }
.exec-detail .ed-md code {
  background: var(--dcdr-bg); border-radius: 4px; padding: 1px 5px; font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; color: #c7254e;
}
.exec-detail .ed-md pre {
  background: var(--dcdr-bg-subtle); border-radius: 6px; padding: 10px 12px; overflow-x: auto; margin: 8px 0;
}
.exec-detail .ed-md pre code { background: transparent; color: var(--dcdr-text-1); padding: 0; }
.exec-detail .ed-md blockquote {
  margin: 8px 0; padding: 4px 12px; border-left: 3px solid var(--dcdr-border); color: var(--dcdr-text-2); background: var(--dcdr-bg-subtle);
}
.exec-detail .ed-md table { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 12px; }
.exec-detail .ed-md th, .exec-detail .ed-md td { border: 1px solid var(--dcdr-border); padding: 4px 8px; text-align: left; }
.exec-detail .ed-md th { background: var(--dcdr-bg); font-weight: 600; }
/* 纯文本(非 markdown): 保留换行, 去掉刺眼符号感 */
.exec-detail .ed-plain { white-space: pre-wrap; word-break: break-word; font-size: 12px; line-height: 1.6; color: var(--dcdr-text-2); }
.exec-detail .ed-empty { text-align: center; color: var(--dcdr-text-3); font-size: 13px; padding: 24px 0; }
</style>
