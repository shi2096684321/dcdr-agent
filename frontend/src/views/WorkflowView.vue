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
              <span>{{ (w.steps || []).length }} 步</span>
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
            <el-tag size="small">{{ (currentWf.steps || []).length }} 步</el-tag>
            <span class="wf-desc-full" v-if="currentWf.description" :title="currentWf.description">{{ currentWf.description }}</span>
          </div>
          <div class="wf-toolbar-right">
            <el-button size="small" @click="openEditDialog">编辑</el-button>
            <el-button type="danger" size="small" @click="removeWf">删除</el-button>
          </div>
        </div>

        <el-tabs v-model="activeTab" class="wf-tabs">
          <!-- 步骤可视化 -->
          <el-tab-pane label="步骤定义" name="steps">
            <div class="steps-viz">
              <div v-for="(step, idx) in (currentWf.steps || [])" :key="idx" class="step-card">
                <div class="step-no">{{ idx + 1 }}</div>
                <div class="step-body">
                  <div class="step-head">
                    <span class="step-name">{{ step.name }}</span>
                    <el-tag size="small" type="info">{{ agentName(step.agentId) }}</el-tag>
                    <el-tag v-if="step.condition" size="small" type="warning">条件: {{ step.condition }}</el-tag>
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
        </el-tabs>
      </template>
      <div v-else class="empty-detail">
        <div class="empty-icon">🔀</div>
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
          <el-form-item label="Agent" label-width="70px">
            <el-select v-model="s.agentId" placeholder="选择执行该步的 Agent" filterable style="width:100%">
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
        </div>
        <el-button type="primary" plain @click="addStep" class="add-step-btn">+ 添加步骤</el-button>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getWorkflows, createWorkflow, updateWorkflow, deleteWorkflow, executeWorkflow,
  getAgents, extractMessage
} from '@/api'

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

// 对话框
const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const form = reactive({
  id: '', name: '', description: '', mode: 'sequential', enabled: true, steps: []
})

function modeIcon(mode) {
  if (mode === 'parallel') return '⇶'
  if (mode === 'conditional') return '⑂'
  return '→'
}
function modeLabel(mode) {
  if (mode === 'parallel') return '并行'
  if (mode === 'conditional') return '条件'
  return '串行'
}
function modeTagType(mode) {
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
  form.steps = [{ name: 'step1', agentId: '', input: '${user_message}', condition: '' }]
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
  form.steps = (w.steps || []).map(s => ({ ...s }))
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
  form.steps.push({ name: 'step' + (form.steps.length + 1), agentId: '', input: '${user_message}', condition: '' })
}
function removeStep(idx) {
  form.steps.splice(idx, 1)
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
    if (!s.agentId) { ElMessage.warning('步骤 ' + s.name + ' 未选择 Agent'); return }
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
        condition: form.mode === 'conditional' ? (s.condition || null) : null
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

onMounted(() => {
  loadWorkflows()
  loadAgents()
})
</script>

<style scoped>
.wf-mode { flex: 1; display: flex; overflow: hidden; }
.sidebar { width: 280px; background: #fafbfc; border-right: 1px solid #e8e8e8; display: flex; flex-direction: column; transition: width 0.2s; }
.sidebar.collapsed { width: 0; overflow: hidden; }
.sidebar-expand { position: absolute; left: 0; top: 56px; width: 20px; height: 40px; background: #fff; border: 1px solid #e8e8e8; border-left: none; border-radius: 0 6px 6px 0; cursor: pointer; z-index: 10; }
.sidebar-header { padding: 12px; display: flex; gap: 8px; align-items: center; }
.new-btn { flex: 1; padding: 8px 12px; border: none; border-radius: 8px; color: #fff; font-size: 13px; cursor: pointer; }
.collapse-btn { width: 28px; height: 32px; border: 1px solid #e8e8e8; background: #fff; border-radius: 6px; cursor: pointer; color: #86909c; }
.wf-list { flex: 1; overflow-y: auto; padding: 0 8px 12px; }
.wf-item { display: flex; gap: 10px; padding: 10px; border-radius: 8px; cursor: pointer; margin-bottom: 4px; }
.wf-item:hover { background: #f0f2f5; }
.wf-item.active { background: linear-gradient(135deg, rgba(102,126,234,0.12) 0%, rgba(118,75,162,0.10) 100%); }
.wf-icon { width: 32px; height: 32px; border-radius: 8px; background: #f0f2f5; display: flex; align-items: center; justify-content: center; font-size: 16px; flex-shrink: 0; }
.wf-info { flex: 1; min-width: 0; }
.wf-name { font-size: 13px; font-weight: 500; color: #303133; display: flex; align-items: center; gap: 4px; }
.wf-name .tag { transform: scale(0.85); }
.wf-meta { font-size: 11px; color: #86909c; margin-top: 2px; display: flex; gap: 4px; }
.empty-tip { text-align: center; color: #a0a4ab; font-size: 13px; padding: 40px 12px; line-height: 1.8; }

.wf-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: #fff; padding: 12px 20px 0; }
.wf-toolbar { display: flex; align-items: center; justify-content: space-between; padding-bottom: 12px; border-bottom: 1px solid #f0f2f5; }
.wf-toolbar-left { display: flex; align-items: center; gap: 8px; }
.wf-toolbar-left h2 { margin: 0; font-size: 18px; }
.wf-desc-full { font-size: 12px; color: #86909c; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-toolbar-right { display: flex; gap: 8px; }
.wf-tabs { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.wf-tabs :deep(.el-tabs__content) { flex: 1; overflow-y: auto; padding-bottom: 20px; }

.steps-viz { padding: 12px 0; display: flex; flex-direction: column; align-items: center; }
.step-card { display: flex; align-items: flex-start; gap: 12px; width: 100%; max-width: 640px; background: #fafbfc; border: 1px solid #e8e8e8; border-radius: 10px; padding: 12px 16px; }
.step-no { width: 28px; height: 28px; border-radius: 50%; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; flex-shrink: 0; }
.step-body { flex: 1; min-width: 0; }
.step-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.step-name { font-size: 14px; font-weight: 500; }
.step-input { font-size: 12px; color: #86909c; word-break: break-word; }
.step-arrow { color: #c0c4cc; font-size: 18px; margin: 4px 0; }

.run-panel { padding: 12px 0; }
.run-input-row { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.run-btn { align-self: flex-start; }
.run-result { margin-top: 16px; }
.run-summary { display: flex; align-items: center; gap: 12px; padding: 8px 0 12px; border-bottom: 1px solid #f0f2f5; margin-bottom: 12px; }
.run-duration { font-size: 12px; color: #86909c; }
.step-result { background: #fafbfc; border: 1px solid #e8e8e8; border-radius: 8px; padding: 10px 14px; margin-bottom: 10px; }
.step-result-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.step-result-head .step-no { width: 22px; height: 22px; font-size: 11px; }
.step-result-head .step-name { font-size: 13px; }
.step-duration { font-size: 11px; color: #a0a4ab; margin-left: auto; }
.step-result-input, .step-result-output, .step-result-error { font-size: 12px; line-height: 1.6; margin-top: 4px; }
.step-result-input .rl, .step-result-output .rl, .step-result-error .rl { color: #86909c; }
.step-result-output pre, .final-output pre { background: #fff; border: 1px solid #f0f2f5; border-radius: 4px; padding: 8px; margin: 4px 0 0; white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 12px; max-height: 200px; overflow-y: auto; }
.step-result-error { color: #f56c6c; }
.final-output { margin-top: 16px; }
.fo-label { font-size: 13px; font-weight: 600; color: #667eea; margin-bottom: 6px; }

.empty-detail { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #a0a4ab; gap: 12px; }
.empty-icon { font-size: 56px; }

.form-step { background: #fafbfc; border: 1px solid #e8e8e8; border-radius: 8px; padding: 12px 12px 0; margin-bottom: 12px; }
.form-step-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.form-step-no { font-size: 13px; font-weight: 600; color: #667eea; }
.add-step-btn { width: 100%; margin-top: 4px; }
</style>
