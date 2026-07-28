<template>
  <div class="creative-mode">
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false" title="展开侧边栏">»</button>
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed}">
      <div class="sidebar-header">
        <button class="new-btn gradient-orange" @click="newProject">+ 新建项目</button>
        <button class="collapse-btn" @click="sidebarCollapsed = true" title="收起侧边栏">«</button>
      </div>
      <div class="project-list">
        <div v-for="p in projects" :key="p.id" class="project-item" :class="{active: p.id===currentProjectId}" @click="switchProject(p.id)">
          <input v-if="editingId===p.id" v-model="editingName" class="proj-edit-input" @click.stop
                 @keyup.enter="saveEdit" @keyup.esc="cancelEdit" @blur="saveEdit" />
          <span v-else class="proj-name">{{ p.name }}</span>
          <div class="proj-actions" @click.stop>
            <span class="meta">{{ p.generationCount }} 张</span>
            <button class="icon-btn" title="重命名" @click="startEdit(p)">✎</button>
            <button class="icon-btn danger" title="删除" @click="removeProject(p)">🗑</button>
          </div>
        </div>
      </div>
    </aside>
    <div class="control-panel">
      <div class="model-card">
        <div class="model-avatar"><img :src="logoUrl" class="avatar-img" alt="DCDR" /></div>
        <div><div class="model-name">{{ modelLabel }}</div><div class="model-desc">图片生成模型</div></div>
      </div>
      <div class="section-title">参考图 (可选)</div>
      <div class="upload-zone" @click="$refs.fileInput.click()">
        <span v-if="!referImage" class="placeholder">点击上传参考图</span>
        <img v-else :src="referImage" class="refer-preview" />
        <input type="file" ref="fileInput" accept="image/*" style="display:none" @change="onUpload" />
      </div>
      <div class="section-title">提示词</div>
      <textarea v-model="prompt" class="prompt-input" placeholder="描述你想生成的画面..." rows="5"></textarea>
      <div class="section-title">参数</div>
      <div class="param-row"><span>尺寸</span>
        <select v-model="size" class="select"><option value="1024x1024">1024x1024</option><option value="1024x1536">1024x1536</option><option value="1536x1024">1536x1024</option><option value="2048x2048">2048x2048</option><option value="2688x1536">2688x1536</option><option value="1536x2688">1536x2688</option></select>
      </div>
      <div class="param-row"><span>风格</span>
        <select v-model="styleVal" class="select"><option value="vivid">生动</option><option value="natural">自然</option><option value="anime">动漫</option><option value="oil">油画</option></select>
      </div>
      <div class="section-title">反向提示词 (可选)</div>
      <textarea v-model="negativePrompt" class="prompt-input" placeholder="描述不希望出现的内容，如：低分辨率、肢体畸形、画面过饱和..." rows="3"></textarea>
      <div class="param-row"><span>生成数量</span>
        <select v-model.number="n" class="select"><option :value="1">1</option><option :value="2">2</option><option :value="3">3</option><option :value="4">4</option><option :value="5">5</option><option :value="6">6</option></select>
      </div>
      <div class="param-row"><span>智能改写</span>
        <label class="switch"><input type="checkbox" v-model="promptExtend" /><span class="slider"></span></label>
      </div>
      <div class="param-row"><span>水印</span>
        <label class="switch"><input type="checkbox" v-model="watermark" /><span class="slider"></span></label>
      </div>
      <div class="thread-status" :class="{busy: threadStats && threadStats.available === 0}">
        <span class="ts-dot"></span>
        <span class="ts-text">生成线程 可用 <b>{{ threadStats ? threadStats.available : '-' }}</b> / {{ threadStats ? threadStats.maxPoolSize : '-' }}<template v-if="threadStats">（活跃 {{ threadStats.activeCount }}）</template></span>
      </div>
      <button class="gen-btn gradient-green" @click="generate" :disabled="!prompt.trim() || generating">{{ generating ? '生成中...' : '生成' }}</button>
    </div>
    <div class="preview-area">
      <div v-if="images.length===0 && !generating" class="empty"><img :src="logoUrl" class="empty-icon" alt="DCDR" /><p>等待生成</p><p class="hint">输入提示词并点击生成</p></div>
      <img v-if="currentImg && !generating" :src="currentImg" class="result-img" />
      <div v-if="generating" class="gen-progress">
        <div class="progress-head">
          <span class="progress-text">正在生成图片 {{ doneCount }} / {{ totalCount }}</span>
        </div>
        <div class="loading-grid">
          <div v-for="(g, i) in liveImages" :key="i" class="loading-tile"
               :class="{done: g && g.resultUrl, failed: g && g.status === 'failed'}">
            <img v-if="g && g.resultUrl" :src="g.resultUrl" class="tile-img" />
            <div v-else class="tile-shimmer"></div>
            <div class="tile-bar"><i></i></div>
            <span v-if="g && g.status === 'failed'" class="tile-fail">失败</span>
            <span v-else-if="g && g.resultUrl" class="tile-done">完成</span>
          </div>
        </div>
      </div>
      <div v-if="images.length>0" class="history">
        <div class="hist-pages">
          <button class="hist-nav" :disabled="historyPage<=1" @click="historyPage--" title="上一页">‹</button>
          <div class="thumb-list">
            <div v-for="item in pagedHistory" :key="item.g.id || item.idx" class="thumb" :class="{active:selIdx===item.idx}" @click="selIdx=item.idx"><img :src="item.g.resultUrl" /></div>
          </div>
          <button class="hist-nav" :disabled="historyPage>=historyTotalPages" @click="historyPage++" title="下一页">›</button>
        </div>
        <div class="hist-meta">{{ historyPage }} / {{ historyTotalPages }} · 共 {{ images.length }} 张</div>
      </div>
      <div v-if="currentImg && !generating" class="actions">
        <button class="act primary" @click="regenerate">重新生成</button>
        <button class="act secondary" @click="openDetail(images[selIdx])">查看详情</button>
        <a class="act secondary" :href="currentImg" download>下载</a>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <GenerationDetailDialog
      :detail-gen="detailGen"
      @close="closeDetail"
      @apply-params="applyParams"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getProjects, createProject, generateImage, getGeneration, getGenerations,
  getThreadPoolStats, deleteProject, renameProject
} from '@/api'
import logoUrl from '@/assets/dcdr-logo.png'
import GenerationDetailDialog from '@/components/GenerationDetailDialog.vue'

const props = defineProps({ currentModel: String, imageModels: Array })
const projects = ref([])

// 项目内联重命名状态
const editingId = ref(null)
const editingName = ref('')
const currentProjectId = ref(null)
const prompt = ref('')
const referImage = ref('')
const size = ref('1024x1024')
const styleVal = ref('vivid')
const negativePrompt = ref('')
const n = ref(1)
const promptExtend = ref(true)
const watermark = ref(false)
const seed = ref(null)
const generating = ref(false)
// 生成进度: 总任务数 / 已完成数 / 逐张实时结果
const totalCount = ref(0)
const doneCount = ref(0)
const failedCount = ref(0)
const liveImages = ref([])
// 生成线程池运行态(可用线程等), 仅用于展示
const threadStats = ref(null)
let threadTimer = null
async function loadThreadPoolStats() {
  try { threadStats.value = await getThreadPoolStats() } catch (e) { /* 非关键指标, 忽略 */ }
}
const images = ref([])       // 完整 generation 对象数组
const selIdx = ref(-1)
// 历史缩略图分页
const HISTORY_PAGE_SIZE = 8
const historyPage = ref(1)
const historyTotalPages = computed(() => Math.max(1, Math.ceil(images.value.length / HISTORY_PAGE_SIZE)))
const pagedHistory = computed(() => {
  const start = (historyPage.value - 1) * HISTORY_PAGE_SIZE
  return images.value
    .slice(start, start + HISTORY_PAGE_SIZE)
    .map((g, idx) => ({ g, idx: start + idx }))
})
// 页码越界自动修正(删除/清空后)
watch(historyTotalPages, (n) => { if (historyPage.value > n) historyPage.value = n })
const detailGen = ref(null)    // 当前查看的 generation
const sidebarCollapsed = ref(false)
const modelLabel = computed(() => props.imageModels?.find(x => x.name === props.currentModel)?.label || props.currentModel)
const currentImg = computed(() => selIdx.value >= 0 ? images.value[selIdx.value]?.resultUrl : null)
async function loadProjects() { projects.value = await getProjects() }
// 从后端加载某项目下历史生成图片(刷新/切换项目不丢失)
// newIds: 可选, 本次刚成功生成的图片 id 集合, 用于生成结束后自动定位到新图而非最后一张
async function loadProjectImages(id, newIds = null) {
  if (!id) { images.value = []; selIdx.value = -1; return }
  try {
    const gens = await getGenerations(id)
    images.value = (gens || [])
      .filter(g => g.status === 'completed' && g.resultUrl)
    // 后端按创建时间倒序, index 0 为最新。优先选中本次刚生成的图片(按 id 定位),
    // 否则默认显示最新一张(而非最旧的最后一张)
    let sel = 0
    if (newIds && newIds.length) {
      const found = images.value.findIndex(g => newIds.includes(g.id))
      if (found >= 0) sel = found
    }
    selIdx.value = images.value.length ? sel : -1
    // 历史缩略图翻页到选中项所在页, 保证高亮项可见
    historyPage.value = selIdx.value >= 0 ? Math.floor(selIdx.value / HISTORY_PAGE_SIZE) + 1 : 1
  } catch (e) { console.error('加载历史图片失败', e); images.value = []; selIdx.value = -1 }
}
async function newProject() {
  const p = await createProject({ name: 'New Project', mode: 'image', model: props.currentModel })
  projects.value.unshift(p); currentProjectId.value = p.id; images.value = []; selIdx.value = -1; historyPage.value = 1; prompt.value = ''
}
function openDetail(gen) {
  detailGen.value = gen
}
function closeDetail() {
  detailGen.value = null
}
/** 将详情中的参数套用到当前控制面板 */
function applyParams({ gen, params }) {
  if (params.prompt) prompt.value = params.prompt
  if (params.size) size.value = params.size
  else if (gen.size) size.value = gen.size
  if (params.style) styleVal.value = params.style
  if (params.negativePrompt != null) negativePrompt.value = params.negativePrompt
  if (params.n != null) n.value = params.n
  if (params.promptExtend != null) promptExtend.value = params.promptExtend
  if (params.watermark != null) watermark.value = params.watermark
  closeDetail()
}
// 隐式确保有归属项目(直接生成但尚无项目时), 不重置用户输入
async function ensureProject() {
  if (currentProjectId.value) return
  const p = await createProject({ name: 'New Project', mode: 'image', model: props.currentModel })
  projects.value.unshift(p); currentProjectId.value = p.id
}
async function switchProject(id) {
  currentProjectId.value = id
  await loadProjectImages(id)
}

function startEdit(p) {
  editingId.value = p.id
  editingName.value = p.name
  nextTick(() => {
    const inp = document.querySelector('.proj-edit-input')
    if (inp) inp.focus()
  })
}

async function saveEdit() {
  const id = editingId.value
  if (!id) return
  const name = editingName.value.trim()
  editingId.value = null
  editingName.value = ''
  if (!name) return
  const proj = projects.value.find(x => x.id === id)
  if (proj && proj.name === name) return
  try {
    await renameProject(id, name)
    await loadProjects()
  } catch (e) {
    console.error('重命名项目失败', e)
  }
}

function cancelEdit() {
  editingId.value = null
  editingName.value = ''
}

async function removeProject(p) {
  if (!confirm(`确定删除项目「${p.name}」？该操作不可恢复，项目下所有图片也会一并删除。`)) return
  try {
    await deleteProject(p.id)
    if (currentProjectId.value === p.id) {
      currentProjectId.value = null
      images.value = []
      selIdx.value = -1
    }
    await loadProjects()
    if (!currentProjectId.value && projects.value.length) {
      currentProjectId.value = projects.value[0].id
      await loadProjectImages(currentProjectId.value)
    }
  } catch (e) {
    console.error('删除项目失败', e)
  }
}
function onUpload(e) { const f = e.target.files[0]; if (!f) return; const r = new FileReader(); r.onload = ev => referImage.value = ev.target.result; r.readAsDataURL(f) }
// 轮询生成任务状态, 直到完成/失败或超时
async function pollGeneration(id, interval = 2000, timeout = 180000) {
  const start = Date.now()
  while (Date.now() - start < timeout) {
    const g = await getGeneration(id)
    if (g && g.status && g.status !== 'pending') return g
    await new Promise(r => setTimeout(r, interval))
  }
  return null
}
// 重新生成: 用当前选中图片已保存的 prompt/参数回填控制面板后再提交,
// 这样即使文本框为空(如刷新页面/切换项目后)也能正常重生成, 而不是被守卫静默 return
async function regenerate() {
  const cur = images.value[selIdx.value]
  if (cur) {
    if (cur.prompt) prompt.value = cur.prompt
    if (cur.paramsJson) {
      try {
        const p = JSON.parse(cur.paramsJson)
        if (p.size) size.value = p.size
        if (p.style) styleVal.value = p.style
        if (p.negativePrompt != null) negativePrompt.value = p.negativePrompt
        if (p.n != null) n.value = p.n
        if (p.promptExtend != null) promptExtend.value = p.promptExtend
        if (p.watermark != null) watermark.value = p.watermark
      } catch (e) { console.error('解析重生成参数失败', e) }
    }
  }
  await generate()
}

async function generate() {
  if (!prompt.value.trim() || generating.value) return
  if (!currentProjectId.value) await ensureProject()
  generating.value = true
  doneCount.value = 0
  failedCount.value = 0
  try {
    // 1. 提交任务, 立即拿到 n 条 pending 记录(每张独立一个任务)
    const tasks = await generateImage({
      model: props.currentModel, prompt: prompt.value, size: size.value,
      style: styleVal.value, referImage: referImage.value || null,
      negativePrompt: negativePrompt.value || null,
      n: n.value,
      promptExtend: promptExtend.value,
      watermark: watermark.value,
      seed: seed.value || null,
      projectId: currentProjectId.value
    })
    totalCount.value = tasks.length
    liveImages.value = tasks.map(() => null)
    loadThreadPoolStats()   // 提交后立即反映线程占用
    // 2. 逐张轮询, 每完成一张即时填充并推进进度条
    let timedOut = false
    const newIds = []   // 本次成功生成的图片 id, 用于生成后自动定位到新图
    await Promise.all(tasks.map((t, idx) => (async () => {
      const r = await pollGeneration(t.id)
      if (r && r.status === 'completed' && r.resultUrl) {
        liveImages.value[idx] = r
        doneCount.value++
        if (r.id) newIds.push(r.id)
      } else if (r && r.status === 'failed') {
        liveImages.value[idx] = r   // 标记失败以展示失败角标
        failedCount.value++
      } else {
        timedOut = true
      }
    })()))
    // 3. 整批结束, 用权威数据刷新项目图片与计数, 并定位到刚生成的图片
    //    以下刷新即使失败也不应阻塞按钮复位(各自兜底, 不让 generating 卡死)
    try { await loadProjectImages(currentProjectId.value, newIds) }
    catch (e) { console.error('刷新历史图片失败', e); ElMessage.error('刷新历史图片失败，请手动刷新') }
    try { await loadProjects() }
    catch (e) { console.error('刷新项目列表失败', e) }
    if (failedCount.value) ElMessage.error(`有 ${failedCount.value} 张生成失败`)
    else if (timedOut && doneCount.value === 0) ElMessage.warning('生成超时，请稍后在项目历史中查看')
  } catch (e) {
    console.error(e)
    const msg = e?.response?.data?.message || e?.message || e
    ElMessage.error('提交生成任务失败：' + msg)
  } finally {
    // 关键: 无论成功/失败/刷新异常, 都必须复位 generating, 否则按钮会卡死, 之后点击被静默忽略
    generating.value = false
  }
}
onMounted(async () => {
  await loadProjects()
  if (projects.value.length) {
    currentProjectId.value = projects.value[0].id
    await loadProjectImages(currentProjectId.value)
  }
  await loadThreadPoolStats()
  // 每 2s 刷新线程池状态, 展示实时可用线程
  threadTimer = setInterval(loadThreadPoolStats, 2000)
})
onUnmounted(() => { if (threadTimer) clearInterval(threadTimer) })
</script>

<style scoped>
/* ===== 设计令牌 ===== */
.creative-mode {
  --bg-base: #0c0e14;
  --bg-glow: #1a1530;
  --surface: rgba(255, 255, 255, 0.035);
  --surface-2: rgba(255, 255, 255, 0.06);
  --surface-hover: rgba(255, 255, 255, 0.08);
  --border: rgba(255, 255, 255, 0.08);
  --border-strong: rgba(255, 255, 255, 0.14);
  --text: #e9ebf2;
  --text-2: #9ea4b3;
  --text-3: #646b7d;
  --accent: #8b7ff5;
  --accent-2: #b06bff;
  --accent-glow: rgba(139, 127, 245, 0.35);
  --success: #34d399;
  --warning: #fbbf24;
  --danger: #f87171;
  --radius-card: 14px;
  --radius-input: 10px;
  --radius-pill: 999px;
  --ease: cubic-bezier(0.4, 0, 0.2, 1);
  flex: 1; display: flex; overflow: hidden; position: relative;
  background:
    radial-gradient(1200px 800px at 0% 0%, var(--bg-glow), transparent 55%),
    radial-gradient(1000px 700px at 100% 100%, rgba(118, 75, 162, 0.18), transparent 55%),
    var(--bg-base);
  color: var(--text);
}

/* ===== 自定义滚动条 ===== */
.creative-mode ::-webkit-scrollbar { width: 8px; height: 8px; }
.creative-mode ::-webkit-scrollbar-track { background: transparent; }
.creative-mode ::-webkit-scrollbar-thumb { background: rgba(255, 255, 255, 0.1); border-radius: var(--radius-pill); }
.creative-mode ::-webkit-scrollbar-thumb:hover { background: rgba(255, 255, 255, 0.2); }

/* ===== 侧边栏 ===== */
.sidebar {
  width: 264px; display: flex; flex-direction: column;
  background: rgba(255, 255, 255, 0.025);
  border-right: 1px solid var(--border);
  backdrop-filter: blur(20px);
  transition: width 0.3s var(--ease);
}
.sidebar.collapsed { width: 0; min-width: 0; padding: 0; border-right: none; overflow: hidden; }
.sidebar-header { padding: 18px; border-bottom: 1px solid var(--border); position: relative; }
.collapse-btn {
  position: absolute; right: 10px; top: 18px;
  width: 28px; height: 28px; border: none; border-radius: 8px;
  background: var(--surface); color: var(--text-2); cursor: pointer;
  font-size: 15px; line-height: 1;
  transition: all 0.2s var(--ease);
}
.collapse-btn:hover { background: var(--accent-glow); color: var(--accent); }
.sidebar-expand {
  position: absolute; left: 0; top: 14px; z-index: 50;
  width: 26px; height: 38px; border: none; border-radius: 0 10px 10px 0;
  background: linear-gradient(135deg, var(--accent), var(--accent-2));
  color: #fff; cursor: pointer; font-size: 15px;
  box-shadow: 4px 0 16px var(--accent-glow);
  transition: all 0.2s var(--ease);
}
.sidebar-expand:hover { filter: brightness(1.1); }
.new-btn {
  width: 100%; height: 42px; color: #fff; border: none;
  border-radius: var(--radius-input); font-size: 14px; font-weight: 600;
  cursor: pointer; letter-spacing: 0.02em;
  box-shadow: 0 4px 18px rgba(250, 112, 154, 0.25);
  transition: all 0.2s var(--ease);
}
.new-btn:hover { filter: brightness(1.08); transform: translateY(-1px); box-shadow: 0 6px 22px rgba(250, 112, 154, 0.35); }
.new-btn:active { transform: translateY(0); }

.project-list { flex: 1; overflow-y: auto; padding: 10px; }
.project-item {
  padding: 11px 12px; border-radius: 10px; cursor: pointer; margin-bottom: 4px;
  font-size: 13px; display: flex; justify-content: space-between; align-items: center;
  border: 1px solid transparent;
  transition: all 0.18s var(--ease);
}
.project-item:hover { background: var(--surface); border-color: var(--border); }
.project-item.active {
  background: linear-gradient(135deg, rgba(139, 127, 245, 0.16), rgba(176, 107, 255, 0.1));
  border-color: rgba(139, 127, 245, 0.3);
  color: #fff;
  box-shadow: inset 0 0 0 1px rgba(139, 127, 245, 0.1);
}
.proj-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.proj-edit-input {
  flex: 1; border: 1px solid var(--accent); border-radius: 8px; padding: 3px 8px;
  font-size: 13px; outline: none; background: var(--surface-2); color: var(--text);
}
.proj-actions { display: flex; align-items: center; gap: 6px; margin-left: 8px; }
.icon-btn {
  border: none; background: transparent; cursor: pointer; color: var(--text-3);
  font-size: 13px; line-height: 1; padding: 3px 5px; border-radius: 6px;
  transition: all 0.18s var(--ease);
}
.icon-btn:hover { background: var(--surface-hover); color: var(--text); }
.icon-btn.danger:hover { color: var(--danger); background: rgba(248, 113, 113, 0.12); }
.meta { font-size: 11px; color: var(--text-3); font-variant-numeric: tabular-nums; }

/* ===== 控制面板 ===== */
.control-panel {
  width: 348px; padding: 22px; overflow-y: auto;
  background: rgba(255, 255, 255, 0.018);
  border-right: 1px solid var(--border);
  backdrop-filter: blur(20px);
}
.model-card {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-card); padding: 14px;
  display: flex; align-items: center; gap: 12px; margin-bottom: 18px;
  transition: all 0.2s var(--ease);
}
.model-card:hover { border-color: var(--border-strong); background: var(--surface-2); }
.model-avatar {
  width: 38px; height: 38px; border-radius: 10px; overflow: hidden;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
  background: linear-gradient(135deg, var(--accent), var(--accent-2));
  box-shadow: 0 4px 14px var(--accent-glow);
}
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.model-name { font-size: 14px; font-weight: 600; letter-spacing: 0.01em; }
.model-desc { font-size: 11px; color: var(--text-3); margin-top: 2px; }

.section-title {
  font-size: 11px; color: var(--text-3); margin: 18px 0 8px;
  text-transform: uppercase; letter-spacing: 0.08em; font-weight: 600;
}
.upload-zone {
  background: var(--surface); border: 1.5px dashed var(--border-strong);
  border-radius: var(--radius-card); padding: 22px; text-align: center; cursor: pointer;
  transition: all 0.2s var(--ease);
}
.upload-zone:hover { border-color: var(--accent); background: var(--surface-2); }
.placeholder { color: var(--text-3); font-size: 13px; }
.refer-preview { max-width: 100%; border-radius: 10px; }

.prompt-input {
  width: 100%; background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-card); padding: 13px; color: var(--text);
  font-size: 14px; resize: vertical; outline: none; font-family: inherit;
  line-height: 1.6; transition: all 0.2s var(--ease);
}
.prompt-input::placeholder { color: var(--text-3); }
.prompt-input:focus { border-color: var(--accent); background: var(--surface-2); box-shadow: 0 0 0 3px var(--accent-glow); }

.param-row {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 10px; font-size: 13px; color: var(--text-2);
}
.seed-input {
  width: 130px; background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-input); padding: 7px 10px; color: var(--text);
  font-size: 13px; outline: none; transition: all 0.2s var(--ease);
}
.seed-input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }

.select {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-input); padding: 7px 12px; color: var(--text);
  font-size: 13px; outline: none; cursor: pointer; min-width: 130px;
  transition: all 0.2s var(--ease);
}
.select:hover { border-color: var(--border-strong); background: var(--surface-2); }
.select:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }
.select option { background: #1a1d2b; color: var(--text); }

.switch { position: relative; display: inline-block; width: 42px; height: 24px; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch .slider {
  position: absolute; cursor: pointer; inset: 0;
  background: rgba(255, 255, 255, 0.1); border-radius: var(--radius-pill);
  transition: 0.25s var(--ease); border: 1px solid var(--border);
}
.switch .slider:before {
  content: ""; position: absolute; height: 18px; width: 18px; left: 2px; top: 2px;
  background: var(--text-3); border-radius: 50%; transition: 0.25s var(--ease);
}
.switch input:checked + .slider {
  background: linear-gradient(135deg, var(--success), #2dd4bf);
  border-color: transparent; box-shadow: 0 0 12px rgba(52, 211, 153, 0.4);
}
.switch input:checked + .slider:before { transform: translateX(18px); background: #fff; }

.thread-status {
  display: flex; align-items: center; gap: 9px; margin-top: 18px;
  padding: 10px 12px; border-radius: 10px;
  background: var(--surface); border: 1px solid var(--border);
  font-size: 12px; color: var(--text-2);
}
.thread-status .ts-dot {
  width: 8px; height: 8px; border-radius: 50%; background: var(--success);
  box-shadow: 0 0 0 3px rgba(52, 211, 153, 0.18); flex-shrink: 0;
  animation: pulse-dot 2s ease-in-out infinite;
}
.thread-status.busy .ts-dot { background: var(--warning); box-shadow: 0 0 0 3px rgba(251, 191, 36, 0.18); }
.thread-status .ts-text { line-height: 1.2; }
.thread-status .ts-text b { color: var(--accent); font-weight: 600; }
@keyframes pulse-dot { 0%, 100% { opacity: 1; } 50% { opacity: 0.6; } }

.gen-btn {
  width: 100%; height: 48px; border: none;
  border-radius: var(--radius-card); font-size: 15px; font-weight: 600;
  color: #fff; cursor: pointer; margin-top: 22px; letter-spacing: 0.02em;
  box-shadow: 0 6px 24px rgba(67, 233, 123, 0.3);
  transition: all 0.2s var(--ease);
}
.gen-btn:hover:not(:disabled) { filter: brightness(1.08); transform: translateY(-1px); box-shadow: 0 8px 28px rgba(67, 233, 123, 0.4); }
.gen-btn:active:not(:disabled) { transform: translateY(0); }
.gen-btn:disabled { opacity: 0.45; cursor: not-allowed; }

/* ===== 预览区 ===== */
.preview-area {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  position: relative; background: rgba(0, 0, 0, 0.2);
}
.empty { text-align: center; color: var(--text-3); }
.empty-icon {
  width: 80px; height: 80px; border-radius: 18px; object-fit: cover;
  margin-bottom: 18px; opacity: 0.4; filter: grayscale(0.3);
  animation: float 4s ease-in-out infinite;
}
.empty p { color: var(--text-2); font-size: 15px; font-weight: 500; }
.hint { font-size: 13px; color: var(--text-3); margin-top: 4px; font-weight: 400 !important; }
@keyframes float { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-8px); } }

.result-img {
  max-width: 85%; max-height: 75vh; border-radius: 14px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.6), 0 0 0 1px var(--border);
  animation: fadein 0.4s var(--ease);
}

.loading-grid {
  flex: 1; display: flex; flex-wrap: wrap; align-content: center; justify-content: center;
  gap: 16px; overflow: auto; margin-top: 20px; width: 100%; padding: 8px;
}
.loading-tile {
  position: relative; width: 168px; height: 168px; border-radius: 14px;
  overflow: hidden; background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
}
.loading-tile .tile-shimmer {
  position: absolute; inset: 0;
  background: linear-gradient(100deg, rgba(255, 255, 255, 0.02) 30%, rgba(255, 255, 255, 0.08) 50%, rgba(255, 255, 255, 0.02) 70%);
  background-size: 200% 100%; animation: shimmer 1.4s ease-in-out infinite;
}
.loading-tile .tile-img { width: 100%; height: 100%; object-fit: cover; animation: fadein 0.4s ease; }
.loading-tile .tile-fail {
  position: absolute; left: 0; right: 0; bottom: 0; padding: 6px 0; text-align: center;
  background: rgba(248, 113, 113, 0.9); color: #fff; font-size: 12px; font-weight: 500;
  backdrop-filter: blur(4px);
}
.loading-tile .tile-done {
  position: absolute; right: 6px; top: 6px; padding: 2px 9px; border-radius: var(--radius-pill);
  background: rgba(52, 211, 153, 0.92); color: #04130d; font-size: 11px; font-weight: 600;
}
.loading-tile .tile-bar { position: absolute; left: 0; right: 0; bottom: 0; height: 4px; background: rgba(0, 0, 0, 0.3); overflow: hidden; }
.loading-tile .tile-bar > i { display: block; height: 100%; width: 40%; background: linear-gradient(90deg, var(--accent), var(--success)); animation: tile-indeterminate 1.3s ease-in-out infinite; }
.loading-tile.done .tile-bar > i { width: 100%; animation: none; background: var(--success); }
.loading-tile.failed .tile-bar > i { width: 100%; animation: none; background: var(--danger); }
@keyframes tile-indeterminate { 0% { transform: translateX(-110%); } 100% { transform: translateX(260%); } }
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
@keyframes fadein { from { opacity: 0; } to { opacity: 1; } }

.gen-progress { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; width: 100%; }
.progress-head { width: 100%; max-width: 420px; color: var(--text); font-size: 14px; margin-bottom: 10px; font-weight: 500; }

.history {
  position: absolute; right: 18px; top: 18px;
  display: flex; flex-direction: column; gap: 8px;
  padding: 8px; border-radius: 14px;
  background: rgba(0, 0, 0, 0.3); backdrop-filter: blur(12px);
  border: 1px solid var(--border);
  max-height: calc(100% - 100px);
}
.hist-pages { display: flex; align-items: center; gap: 6px; }
.thumb-list {
  display: flex; flex-direction: column; gap: 6px;
  overflow-y: auto; max-height: calc(100vh - 240px); padding: 2px;
}
.hist-nav {
  width: 26px; height: 26px; flex-shrink: 0; border: 1px solid var(--border);
  border-radius: 8px; background: var(--surface); color: var(--text-2);
  cursor: pointer; font-size: 16px; line-height: 1;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.18s var(--ease);
}
.hist-nav:hover:not(:disabled) { background: var(--accent-glow); color: var(--accent); border-color: var(--accent); }
.hist-nav:disabled { opacity: 0.3; cursor: not-allowed; }
.hist-meta { font-size: 11px; color: var(--text-3); text-align: center; font-variant-numeric: tabular-nums; padding-top: 2px; }
.thumb {
  width: 60px; height: 60px; border-radius: 10px; overflow: hidden; cursor: pointer;
  border: 2px solid transparent; opacity: 0.5;
  transition: all 0.2s var(--ease);
}
.thumb:hover { opacity: 0.85; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.thumb.active { opacity: 1; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }

.actions { position: absolute; bottom: 30px; display: flex; gap: 10px; }
.act {
  padding: 9px 20px; border: 1px solid var(--border); border-radius: var(--radius-pill);
  font-size: 13px; cursor: pointer; text-decoration: none; display: inline-flex; align-items: center;
  font-weight: 500; backdrop-filter: blur(12px);
  transition: all 0.2s var(--ease);
}
.act.primary {
  background: linear-gradient(135deg, var(--success), #2dd4bf); color: #04130d;
  border-color: transparent; box-shadow: 0 4px 18px rgba(52, 211, 153, 0.3);
}
.act.primary:hover { filter: brightness(1.08); transform: translateY(-1px); }
.act.secondary { background: rgba(255, 255, 255, 0.08); color: var(--text); }
.act.secondary:hover { background: rgba(255, 255, 255, 0.15); border-color: var(--border-strong); }
</style>
