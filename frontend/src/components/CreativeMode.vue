<template>
  <div class="creative-mode">
    <aside class="sidebar">
      <div class="sidebar-header">
        <button class="new-btn gradient-orange" @click="newProject">+ 新建项目</button>
      </div>
      <div class="project-list">
        <div v-for="p in projects" :key="p.id" class="project-item" :class="{active: p.id===currentProjectId}" @click="switchProject(p.id)">
          <span>{{ p.name }}</span><span class="meta">{{ p.generationCount }} 张</span>
        </div>
      </div>
    </aside>
    <div class="control-panel">
      <div class="model-card">
        <div class="model-avatar gradient-orange">AI</div>
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
        <select v-model="size" class="select"><option value="1024x1024">1024x1024</option><option value="1024x1536">1024x1536</option><option value="1536x1024">1536x1024</option></select>
      </div>
      <div class="param-row"><span>风格</span>
        <select v-model="styleVal" class="select"><option value="vivid">生动</option><option value="natural">自然</option><option value="anime">动漫</option><option value="oil">油画</option></select>
      </div>
      <button class="gen-btn gradient-green" @click="generate" :disabled="!prompt.trim() || generating">{{ generating ? '生成中...' : '生成' }}</button>
    </div>
    <div class="preview-area">
      <div v-if="images.length===0 && !generating" class="empty"><div class="empty-icon">AI</div><p>等待生成</p><p class="hint">输入提示词并点击生成</p></div>
      <img v-if="currentImg && !generating" :src="currentImg" class="result-img" />
      <div v-if="generating" class="loading"><div class="spinner"></div><p>正在生成中...</p></div>
      <div v-if="images.length>0" class="history">
        <div v-for="(img,idx) in images" :key="idx" class="thumb" :class="{active:selIdx===idx}" @click="selIdx=idx"><img :src="img" /></div>
      </div>
      <div v-if="currentImg && !generating" class="actions">
        <button class="act primary" @click="generate">重新生成</button>
        <a class="act secondary" :href="currentImg" download>下载</a>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getProjects, createProject, generateImage } from '../api'
const props = defineProps({ currentModel: String, imageModels: Array })
const projects = ref([])
const currentProjectId = ref(null)
const prompt = ref('')
const referImage = ref('')
const size = ref('1024x1024')
const styleVal = ref('vivid')
const generating = ref(false)
const images = ref([])
const selIdx = ref(-1)
const modelLabel = computed(() => props.imageModels?.find(x => x.name === props.currentModel)?.label || props.currentModel)
const currentImg = computed(() => selIdx.value >= 0 ? images.value[selIdx.value] : null)
async function loadProjects() { projects.value = await getProjects() }
async function newProject() {
  const p = await createProject({ name: 'New Project', mode: 'image', model: props.currentModel })
  projects.value.unshift(p); currentProjectId.value = p.id; images.value = []; selIdx.value = -1; prompt.value = ''
}
function switchProject(id) { currentProjectId.value = id }
function onUpload(e) { const f = e.target.files[0]; if (!f) return; const r = new FileReader(); r.onload = ev => referImage.value = ev.target.result; r.readAsDataURL(f) }
async function generate() {
  if (!prompt.value.trim() || generating.value) return
  generating.value = true
  try {
    const r = await generateImage({ model: props.currentModel, prompt: prompt.value, size: size.value, style: styleVal.value, referImage: referImage.value || null })
    if (r.imageUrl) { images.value.push(r.imageUrl); selIdx.value = images.value.length - 1 }
  } catch(e) { console.error(e) }
  generating.value = false
}
onMounted(() => loadProjects())
</script>

<style scoped>
.creative-mode{flex:1;display:flex;overflow:hidden;background:#1a1a1a}
.sidebar{width:260px;background:#fff;border-right:1px solid #e8e8e8;display:flex;flex-direction:column}
.sidebar-header{padding:16px;border-bottom:1px solid #f0f0f0}
.new-btn{width:100%;height:40px;color:#fff;border:none;border-radius:8px;font-size:14px;cursor:pointer}
.project-list{flex:1;overflow-y:auto;padding:8px}
.project-item{padding:10px 12px;border-radius:8px;cursor:pointer;margin-bottom:4px;font-size:13px;display:flex;justify-content:space-between}
.project-item:hover{background:#f5f7fa}
.project-item.active{background:#fff0f6;color:#eb2f96}
.meta{font-size:11px;color:#c0c4cc}
.control-panel{width:340px;background:#1e1e1e;color:#e0e0e0;padding:20px;overflow-y:auto}
.model-card{background:#252525;border-radius:10px;padding:12px;display:flex;align-items:center;gap:10px;margin-bottom:16px}
.model-avatar{width:32px;height:32px;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:12px;color:#fff}
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
.gen-btn{width:100%;height:46px;border:none;border-radius:10px;font-size:15px;font-weight:600;color:#fff;cursor:pointer;margin-top:20px}
.gen-btn:disabled{opacity:0.5}
.preview-area{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;position:relative;background:#111}
.empty{text-align:center;color:#555}
.empty-icon{font-size:48px;margin-bottom:16px;opacity:0.4}
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
</style>
