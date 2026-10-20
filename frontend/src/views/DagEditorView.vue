<template>
  <div class="dag-mode">
    <div class="dag-header">
      <h2>可视化 DAG 编排</h2>
      <p class="subtitle">拖拽节点 + 连线编排多专家协作流程 (V3.0-beta F-32); 支持并行分支、自然语言条件与人工审批</p>
    </div>

    <div class="dag-toolbar">
      <el-select v-model="workflowId" size="small" style="width:240px" placeholder="选择要编辑的工作流" @change="onWorkflowChange">
        <el-option v-for="w in editableWorkflows" :key="w.id" :label="w.name" :value="w.id" />
      </el-select>
      <el-button size="small" @click="createNew">新建 DAG 工作流</el-button>
      <div style="flex:1"></div>
      <el-button size="small" type="primary" :disabled="!dirty" :loading="saving" @click="save">保存</el-button>
      <el-button size="small" @click="runDag" :loading="running" :disabled="!workflowId && !newWorkflow">试运行</el-button>
      <el-button size="small" @click="loadVersions" v-if="workflowId">版本历史</el-button>
    </div>

    <div class="dag-body">
      <!-- 节点面板 -->
      <div class="palette">
        <div class="p-title">添加节点</div>
        <div class="p-item" v-for="p in PALETTE" :key="p.type" @click="addNode(p.type)">
          <svg class="p-icon" viewBox="0 0 24 24" :class="'pi-' + p.type" v-html="NODE_ICONS[p.type]" />
          <span class="p-text">{{ p.label }}</span>
        </div>
        <div class="p-hint">
          <div>· 拖动节点调整布局</div>
          <div>· 点击节点后点击"连线"再到目标节点创建边</div>
          <div>· 点击节点/边进行配置或删除</div>
          <div>· condition 出边标签: 是/否</div>
          <div>· router 出边标签: 候选执行者名</div>
        </div>
      </div>

      <!-- 画布 -->
      <div class="canvas-wrap" ref="canvasWrap" @mousemove="onDrag" @mouseup="endDrag" @mouseleave="endDrag">
        <svg class="canvas-svg" :width="canvasW" :height="canvasH">
          <!-- 网格 -->
          <defs>
            <pattern id="grid" width="80" height="80" patternUnits="userSpaceOnUse">
              <path d="M 80 0 L 0 0 0 80" fill="none" stroke="var(--dcdr-grid)" stroke-width="1"/>
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#grid)"/>
          <!-- 边 -->
          <g v-for="e in edges" :key="e.source + '->' + e.target" class="edge" @click="selectEdge(e)">
            <path :d="edgePath(e)" class="edge-path" :class="{ selected: selectedEdge === e }" fill="none" stroke-width="1.6" marker-end="url(#arrow)"/>
            <text v-if="e.label" :x="edgeMid(e).x" :y="edgeMid(e).y - 6" text-anchor="middle" class="edge-label">{{ e.label }}</text>
          </g>
          <defs>
            <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--dcdr-accent)" fill-opacity="0.55"/>
            </marker>
          </defs>
          <!-- 连线预览 -->
          <path v-if="linking" :d="previewPath" fill="none" stroke="var(--dcdr-accent)" stroke-width="2" stroke-dasharray="6 4"/>
          <!-- 节点 -->
          <g v-for="n in nodes" :key="n.id" class="node"
             :transform="`translate(${n.position.x},${n.position.y})`"
             @mousedown="startDrag(n, $event)" @click.stop="selectNode(n)">
            <rect :width="NODE_W" :height="NODE_H" rx="12" class="node-box" :class="['nt-' + n.type, { selected: selectedNode === n, linking: linking && linkFrom === n }]"/>
            <!-- 类型图标：与全站同一套线性语言，不用字母徽标 -->
            <g class="node-icon" transform="translate(12,10) scale(0.625)" :class="'ni-' + n.type" v-html="NODE_ICONS[n.type]" />
            <text x="33" y="22" class="node-type">{{ nodeTypeLabel(n.type) }}</text>
            <text x="12" y="44" class="node-name">{{ n.data?.label || n.id }}</text>
            <text x="12" y="60" class="node-sub">{{ nodeSub(n) }}</text>
            <!-- 连线锚点 -->
            <circle v-if="!linking" :cx="NODE_W" :cy="NODE_H / 2" r="6" class="anchor" @mousedown.stop="startLink(n, $event)"/>
          </g>
        </svg>
        <div v-if="!nodes.length" class="empty-tip">从左侧添加节点开始编排</div>
      </div>

      <!-- 属性面板 -->
      <div class="props" v-if="selectedNode || selectedEdge">
        <div class="p-title" v-if="selectedNode">节点配置</div>
        <template v-if="selectedNode">
          <el-form label-width="72px" size="small">
            <el-form-item label="名称">
              <el-input v-model="selectedNode.data.label" @input="dirty = true" />
            </el-form-item>
            <template v-if="selectedNode.type === 'agent'">
              <el-form-item label="专家">
                <el-select v-model="selectedNode.data.agentId" placeholder="选择专家" @change="dirty = true">
                  <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="输入模板">
                <el-input v-model="selectedNode.data.input" type="textarea" :rows="3"
                          placeholder="支持 ${user_message} 与 ${节点ID.output}" @input="dirty = true" />
              </el-form-item>
            </template>
            <template v-if="selectedNode.type === 'condition'">
              <el-form-item label="判断模式">
                <el-radio-group v-model="selectedNode.data.conditionMode" @change="dirty = true">
                  <el-radio value="llm">自然语言</el-radio>
                  <el-radio value="expr">表达式</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="条件">
                <el-input v-model="selectedNode.data.condition" type="textarea" :rows="3"
                          :placeholder="conditionPlaceholder" @input="dirty = true" />
              </el-form-item>
            </template>
            <template v-if="selectedNode.type === 'router'">
              <el-form-item label="路由规则">
                <el-input v-model="selectedNode.data.input" type="textarea" :rows="3"
                          placeholder="可选: 自然语言分配规则, 如: 涉及代码的任务走 编程专家; 支持 ${nodeId.output}" @input="dirty = true" />
              </el-form-item>
            </template>
            <div class="p-tip" v-if="selectedNode.type === 'router'">该节点只做分析+指派, 不执行任务; 出边标签为候选执行者, LLM 会选最合适的一个继续。</div>
            <div class="p-tip" v-if="selectedNode.type === 'approval'">执行到该节点会暂停, 等待人工审批通过后继续。</div>
            <div class="p-tip" v-if="selectedNode.type === 'merge'">汇聚多个分支, 输出为各前驱节点输出的拼接。</div>
          </el-form>
          <el-button size="small" type="danger" text @click="removeNode(selectedNode)">删除节点</el-button>
        </template>
        <template v-else-if="selectedEdge">
          <div class="p-title">边配置</div>
          <el-form label-width="72px" size="small">
            <el-form-item label="标签">
              <el-input v-model="selectedEdge.label" :placeholder="edgeLabelPlaceholder" @input="dirty = true" />
            </el-form-item>
          </el-form>
          <el-button size="small" type="danger" text @click="removeEdge(selectedEdge)">删除边</el-button>
        </template>
      </div>
    </div>

    <!-- 保存对话框 (名称/描述/变更说明) -->
    <el-dialog v-model="saveDialogVisible" title="保存工作流" width="440px">
      <el-form label-width="72px">
        <el-form-item label="名称">
          <el-input v-model="wfMeta.name" placeholder="工作流名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="wfMeta.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="变更说明">
          <el-input v-model="wfMeta.changeNote" placeholder="本次改动说明 (存入版本历史)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 试运行结果 -->
    <el-dialog v-model="runDialogVisible" title="试运行结果" width="560px">
      <div v-if="runResult" class="run-result">
        <div class="run-status" :class="runResult.status">
          状态: {{ runResult.status }} · 耗时 {{ runResult.totalDurationMs }}ms
        </div>
        <div v-if="runResult.error" class="run-error">{{ runResult.error }}</div>
        <div class="run-steps" v-for="s in runResult.steps || []" :key="s.name">
          <b>{{ s.name }}</b> [{{ s.status }}]
          <span v-if="s.error" class="run-error"> {{ s.error }}</span>
        </div>
        <div class="run-final" v-if="runResult.finalOutput">最终输出: {{ runResult.finalOutput }}</div>
      </div>
    </el-dialog>

    <!-- 版本历史 (F-34) -->
    <el-dialog v-model="versionsVisible" title="版本历史" width="560px">
      <el-table :data="versions" size="small" v-loading="versionsLoading">
        <el-table-column prop="version" label="版本" width="60">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="changeNote" label="变更说明" min-width="150" />
        <el-table-column label="时间" min-width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" type="warning" text @click="doRollback(row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getWorkflows, getWorkflow, createWorkflow, updateWorkflow, executeWorkflow,
  getWorkflowVersions, rollbackWorkflowVersion, getAgents, extractMessage
} from '@/api'

// 从工作流页跳转时携带目标工作流 ID, 挂载后自动选中加载
const props = defineProps({ initialWorkflowId: { type: String, default: '' } })

const NODE_W = 150
const NODE_H = 72

const workflows = ref([])
const agents = ref([])
const workflowId = ref('')
const newWorkflow = ref(null)
const dirty = ref(false)
const saving = ref(false)
const running = ref(false)
const nodes = ref([])
const edges = ref([])

const selectedNode = ref(null)
const selectedEdge = ref(null)
const linking = ref(false)
const linkFrom = ref(null)
const linkPos = reactive({ x: 0, y: 0 })
const dragging = ref(null)
const dragOffset = reactive({ x: 0, y: 0 })

const saveDialogVisible = ref(false)
const wfMeta = reactive({ name: '', description: '', changeNote: '' })
const runDialogVisible = ref(false)
const runResult = ref(null)
const versionsVisible = ref(false)
const versionsLoading = ref(false)
const versions = ref([])

const canvasW = ref(1600)
const canvasH = ref(900)

const editableWorkflows = computed(() => (workflows.value || []).filter(w => w.editable !== false && !w.isTemplate))
const canvasWrap = ref(null)

/* 节点类型图标：一处定义，画布与调色板共用，避免两处各画一遍导致风格漂移 */
const NODE_ICONS = {
  agent: '<path d="M12 3L19 6V11.5C19 16 15.8 19.4 12 21C8.2 19.4 5 16 5 11.5V6L12 3Z" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M8.8 11.8L11.2 14.2L15.4 9.6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  condition: '<path d="M12 3.6L20.4 12L12 20.4L3.6 12L12 3.6Z" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M8.8 12H15.2" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  router: '<circle cx="6" cy="6" r="2.4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><circle cx="18" cy="6" r="2.4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><circle cx="12" cy="18" r="2.4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M8.4 6H15.6M6 8.4V12C6 13.1 6.9 14 8 14H12V15.6M18 8.4V12C18 13.1 17.1 14 16 14H12" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  approval: '<rect x="4.6" y="4.6" width="6" height="6" rx="1.6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><rect x="13.4" y="4.6" width="6" height="6" rx="1.6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><rect x="4.6" y="13.4" width="6" height="6" rx="1.6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M13.6 16.4L15.4 18.2L18.6 14.6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  merge: '<path d="M4 8V6C4 4.9 4.9 4 6 4H8M16 4H18C19.1 4 20 4.9 20 6V8M20 16V18C20 19.1 19.1 20 18 20H16M8 20H6C4.9 20 4 19.1 4 18V16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M9 12H15" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>'
}
const PALETTE = [
  { type: 'agent', label: '专家节点' },
  { type: 'condition', label: '条件分支' },
  { type: 'router', label: '路由节点' },
  { type: 'approval', label: '人工审批' },
  { type: 'merge', label: '分支合并' }
]
const nodeTypeLabel = (t) => ({ agent: 'AGENT', condition: 'CONDITION', router: 'ROUTER', approval: 'APPROVAL', merge: 'MERGE' })[t] || 'NODE'
const conditionPlaceholder = computed(() => {
  return selectedNode.value?.data?.conditionMode === 'expr'
    ? "形如 contains(${n1.output},'YES')"
    : '用自然语言描述, 如: 判断上一节点输出是否包含错误信息'
})
const edgeLabelPlaceholder = computed(() => {
  if (!selectedEdge.value) return ''
  const src = nodes.value.find(n => n.id === selectedEdge.value.source)
  if (src?.type === 'condition') return 'condition 出边填: 是 / 否'
  if (src?.type === 'router') return 'router 出边填: 候选执行者名 (如: 编程专家)'
  return '可选边标签'
})
const nodeSub = (n) => {
  if (n.type === 'agent') {
    const a = agents.value.find(x => x.id === n.data?.agentId)
    return a ? a.name : '未选择专家'
  }
  if (n.type === 'condition') return n.data?.conditionMode === 'expr' ? '表达式判断' : '自然语言判断'
  if (n.type === 'router') return '分析并指派执行者'
  if (n.type === 'approval') return '人工审批门禁'
  return '合并汇聚'
}
const fmtTime = (t) => (t ? new Date(t).toLocaleString('zh-CN') : '-')

function nodeCenter(n, side) {
  const x = side === 'out' ? n.position.x + NODE_W : n.position.x
  return { x, y: n.position.y + NODE_H / 2 }
}
function edgePath(e) {
  const s = nodeCenter(nodes.value.find(n => n.id === e.source), 'out')
  const t = nodeCenter(nodes.value.find(n => n.id === e.target), 'in')
  if (!s || !t) return ''
  const dx = Math.max(40, Math.abs(t.x - s.x) / 2)
  return `M ${s.x} ${s.y} C ${s.x + dx} ${s.y}, ${t.x - dx} ${t.y}, ${t.x} ${t.y}`
}
function edgeMid(e) {
  const s = nodeCenter(nodes.value.find(n => n.id === e.source), 'out')
  const t = nodeCenter(nodes.value.find(n => n.id === e.target), 'in')
  if (!s || !t) return { x: 0, y: 0 }
  return { x: (s.x + t.x) / 2, y: (s.y + t.y) / 2 }
}
const previewPath = computed(() => {
  if (!linking.value || !linkFrom.value) return ''
  const s = nodeCenter(linkFrom.value, 'out')
  return `M ${s.x} ${s.y} L ${linkPos.x} ${linkPos.y}`
})

let nodeSeq = 0
function addNode(type) {
  const id = 'n' + (++nodeSeq)
  const label = { agent: '专家', condition: '条件', router: '路由', approval: '审批', merge: '合并' }[type]
  nodes.value.push({
    id,
    type,
    position: { x: 80 + (nodes.value.length % 4) * 220, y: 80 + Math.floor(nodes.value.length / 4) * 120 },
    data: { label: label + ' ' + nodeSeq, conditionMode: type === 'condition' ? 'llm' : undefined, input: type === 'agent' ? '${user_message}' : undefined }
  })
  selectedNode.value = nodes.value[nodes.value.length - 1]
  selectedEdge.value = null
  dirty.value = true
}
function removeNode(n) {
  nodes.value = nodes.value.filter(x => x.id !== n.id)
  edges.value = edges.value.filter(e => e.source !== n.id && e.target !== n.id)
  selectedNode.value = null
  dirty.value = true
}
function removeEdge(e) {
  edges.value = edges.value.filter(x => x !== e)
  selectedEdge.value = null
  dirty.value = true
}
function selectNode(n) {
  if (linking.value && linkFrom.value && linkFrom.value.id !== n.id) {
    // 完成连线
    if (!edges.value.some(e => e.source === linkFrom.value.id && e.target === n.id)) {
      const from = linkFrom.value
      // condition 出边默认 是; router 出边默认取目标节点名作为候选执行者
      let label = from.type === 'condition' ? '是' : ''
      if (from.type === 'router') label = n.data?.label || n.id
      edges.value.push({ source: from.id, target: n.id, label })
      dirty.value = true
    }
    linking.value = false
    linkFrom.value = null
    return
  }
  selectedNode.value = n
  selectedEdge.value = null
}
function selectEdge(e) {
  selectedEdge.value = e
  selectedNode.value = null
}
function startDrag(n, ev) {
  dragging.value = n
  dragOffset.x = ev.offsetX - n.position.x
  dragOffset.y = ev.offsetY - n.position.y
}
function onDrag(ev) {
  if (dragging.value) {
    dragging.value.position.x = Math.max(0, ev.offsetX - dragOffset.x)
    dragging.value.position.y = Math.max(0, ev.offsetY - dragOffset.y)
    dirty.value = true
  }
  if (linking.value) {
    linkPos.x = ev.offsetX
    linkPos.y = ev.offsetY
  }
}
function endDrag() { dragging.value = null }
function startLink(n, ev) {
  linking.value = true
  linkFrom.value = n
  linkPos.x = n.position.x + NODE_W
  linkPos.y = n.position.y + NODE_H / 2
  ev.preventDefault()
}

async function loadLists() {
  const [wfs, ags] = await Promise.all([getWorkflows(), getAgents()])
  workflows.value = wfs || []
  agents.value = ags || []
}

function onWorkflowChange(id) {
  const wf = workflows.value.find(w => w.id === id)
  if (!wf) return
  if (dirty.value) {
    ElMessageBox.confirm('当前画布未保存, 切换将丢失改动, 确认?', '提示', { type: 'warning' })
      .then(() => loadWorkflow(wf)).catch(() => {})
  } else {
    loadWorkflow(wf)
  }
}

async function loadWorkflow(wf) {
  try {
    const detail = await getWorkflow(wf.id)
    nodes.value = detail.dag?.nodes || []
    edges.value = detail.dag?.edges || []
    // 恢复节点序号
    for (const n of nodes.value) {
      const seq = parseInt(String(n.id).replace(/\D/g, ''), 10)
      if (!isNaN(seq) && seq > nodeSeq) nodeSeq = seq
    }
    wfMeta.name = detail.name
    wfMeta.description = detail.description
    newWorkflow.value = null
    selectedNode.value = null
    selectedEdge.value = null
    dirty.value = false
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载工作流失败')
  }
}

function createNew() {
  nodes.value = []
  edges.value = []
  nodeSeq = 0
  workflowId.value = ''
  newWorkflow.value = { mode: 'dag' }
  wfMeta.name = ''
  wfMeta.description = ''
  wfMeta.changeNote = '初始版本'
  selectedNode.value = null
  selectedEdge.value = null
  dirty.value = true
}

function save() {
  if (!nodes.value.length) {
    ElMessage.warning('画布为空, 请先添加节点')
    return
  }
  if (!wfMeta.name) wfMeta.name = 'DAG 工作流 ' + new Date().toLocaleDateString('zh-CN')
  saveDialogVisible.value = true
}

async function doSave() {
  if (!wfMeta.name) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const body = {
      name: wfMeta.name,
      description: wfMeta.description,
      mode: 'dag',
      steps: [],
      dag: { nodes: nodes.value, edges: edges.value },
      changeNote: wfMeta.changeNote
    }
    if (workflowId.value) {
      await updateWorkflow(workflowId.value, body)
      ElMessage.success('已保存, 变更已存入版本历史')
    } else {
      const created = await createWorkflow(body)
      workflowId.value = created.id
      newWorkflow.value = null
      ElMessage.success('已创建 DAG 工作流')
      await loadLists()
    }
    dirty.value = false
    saveDialogVisible.value = false
  } catch (e) {
    ElMessage.error(extractMessage(e) || '保存失败')
  } finally {
    saving.value = false
  }
}

async function runDag() {
  if (!nodes.value.length) {
    ElMessage.warning('画布为空')
    return
  }
  let input
  try {
    const r = await ElMessageBox.prompt('请输入试运行的用户消息 (${user_message})', '试运行', { inputValue: '你好' })
    input = r.value
  } catch { return }
  running.value = true
  try {
    let id = workflowId.value
    if (!id) {
      // 未保存: 先静默保存再执行
      const body = { name: wfMeta.name || 'DAG 试运行草稿', mode: 'dag', steps: [], dag: { nodes: nodes.value, edges: edges.value } }
      const created = await createWorkflow(body)
      id = created.id
      workflowId.value = id
      await loadLists()
    }
    runResult.value = await executeWorkflow(id, input)
    runDialogVisible.value = true
  } catch (e) {
    ElMessage.error(extractMessage(e) || '执行失败')
  } finally {
    running.value = false
  }
}

async function loadVersions() {
  if (!workflowId.value) return
  versionsVisible.value = true
  versionsLoading.value = true
  try {
    versions.value = await getWorkflowVersions(workflowId.value)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载版本历史失败')
  } finally {
    versionsLoading.value = false
  }
}

async function doRollback(row) {
  try {
    await ElMessageBox.confirm(`回滚到 v${row.version}? 当前定义会先生成快照, 之后可再回滚。`, '回滚版本', { type: 'warning' })
    const wf = await rollbackWorkflowVersion(workflowId.value, row.version)
    ElMessage.success(`已回滚到 v${row.version}`)
    await loadWorkflow(wf)
    versionsVisible.value = false
  } catch (e) { /* 取消 */ if (e !== 'cancel' && e?.message) ElMessage.error(extractMessage(e)) }
}

onMounted(async () => {
  try {
    await loadLists()
    // 从工作流页跳转而来: 自动选中并加载对应工作流
    if (props.initialWorkflowId) {
      const wf = workflows.value.find(w => w.id === props.initialWorkflowId)
      if (wf) {
        workflowId.value = wf.id
        await loadWorkflow(wf)
      }
    }
  } catch (e) {
    ElMessage.error(extractMessage(e) || '初始化失败')
  }
})
</script>

<style scoped>
.dag-mode { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: var(--dcdr-bg-subtle); }
.dag-header { padding: 16px 24px 0; }
.dag-header h2 { margin: 0 0 2px; font-size: 20px; color: var(--dcdr-text-1); }
.subtitle { margin: 0; font-size: 13px; color: var(--dcdr-text-3); }
.dag-toolbar { display: flex; align-items: center; gap: 10px; padding: 12px 24px; }
.dag-body { flex: 1; display: flex; min-height: 0; margin: 0 24px 20px; gap: 12px; }

.palette { width: 180px; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 12px; flex-shrink: 0; }
.p-title { font-weight: 600; font-size: 13px; color: var(--dcdr-text-1); margin-bottom: 10px; }
.p-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 8px; cursor: pointer;
  font-size: 13px; color: var(--dcdr-text-2); margin-bottom: 6px; border: 1px dashed transparent; }
.p-item:hover { background: var(--dcdr-bg-subtle); border-color: var(--dcdr-border); }
.p-icon { width: 16px; height: 16px; flex: none; }
.p-text { font-size: 12.5px; color: var(--dcdr-text-2); }
.p-item:hover .p-text { color: var(--dcdr-text-1); }
.p-hint { margin-top: 12px; font-size: 12px; color: var(--dcdr-text-3); line-height: 1.8; }

.canvas-wrap { flex: 1; background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner); overflow: auto; position: relative; }
.canvas-svg { display: block; }
.empty-tip { position: absolute; top: 40%; left: 50%; transform: translate(-50%, -50%); color: var(--dcdr-text-3); font-size: 13px; }
.node { cursor: grab; }
.node-box { fill: var(--dcdr-surface-2); stroke: var(--dcdr-border-strong); stroke-width: 1; }
.node-box.nt-agent { stroke: var(--dcdr-accent-line); }
.node-box.nt-condition { stroke: color-mix(in srgb, var(--dcdr-warning) 45%, transparent); }
.node-box.nt-router { stroke: var(--dcdr-accent-line); }
.node-box.nt-approval { stroke: color-mix(in srgb, var(--dcdr-danger) 45%, transparent); }
.node-box.nt-merge { stroke: color-mix(in srgb, var(--dcdr-success) 45%, transparent); }
.node-box.selected { stroke: var(--dcdr-accent); stroke-width: 1.5; filter: drop-shadow(0 0 14px var(--dcdr-accent-wash)); }
.node-box.linking { stroke-dasharray: 6 3; }
.node-icon { color: var(--dcdr-accent); }
.node-type { font-family: var(--dcdr-font-mono); font-size: 8.5px; letter-spacing: 0.6px; fill: var(--dcdr-text-3); }
.node-name { font-size: 12.5px; font-weight: 500; fill: var(--dcdr-text-1); }
.node-sub { font-size: 10px; fill: var(--dcdr-text-3); }
.anchor { fill: var(--dcdr-accent); cursor: crosshair; opacity: 0.5; transition: opacity 0.15s; }
.node:hover .anchor { opacity: 1; }
.node { cursor: grab; }
.ni-condition { color: var(--dcdr-warning); }
.ni-router { color: var(--dcdr-accent-2); }
.ni-approval { color: var(--dcdr-danger); }
.ni-merge { color: var(--dcdr-success); }
.pi-agent { color: var(--dcdr-accent); }
.pi-condition { color: var(--dcdr-warning); }
.pi-router { color: var(--dcdr-accent-2); }
.pi-approval { color: var(--dcdr-danger); }
.pi-merge { color: var(--dcdr-success); }
.edge-path { stroke: var(--dcdr-accent); stroke-opacity: 0.45; }
.edge-path.selected { stroke: var(--dcdr-danger); stroke-opacity: 1; stroke-width: 2; }
.edge-label { font-family: var(--dcdr-font-mono); font-size: 9.5px; fill: var(--dcdr-text-3); }
.anchor:hover { r: 8; }
.edge { cursor: pointer; }
.edge:hover path { stroke: var(--dcdr-danger); }


.props { width: 260px; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 12px; flex-shrink: 0; overflow-y: auto; }
.p-tip { font-size: 12px; color: var(--dcdr-text-3); line-height: 1.8; margin: 6px 0; }

.run-result { font-size: 13px; line-height: 1.8; }
.run-status { font-weight: 600; margin-bottom: 8px; }
.run-status.success { color: var(--dcdr-success); }
.run-status.failed { color: var(--dcdr-danger); }
.run-error { color: var(--dcdr-danger); }
.run-final { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--dcdr-border); color: var(--dcdr-text-2); }
</style>
