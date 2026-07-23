<template>
  <div class="creative-mode">
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
      <button class="gen-btn gradient-green" @click="generate" :disabled="!prompt.trim() || generating">{{ generating ? '生成中...' : '生成' }}</button>
    </div>
    <div class="preview-area">
      <div v-if="images.length===0 && !generating" class="empty"><img :src="logoUrl" class="empty-icon" alt="DCDR" /><p>等待生成</p><p class="hint">输入提示词并点击生成</p></div>
      <img v-if="currentImg && !generating" :src="currentImg" class="result-img" />
      <div v-if="generating" class="loading"><div class="spinner"></div><p>正在生成中...</p></div>
      <div v-if="images.length>0" class="history">
        <div v-for="(g,idx) in images" :key="g.id || idx" class="thumb" :class="{active:selIdx===idx}" @click="selIdx=idx"><img :src="g.resultUrl" /></div>
      </div>
      <div v-if="currentImg && !generating" class="actions">
        <button class="act primary" @click="generate">重新生成</button>
        <button class="act secondary" @click="openDetail(images[selIdx])">查看详情</button>
        <a class="act secondary" :href="currentImg" download>下载</a>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <Teleport to="body">
      <div v-if="showDetail && detailGen" class="img-viewer" @click.self="closeDetail">
        <button class="viewer-close" @click="closeDetail">&times;</button>
        <div class="viewer-stage">
          <img :src="detailGen.resultUrl" class="viewer-img" />
        </div>
        <div class="viewer-panel">
          <div class="vp-head">
            <p class="vp-title">{{ detailGen.prompt || '无提示词' }}</p>
            <span class="vp-tag">{{ detailGen.status === 'completed' ? '已完成' : detailGen.status }}</span>
          </div>
          <div class="vp-section">
            <span class="vp-label">提示词</span>
            <pre class="vp-prompt">{{ detailGen.prompt }}</pre>
          </div>
          <div v-if="detailGen.paramsJson" class="vp-section">
            <span class="vp-label">生成参数</span>
            <div class="vp-grid">
              <div class="vp-cell"><span>尺寸</span><b>{{ detailGen.size || '-' }}</b></div>
              <div class="vp-cell"><span>风格</span><b>{{ styleLabel(parseParam('style')) }}</b></div>
              <div class="vp-cell"><span>数量</span><b>{{ parseParam('n') || 1 }}</b></div>
              <div class="vp-cell"><span>随机种子</span><b>{{ parseParam('seed') != null ? parseParam('seed') : '-' }}</b></div>
              <div class="vp-cell"><span>模型</span><b>{{ detailGen.model || '-' }}</b></div>
              <div class="vp-cell"><span>智能改写</span><b>{{ parseParam('promptExtend') ? '开' : '关' }}</b></div>
              <div class="vp-cell"><span>水印</span><b>{{ parseParam('watermark') ? '开' : '关' }}</b></div>
            </div>
          </div>
          <div v-if="parseParam('negativePrompt')" class="vp-section">
            <span class="vp-label">反向提示词</span>
            <pre class="vp-prompt">{{ parseParam('negativePrompt') }}</pre>
          </div>
          <div class="vp-section">
            <span class="vp-label">元信息</span>
            <div class="vp-grid">
              <div class="vp-cell"><span>耗时</span><b>{{ formatDuration(detailGen.durationMs) }}</b></div>
              <div class="vp-cell"><span>创建时间</span><b>{{ formatDate(detailGen.createdAt) }}</b></div>
            </div>
          </div>
          <div class="vp-actions">
            <button class="vp-btn ghost" @click="applyParams(detailGen)">套用参数</button>
          </div>
          <a class="vp-download" :href="detailGen.resultUrl" download>&#x2B07; 下载图片</a>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { getProjects, createProject, generateImage, getGeneration, getGenerations, deleteProject, renameProject } from '../api'
import logoUrl from '../assets/dcdr-logo.png'
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
const images = ref([])       // 完整 generation 对象数组
const selIdx = ref(-1)
const showDetail = ref(false)  // 详情弹窗
const detailGen = ref(null)    // 当前查看的 generation
const sidebarCollapsed = ref(false)
const modelLabel = computed(() => props.imageModels?.find(x => x.name === props.currentModel)?.label || props.currentModel)
// qwen-image-2.0 文生图接口不支持参考图, 图生图需走 qwen-image-edit
const refSupported = computed(() => !props.currentModel?.includes('qwen-image-2.0'))
const currentImg = computed(() => selIdx.value >= 0 ? images.value[selIdx.value]?.resultUrl : null)
async function loadProjects() { projects.value = await getProjects() }
// 从后端加载某项目下历史生成图片(刷新/切换项目不丢失)
async function loadProjectImages(id) {
  if (!id) { images.value = []; selIdx.value = -1; return }
  try {
    const gens = await getGenerations(id)
    images.value = (gens || [])
      .filter(g => g.status === 'completed' && g.resultUrl)
    selIdx.value = images.value.length ? images.value.length - 1 : -1
  } catch (e) { console.error('加载历史图片失败', e); images.value = []; selIdx.value = -1 }
}
async function newProject() {
  const p = await createProject({ name: 'New Project', mode: 'image', model: props.currentModel })
  projects.value.unshift(p); currentProjectId.value = p.id; images.value = []; selIdx.value = -1; prompt.value = ''
}
function openDetail(gen) {
  detailGen.value = gen
  showDetail.value = true
}
function closeDetail() {
  showDetail.value = false
  detailGen.value = null
}
/** 从 paramsJson 中解析参数 */
function parseParam(key) {
  if (!detailGen.value?.paramsJson) return null
  try {
    const params = JSON.parse(detailGen.value.paramsJson)
    return params[key] ?? null
  } catch { return null }
}
function styleLabel(v) {
  const map = { vivid: '生动', natural: '自然', anime: '动漫', oil: '油画' }
  return map[v] || v || '-'
}
function formatDuration(ms) {
  if (!ms) return '-'
  const s = Math.round(ms / 1000)
  if (s < 60) return s + 's'
  return Math.floor(s / 60) + 'm' + (s % 60) + 's'
}
function formatDate(dt) {
  if (!dt) return '-'
  const d = new Date(dt)
  return `${d.getMonth()+1}/${d.getDate()} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}
/** 将详情中的参数套用到当前控制面板 */
function applyParams(gen) {
  try {
    const params = gen.paramsJson ? JSON.parse(gen.paramsJson) : {}
    if (params.prompt) prompt.value = params.prompt
    if (params.size) size.value = params.size
    else if (gen.size) size.value = gen.size
    if (params.style) styleVal.value = params.style
    if (params.negativePrompt != null) negativePrompt.value = params.negativePrompt
    if (params.n != null) n.value = params.n
    if (params.promptExtend != null) promptExtend.value = params.promptExtend
    if (params.watermark != null) watermark.value = params.watermark
    closeDetail()
  } catch(e) { console.error('解析参数失败', e) }
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
async function generate() {
  if (!prompt.value.trim() || generating.value) return
  if (!currentProjectId.value) await ensureProject()
  generating.value = true
  try {
    // 1. 提交任务, 立即拿到 pending 记录
    const task = await generateImage({
      model: props.currentModel, prompt: prompt.value, size: size.value,
      style: styleVal.value, referImage: referImage.value || null,
      negativePrompt: negativePrompt.value || null,
      n: n.value,
      promptExtend: promptExtend.value,
      watermark: watermark.value,
      seed: seed.value || null,
      projectId: currentProjectId.value
    })
    // 2. 轮询直到出结果
    const result = await pollGeneration(task.id)
    if (result && result.status === 'completed' && result.resultUrl) {
      // n>1 时后端会落多条生成记录, 这里直接刷新项目图片以展示全部
      await loadProjectImages(currentProjectId.value)
      await loadProjects()   // 刷新侧边栏的项目计数
    } else if (result && result.status === 'failed') {
      ElMessage.error(result.errorMessage || '生成失败，请稍后重试')
    } else {
      ElMessage.warning('生成超时，请稍后在项目历史中查看')
    }
  } catch(e) { console.error(e) }
  generating.value = false
}
onMounted(async () => {
  await loadProjects()
  if (projects.value.length) {
    currentProjectId.value = projects.value[0].id
    await loadProjectImages(currentProjectId.value)
  }
})
</script>

<style scoped>
.creative-mode{flex:1;display:flex;overflow:hidden;background:#1a1a1a;position:relative}
.sidebar{width:260px;background:#fff;border-right:1px solid #e8e8e8;display:flex;flex-direction:column;transition:width 0.25s ease}
.sidebar.collapsed{width:0;min-width:0;padding:0;border-right:none;overflow:hidden}
.sidebar-header{padding:16px;border-bottom:1px solid #f0f0f0;position:relative}
.collapse-btn{position:absolute;right:10px;top:18px;width:26px;height:26px;border:none;border-radius:6px;background:#f5f7fa;color:#999;cursor:pointer;font-size:16px;line-height:1}
.collapse-btn:hover{background:rgba(102,126,234,0.12);color:#667eea}
.sidebar-expand{position:absolute;left:0;top:14px;z-index:50;width:26px;height:36px;border:none;border-radius:0 8px 8px 0;background:#667eea;color:#fff;cursor:pointer;font-size:16px;box-shadow:2px 0 8px rgba(0,0,0,0.15)}
.sidebar-expand:hover{background:#5568d3}
.new-btn{width:100%;height:40px;color:#fff;border:none;border-radius:8px;font-size:14px;cursor:pointer}
.project-list{flex:1;overflow-y:auto;padding:8px}
.project-item{padding:10px 12px;border-radius:8px;cursor:pointer;margin-bottom:4px;font-size:13px;display:flex;justify-content:space-between;align-items:center}
.project-item:hover{background:#f5f7fa}
.project-item.active{background:#fff0f6;color:#eb2f96}
.proj-name{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.proj-edit-input{flex:1;border:1px solid #ff85c0;border-radius:4px;padding:2px 6px;font-size:13px;outline:none}
.proj-actions{display:flex;align-items:center;gap:6px;margin-left:8px}
.icon-btn{border:none;background:transparent;cursor:pointer;color:#999;font-size:13px;line-height:1;padding:2px 4px;border-radius:4px}
.icon-btn:hover{background:rgba(0,0,0,0.06);color:#333}
.icon-btn.danger:hover{color:#f56c6c}
.meta{font-size:11px;color:#c0c4cc}
.control-panel{width:340px;background:#1e1e1e;color:#e0e0e0;padding:20px;overflow-y:auto}
.model-card{background:#252525;border-radius:10px;padding:12px;display:flex;align-items:center;gap:10px;margin-bottom:16px}
.model-avatar{width:32px;height:32px;border-radius:8px;overflow:hidden;display:flex;align-items:center;justify-content:center}
.avatar-img{width:100%;height:100%;object-fit:cover}
.model-name{font-size:14px;font-weight:600}
.model-desc{font-size:11px;color:#777}
.section-title{font-size:12px;color:#888;margin:16px 0 8px}
.upload-zone{background:#252525;border:1px dashed #444;border-radius:10px;padding:20px;text-align:center;cursor:pointer}
.placeholder{color:#666;font-size:13px}
.refer-preview{max-width:100%;border-radius:8px}
.prompt-input{width:100%;background:#252525;border:1px solid #333;border-radius:10px;padding:12px;color:#e0e0e0;font-size:14px;resize:vertical;outline:none;font-family:inherit}
.prompt-input:focus{border-color:#667eea}
.param-row{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;font-size:13px}
.select{background:#252525;border:1px solid #333;border-radius:8px;padding:6px 12px;color:#ddd;font-size:13px}
.seed-input{width:130px;background:#252525;border:1px solid #333;border-radius:8px;padding:6px 10px;color:#ddd;font-size:13px;outline:none}
.seed-input:focus{border-color:#667eea}
.switch{position:relative;display:inline-block;width:40px;height:22px}
.switch input{opacity:0;width:0;height:0}
.switch .slider{position:absolute;cursor:pointer;inset:0;background:#333;border-radius:999px;transition:0.2s}
.switch .slider:before{content:"";position:absolute;height:16px;width:16px;left:3px;top:3px;background:#999;border-radius:50%;transition:0.2s}
.switch input:checked + .slider{background:#43e97b}
.switch input:checked + .slider:before{transform:translateX(18px);background:#fff}
.gen-btn{width:100%;height:46px;border:none;border-radius:10px;font-size:15px;font-weight:600;color:#fff;cursor:pointer;margin-top:20px}
.gen-btn:disabled{opacity:0.5}
.preview-area{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;position:relative;background:#111}
.empty{text-align:center;color:#555}
.empty-icon{width:72px;height:72px;border-radius:16px;object-fit:cover;margin-bottom:16px;opacity:0.5}
.hint{font-size:13px;color:#444}
.result-img{max-width:85%;max-height:75vh;border-radius:12px;box-shadow:0 8px 40px rgba(0,0,0,0.5)}
.loading{text-align:center;color:#666}
.spinner{width:48px;height:48px;border:4px solid #333;border-top-color:#667eea;border-radius:50%;animation:spin 0.8s linear infinite;margin:0 auto 12px}
@keyframes spin{to{transform:rotate(360deg)}}
.history{position:absolute;right:16px;top:60px;display:flex;flex-direction:column;gap:6px}
.thumb{width:56px;height:56px;border-radius:8px;overflow:hidden;cursor:pointer;border:2px solid transparent;opacity:0.6}
.thumb img{width:100%;height:100%;object-fit:cover}
.thumb.active{opacity:1;border-color:#667eea}
.actions{position:absolute;bottom:28px;display:flex;gap:10px}
.act{padding:8px 18px;border:none;border-radius:8px;font-size:13px;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center}
.act.primary{background:#43e97b;color:#000}
.act.secondary{background:rgba(255,255,255,0.08);color:#bbb}
.img-viewer{position:fixed;inset:0;z-index:200;background:rgba(0,0,0,0.85);display:flex;backdrop-filter:blur(4px)}
.viewer-close{position:absolute;top:16px;right:16px;z-index:210;width:40px;height:40px;border-radius:50%;background:rgba(255,255,255,0.12);border:none;color:#fff;font-size:18px;cursor:pointer;display:flex;align-items:center;justify-content:center}
.viewer-close:hover{background:rgba(255,255,255,0.25)}
.viewer-stage{flex:1;display:flex;align-items:center;justify-content:center;overflow:hidden;cursor:zoom-out}
.viewer-img{max-width:90%;max-height:90%;object-fit:contain;border-radius:8px;box-shadow:0 12px 60px rgba(0,0,0,0.6);transition:transform 0.1s ease-out}
.viewer-panel{width:360px;background:#1e1e1e;color:#e0e0e0;display:flex;flex-direction:column;padding:20px;overflow-y:auto;border-left:1px solid #333;gap:18px}
.vp-head{display:flex;flex-direction:column;gap:8px}
.vp-title{font-size:16px;font-weight:600;line-height:1.4;margin:0;word-break:break-word}
.vp-tag{align-self:flex-start;font-size:11px;padding:2px 8px;border-radius:6px;background:rgba(67,233,123,0.12);color:#43e97b;border:1px solid rgba(67,233,123,0.25)}
.vp-section{display:flex;flex-direction:column;gap:6px}
.vp-label{font-size:11px;color:#888;text-transform:uppercase;letter-spacing:0.05em}
.vp-prompt{background:#252525;border:1px solid #333;border-radius:10px;padding:12px;font-size:13px;line-height:1.6;color:#ccc;white-space:pre-wrap;word-break:break-word;max-height:160px;overflow-y:auto}
.vp-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.vp-cell{background:#252525;border:1px solid #333;border-radius:10px;padding:10px;display:flex;flex-direction:column;gap:4px}
.vp-cell span{font-size:11px;color:#888}
.vp-cell b{font-size:13px;font-weight:600;color:#e0e0e0;font-family:monospace}
.vp-actions{display:flex;gap:10px}
.vp-btn{flex:1;padding:10px;border:none;border-radius:10px;font-size:13px;font-weight:600;cursor:pointer}
.vp-btn.ghost{background:rgba(102,126,234,0.15);color:#a5b4fc}
.vp-btn.ghost:hover{background:rgba(102,126,234,0.3)}
.vp-download{display:flex;align-items:center;justify-content:center;gap:6px;padding:12px;border-radius:10px;background:#43e97b;color:#000;font-weight:700;text-decoration:none;font-size:14px}
.vp-download:hover{background:#37d96f}
.viewer-zoom{position:absolute;bottom:20px;left:50%;transform:translateX(-50%);display:flex;align-items:center;gap:8px;background:rgba(0,0,0,0.5);padding:6px 12px;border-radius:999px;color:#fff;font-size:13px;backdrop-filter:blur(4px)}
.viewer-zoom button{width:30px;height:30px;border-radius:50%;border:none;background:rgba(255,255,255,0.15);color:#fff;cursor:pointer;font-size:16px}
.viewer-zoom button:hover{background:rgba(255,255,255,0.3)}
.viewer-zoom .reset{width:auto;padding:0 12px;border-radius:14px;font-size:12px}
</style>
