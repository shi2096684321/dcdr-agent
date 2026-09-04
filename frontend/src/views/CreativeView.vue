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
      <!-- 图片 / 视频 生成切换 -->
      <div class="gen-type-tabs">
        <div class="gtab" :class="{active: genType==='image'}" @click="genType='image'">图片生成</div>
        <div class="gtab" :class="{active: genType==='video'}" @click="genType='video'">视频生成</div>
      </div>
      <template v-if="genType==='image'">
      <div class="model-card">
        <div class="model-avatar"><img :src="logoUrl" class="avatar-img" alt="DCDR" /></div>
        <div class="model-select-wrap">
          <el-select :model-value="selectedImageModel" @update:model-value="onSelectImageModel" placeholder="选择图片模型" size="default" style="width:100%">
            <el-option v-for="m in imageModels" :key="m.name" :label="m.label" :value="m.name" />
          </el-select>
        </div>
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
      <div class="thread-status" :class="{busy: threadStats && threadStats.imagePool && threadStats.imagePool.available === 0}">
        <span class="ts-dot"></span>
        <span class="ts-text">生成线程 可用 <b>{{ threadStats?.imagePool ? threadStats.imagePool.available : '-' }}</b> / {{ threadStats?.imagePool ? threadStats.imagePool.maxPoolSize : '-' }}<template v-if="threadStats?.imagePool">（活跃 {{ threadStats.imagePool.activeCount }}）</template></span>
      </div>
      <button class="gen-btn gradient-green" @click="generate" :disabled="!prompt.trim() || generating">{{ generating ? '生成中...' : '生成' }}</button>
      </template>

      <!-- 视频生成控制面板 -->
      <template v-else>
        <div class="model-card">
          <div class="model-avatar"><img :src="logoUrl" class="avatar-img" alt="DCDR" /></div>
          <div class="model-select-wrap">
          <el-select :model-value="selectedVideoModel" @update:model-value="onSelectVideoModel" placeholder="选择视频模型" size="default" style="width:100%">
              <el-option v-for="m in videoModels" :key="m.name" :label="m.label" :value="m.name" />
          </el-select>
          </div>
        </div>
        <div class="section-title">提示词</div>
        <textarea v-model="videoPrompt" class="prompt-input" placeholder="描述你想要生成的视频画面..." rows="5"></textarea>
        <div class="section-title">首帧参考图 (可选)</div>
        <div class="upload-zone" @click="$refs.videoFileInput.click()">
          <span v-if="!videoReferImage" class="placeholder">点击上传首帧参考图（图生视频）</span>
          <img v-else :src="videoReferImage" class="refer-preview" />
          <input type="file" ref="videoFileInput" accept="image/*" style="display:none" @change="onVideoUpload" />
        </div>
        <template v-if="capEndImage">
          <div class="section-title">尾帧参考图 (首尾帧模式, 可选)</div>
          <div class="upload-zone" @click="$refs.videoEndFileInput.click()">
            <span v-if="!videoEndImage" class="placeholder">点击上传尾帧参考图（需配合首帧）</span>
            <img v-else :src="videoEndImage" class="refer-preview" />
            <input type="file" ref="videoEndFileInput" accept="image/*" style="display:none" @change="onVideoEndUpload" />
          </div>
          <p v-if="!videoReferImage" class="param-hint">尾帧需配合首帧参考图一起使用</p>
        </template>
        <template v-if="capNegativePrompt">
          <div class="section-title">反向提示词 (可选)</div>
          <textarea v-model="videoNegativePrompt" class="prompt-input" placeholder="描述不希望出现的画面，例如：模糊、变形、文字..." rows="2"></textarea>
        </template>
        <template v-if="capSeed">
          <div class="param-row"><span>随机种子</span>
            <input v-model.number="videoSeed" type="number" class="num-input" placeholder="留空随机" />
          </div>
        </template>
        <div class="param-row"><span>时长</span>
          <select v-model.number="videoDuration" class="select"><option :value="5">5 秒</option><option :value="10">10 秒</option></select>
        </div>
        <div class="param-row"><span>比例</span>
          <select v-model="videoAspect" class="select"><option value="16:9">16:9 横版</option><option value="9:16">9:16 竖版</option><option value="1:1">1:1 方形</option></select>
        </div>
        <div class="thread-status" :class="{busy: threadStats && threadStats.videoPool && threadStats.videoPool.available === 0}">
          <span class="ts-dot"></span>
          <span class="ts-text">视频线程 可用 <b>{{ threadStats?.videoPool ? threadStats.videoPool.available : '-' }}</b> / {{ threadStats?.videoPool ? threadStats.videoPool.maxPoolSize : '-' }}<template v-if="threadStats?.videoPool">（活跃 {{ threadStats.videoPool.activeCount }}）</template></span>
        </div>
        <button class="gen-btn gradient-green" @click="generateVideo" :disabled="!videoPrompt.trim() || videoGenerating">{{ videoGenerating ? '生成中...' : '生成视频' }}</button>
      </template>
    </div>
    <div class="preview-area">
      <template v-if="genType==='image'">
      <div v-if="images.length===0 && !generating" class="empty"><img :src="logoUrl" class="empty-icon" alt="DCDR" /><p>等待生成</p><p class="hint">输入提示词并点击生成</p></div>
      <!-- 选中失败记录: 用破裂图片占位 + 错误原因 -->
      <div v-else-if="!generating && selectedGenFailed" class="failed-preview">
        <div class="failed-preview-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2" opacity="0.35"/><path d="M9 15l3-6 3 6M10 17h4" stroke-linecap="round" stroke-linejoin="round"/><path d="M5 5l14 14" stroke="currentColor" stroke-opacity="0.6" stroke-dasharray="2 3" stroke-linecap="round"/></svg>
        </div>
        <p class="failed-preview-title">图片生成失败</p>
        <p class="failed-preview-msg" v-if="currentSel && currentSel.errorMessage">{{ currentSel.errorMessage }}</p>
      </div>
      <img v-else-if="currentImg && !generating" :src="currentImg" class="result-img" />
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
      <div v-if="imageTotal>0" class="history">
        <div class="hist-pages">
          <button class="hist-nav" :disabled="historyPage<=1" @click="changeImagePage(historyPage-1)" title="上一页">‹</button>
          <div class="thumb-list">
            <div v-for="item in pagedHistory" :key="item.g.id || item.idx" class="thumb" :class="{active:selIdx===item.idx, failed: item.g.status === 'failed'}" :title="item.g.status === 'failed' ? (item.g.errorMessage || '生成失败') : ''" @click="selIdx=item.idx">
              <img v-if="item.g.resultUrl" :src="item.g.resultUrl" />
              <span v-else class="thumb-broken">🖼</span>
            </div>
          </div>
          <button class="hist-nav" :disabled="historyPage>=historyTotalPages" @click="changeImagePage(historyPage+1)" title="下一页">›</button>
        </div>
        <div class="hist-meta">{{ historyPage }} / {{ historyTotalPages }} · 共 {{ imageTotal }} 张</div>
      </div>
      <div v-if="!generating && (currentImg || currentSel)" class="actions">
        <button class="act primary" @click="regenerate">重新生成</button>
        <button class="act secondary" @click="openDetail(currentSel)">查看详情</button>
        <a v-if="currentImg" class="act secondary" :href="currentImg" download>下载</a>
      </div>
      </template>

      <!-- 视频预览 -->
      <template v-else>
        <div v-if="videoStatus==='pending' || videoStatus==='processing'" class="video-progress">
          <div class="vp-stage-icon">{{ videoStageIcon }}</div>
          <div class="vp-status">{{ videoStageLabel }}</div>
          <div class="vp-bar"><i :style="{ width: videoProgressPercent + '%' }"></i></div>
          <div class="vp-metrics">
            <div class="vp-metric">
              <span class="vp-metric-label">排队耗时</span>
              <span class="vp-metric-value">{{ videoQueueText }}</span>
            </div>
            <div class="vp-metric">
              <span class="vp-metric-label">渲染用时</span>
              <span class="vp-metric-value">{{ videoRenderText }}</span>
            </div>
          </div>
          <div class="vp-stage-track">
            <span class="vp-stage" :class="{active: videoStageIndex>=0, done: videoStageIndex>0}">① 排队</span>
            <span class="vp-stage" :class="{active: videoStageIndex>=1, done: videoStageIndex>1}">② 渲染</span>
            <span class="vp-stage" :class="{active: videoStageIndex>=2, done: videoStageIndex>2}">③ 收尾</span>
          </div>
          <p class="vp-tip">视频生成无真实进度，进度为阶段估算；排队/渲染时间取自后端真实时间节点</p>
        </div>
        <div v-else-if="!currentVideo && !resultVideo && !currentVideoSelFailed" class="empty"><img :src="logoUrl" class="empty-icon" alt="DCDR" /><p>等待生成视频</p><p class="hint">输入提示词并点击生成视频</p></div>
        <!-- 选中失败视频记录: 占位展示 -->
        <div v-else-if="currentVideoSelFailed" class="failed-preview">
          <div class="failed-preview-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2" opacity="0.35"/><path d="M9 15l3-6 3 6M10 17h4" stroke-linecap="round" stroke-linejoin="round"/><path d="M5 5l14 14" stroke="currentColor" stroke-opacity="0.6" stroke-dasharray="2 3" stroke-linecap="round"/></svg></div>
          <p class="failed-preview-title">视频生成失败</p>
          <p class="failed-preview-msg" v-if="currentVideoSelFailed.errorMessage">{{ currentVideoSelFailed.errorMessage }}</p>
        </div>
        <video v-else :src="currentVideo || resultVideo" class="result-img" controls autoplay loop></video>
        <div v-if="(currentVideo || resultVideo || currentVideoSelFailed) && !videoGenerating" class="actions">
          <a v-if="currentVideo || resultVideo" class="act secondary" :href="currentVideo || resultVideo" download>下载</a>
          <button class="act secondary" @click="openDetail(currentVideoSelFailed || videos[videoSelIdx])">查看详情</button>
        </div>
        <!-- 视频历史缩略图 -->
        <div v-if="videoTotal>0" class="history">
          <div class="hist-pages">
            <button class="hist-nav" :disabled="videoHistoryPage<=1" @click="changeVideoPage(videoHistoryPage-1)" title="上一页">‹</button>
            <div class="thumb-list">
              <div v-for="item in pagedVideoHistory" :key="item.g.id || item.idx" class="thumb" :class="{active:videoSelIdx===item.idx, failed: item.g.status === 'failed'}" :title="item.g.status === 'failed' ? (item.g.errorMessage || '生成失败') : ''" @click="videoSelIdx=item.idx">
                <video v-if="item.g.resultUrl" :src="item.g.resultUrl" class="thumb-video" muted preload="metadata"></video>
                <span v-else class="thumb-broken">🎬</span>
                <button class="thumb-detail" title="查看详情" @click.stop="openDetail(item.g)">ⓘ</button>
              </div>
            </div>
            <button class="hist-nav" :disabled="videoHistoryPage>=videoHistoryTotalPages" @click="changeVideoPage(videoHistoryPage+1)" title="下一页">›</button>
          </div>
          <div class="hist-meta">{{ videoHistoryPage }} / {{ videoHistoryTotalPages }} · 共 {{ videoTotal }} 个</div>
        </div>
      </template>
    </div>

    <!-- 详情弹窗 -->
    <GenerationDetailDialog
      :detail-gen="detailGen"
      @close="closeDetail"
      @apply-params="applyParams"
      @apply-video-params="applyVideoParams"
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
import { generateVideo as submitVideoTask, getVideoStatus } from '@/api/video'
import logoUrl from '@/assets/dcdr-logo.png'
import GenerationDetailDialog from '@/components/GenerationDetailDialog.vue'

const props = defineProps({ currentModel: String, imageModels: Array, videoModels: Array })
const emit = defineEmits(['update:currentModel'])
// 图片 / 视频 生成模式切换
const genType = ref('image')
// 图片/视频各自独立持有选中模型(以对应类型为准, 互不干扰)
// 默认取各自类型列表的第一项, 避免共用单一 currentModel 导致的互相串号
const selectedImageModel = ref('')
const selectedVideoModel = ref('')
// 模型列表就绪后, 若尚未选择则默认选中第一项
function syncDefaultModels() {
  if (!selectedImageModel.value && props.imageModels?.length) selectedImageModel.value = props.imageModels[0].name
  if (!selectedVideoModel.value && props.videoModels?.length) selectedVideoModel.value = props.videoModels[0].name
}
// 选中图片模型: 仅更新本地图片状态, 并回传顶栏(创作模式下展示用)
function onSelectImageModel(val) { selectedImageModel.value = val; emit('update:currentModel', val) }
// 选中视频模型: 仅更新本地视频状态
function onSelectVideoModel(val) {
  selectedVideoModel.value = val
  emit('update:currentModel', val)
  // 切换模型后清理不兼容的参数, 避免脏数据带入请求
  videoEndImage.value = ''
  videoNegativePrompt.value = ''
  videoSeed.value = null
}
// 同步顶栏模型: 无论默认初始化 / 手动切换 / 切换 Tab, 顶栏始终显示"当前类型选中的模型"
watch([selectedImageModel, selectedVideoModel, genType], () => {
  emit('update:currentModel', genType.value === 'video' ? selectedVideoModel.value : selectedImageModel.value)
})
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
const images = ref([])       // 当前页的图片 generation 对象数组 (后端分页)
const selIdx = ref(-1)
// 历史缩略图分页 (后端分页)
const HISTORY_PAGE_SIZE = 8
const historyPage = ref(1)
const imageTotal = ref(0)
const historyTotalPages = computed(() => Math.max(1, Math.ceil(imageTotal.value / HISTORY_PAGE_SIZE)))
const pagedHistory = computed(() => images.value.map((g, idx) => ({ g, idx })))
const detailGen = ref(null)    // 当前查看的 generation
const sidebarCollapsed = ref(false)
// ===== 视频生成状态 =====
const videoPrompt = ref('')
const videoDuration = ref(5)
const videoAspect = ref('16:9')
const videoReferImage = ref('')   // 首帧参考图(图生视频, base64 dataURL)
const videoEndImage = ref('')     // 尾帧参考图(首尾帧模式, 仅可灵支持, 需配合首帧)
const videoNegativePrompt = ref('') // 反向提示词(仅万相支持)
const videoSeed = ref(null)       // 随机种子复现(仅万相支持)
// 当前视频模型支持的可选能力(由后端 ModelInfo.capabilities 下发)
const videoCaps = computed(() => {
  const m = props.videoModels?.find(x => x.name === selectedVideoModel.value)
  return m?.capabilities || []
})
const capEndImage = computed(() => videoCaps.value.includes('end-image'))
const capNegativePrompt = computed(() => videoCaps.value.includes('negative-prompt'))
const capSeed = computed(() => videoCaps.value.includes('seed'))
const videoGenerating = ref(false)
const resultVideo = ref('')
const videoStatus = ref('')   // pending | processing | succeeded | failed
let videoPollTimer = null
// 视频生成阶段式进度(纯前端估算, 厂商无真实百分比)
// 0: 排队 1: 渲染 2: 收尾 3: 完成(成功)
const videoStageIndex = ref(0)
// 各阶段预计耗时(秒); 渲染耗时与视频时长正相关
// 排队阶段不预测真实时长(取决于池空闲), 仅给一个上限用于估算爬升
const QUEUE_EST_SEC = 8
const videoStageEst = computed(() => {
  const dur = Number(videoDuration.value) || 5
  return [QUEUE_EST_SEC, Math.max(20, dur * 6), 6]   // 排队 / 渲染 / 收尾
})
const videoTotalEstSec = computed(() => videoStageEst.value.reduce((a, b) => a + b, 0))
const videoStartTs = ref(0)             // 提交时刻(ms, 前端记, 仅兜底)
const videoStageStartTs = ref(0)        // 进入当前阶段的时刻(ms)
const videoNow = ref(Date.now())        // 响应式时钟(每秒自增), 驱动进度/时长重算
// 后端真实时间节点(轮询回填): createdAt 提交时刻, startedAt 进入渲染时刻(ms), durationMs 总耗时
const videoCreatedAtMs = ref(0)
const videoStartedAtMs = ref(0)
const videoDurationMs = ref(0)
let videoRafTimer = null
// 基于计时 + 阶段推进的实时进度(估算值, 非厂商真实百分比)
const videoProgressPercent = computed(() => {
  if (videoStageIndex.value >= 3) return 100
  if (videoStageIndex.value === 0) {
    // 排队中: 缓慢爬升, 上限 15%(避免真实排队时长不可预测导致进度跑飞)
    const q = Math.min((videoNow.value - videoStageStartTs.value) / 1000 / QUEUE_EST_SEC, 1)
    return Math.round(Math.min(q, 1) * 15)
  }
  let done = 0
  for (let i = 0; i < videoStageIndex.value; i++) done += videoStageEst.value[i]
  const cur = videoStageEst.value[videoStageIndex.value] || 1
  const stageElapsed = (videoNow.value - videoStageStartTs.value) / 1000
  done += Math.min(stageElapsed, cur)
  return Math.max(0, Math.min(99, Math.round((done / videoTotalEstSec.value) * 100)))
})
// 排队耗时: 提交 -> 进入渲染; 渲染前实时(now-提交), 渲染开始后固定(startedAt-提交)
const videoQueueMs = computed(() => {
  if (!videoCreatedAtMs.value) return 0
  return videoStartedAtMs.value
    ? videoStartedAtMs.value - videoCreatedAtMs.value
    : Math.max(0, videoNow.value - videoCreatedAtMs.value)
})
// 渲染用时: 进入渲染 -> 完成; 渲染前为0, 渲染中实时(now-startedAt), 完成用 durationMs
const videoRenderMs = computed(() => {
  if (!videoStartedAtMs.value) return 0
  if (videoDurationMs.value) return videoDurationMs.value
  return Math.max(0, videoNow.value - videoStartedAtMs.value)
})
const videoQueueText = computed(() => fmtDuration(Math.floor(videoQueueMs.value / 1000)))
const videoRenderText = computed(() => fmtDuration(Math.floor(videoRenderMs.value / 1000)))
const videoStageIcon = computed(() => ['⏳', '🎬', '✨', '✅'][videoStageIndex.value] || '⏳')
const videoStageLabel = computed(() => ['排队中...', '渲染中...', '收尾处理中...', '即将完成'][videoStageIndex.value] || '排队中...')
function fmtDuration(sec) {
  const m = Math.floor(sec / 60), s = sec % 60
  return m > 0 ? `${m}分${s}秒` : `${s}秒`
}
// 视频历史(按 type==='video' 区分, 与图片互不混入, 后端分页)
const videos = ref([])         // 当前页的视频 generation 对象数组
const videoSelIdx = ref(-1)
const videoHistoryPage = ref(1)
const videoTotal = ref(0)
const videoHistoryTotalPages = computed(() => Math.max(1, Math.ceil(videoTotal.value / HISTORY_PAGE_SIZE)))
const pagedVideoHistory = computed(() => videos.value.map((g, idx) => ({ g, idx })))
// 当前选中的视频(历史点击或刚生成)
const currentVideo = computed(() => videoSelIdx.value >= 0 ? videos.value[videoSelIdx.value]?.resultUrl : null)
// 选中的失败视频记录(用于占位/详情展示)
const currentVideoSelFailed = computed(() => videoSelIdx.value >= 0 && videos.value[videoSelIdx.value]?.status === 'failed' ? videos.value[videoSelIdx.value] : null)
async function generateVideo() {
  if (!videoPrompt.value.trim() || videoGenerating.value) return
  if (!currentProjectId.value) await ensureProject()
  videoGenerating.value = true
  resultVideo.value = ''
  videoStatus.value = 'pending'
  // 初始化阶段式进度
  videoStageIndex.value = 0
  videoStartTs.value = Date.now()
  videoStageStartTs.value = videoStartTs.value
  // 清空后端时间节点, 待轮询回填(createdAt 兜底用本地提交时刻)
  videoCreatedAtMs.value = videoStartTs.value
  videoStartedAtMs.value = 0
  videoDurationMs.value = 0
  startVideoProgressTicker()
  try {
    const data = await submitVideoTask({
      prompt: videoPrompt.value,
      model: selectedVideoModel.value,
      duration: videoDuration.value,
      aspectRatio: videoAspect.value,
      referImage: videoReferImage.value || null,
      endImage: capEndImage.value ? (videoEndImage.value || null) : null,
      negativePrompt: capNegativePrompt.value ? (videoNegativePrompt.value || null) : null,
      seed: capSeed.value ? (videoSeed.value || null) : null,
      projectId: currentProjectId.value
    })
    // 后端返回 pending 任务, 启动轮询
    startVideoPolling(data.id)
  } catch (e) {
    ElMessage.error(e?.message || '视频生成失败')
    videoStatus.value = ''
    stopVideoProgress()
  }
}
function startVideoPolling(id) {
  stopVideoPolling()
    const tick = async () => {
    try {
      const st = await getVideoStatus(id)
      videoStatus.value = st.status
      // 回填后端真实时间节点
      if (st.createdAt) videoCreatedAtMs.value = new Date(st.createdAt).getTime()
      if (st.startedAt) videoStartedAtMs.value = st.startedAt
      if (st.durationMs) videoDurationMs.value = st.durationMs
      if (st.status === 'completed' || st.status === 'succeeded') {
        // 真实完成, 进度拉满
        videoStageIndex.value = 3
        resultVideo.value = st.resultUrl
        stopVideoPolling()
        stopVideoProgress()
        // 刷新历史并定位到刚生成的视频
        if (currentProjectId.value) await loadProjectImages(currentProjectId.value, [st.id])
      } else if (st.status === 'processing') {
        // 后端真实回写: 已拿到线程, 进入渲染阶段(②)
        if (videoStageIndex.value < 1) {
          videoStageIndex.value = 1
          videoStageStartTs.value = videoNow.value
        }
      } else if (st.status === 'pending') {
        // 后端真实状态: 仍在队列中排队(①), 保底置阶段 0
        videoStageIndex.value = 0
        videoStageStartTs.value = videoStartTs.value
      } else if (st.status === 'failed') {
        ElMessage.error(st.error || '视频生成失败')
        stopVideoPolling()
        stopVideoProgress()
      }
    } catch (e) {
      // 单次查询失败仅提示, 不清进度 ticker, 也不停轮询(下次重试)
      ElMessage.warning(e?.message || '查询视频状态失败，将自动重试')
    }
  }
  tick()
  videoPollTimer = setInterval(tick, 3000)
}
function startVideoProgressTicker() {
  stopVideoProgressTicker()
  // 仅刷新响应式时钟 videoNow(驱动进度%/已等待时长重算)
  // 阶段(排队/渲染/收尾)完全由轮询返回的 status 决定, 不再用前端计时伪造推进
  videoRafTimer = setInterval(() => {
    videoNow.value = Date.now()
    // 安全上限: 超过 10 分钟仍无结果, 停止估算并提示(避免无限卡)
    if ((videoNow.value - videoStartTs.value) / 1000 > 600) {
      stopVideoProgress()
      ElMessage.error('视频生成超时（>10 分钟），请稍后在历史中查看或重试')
    }
  }, 1000)
}
function stopVideoProgressTicker() {
  if (videoRafTimer) { clearInterval(videoRafTimer); videoRafTimer = null }
}
// 统一收尾: 仅清理进度 ticker 与状态(由生成成功/失败调用, 不被轮询错误误杀)
function stopVideoProgress() {
  stopVideoProgressTicker()
  videoGenerating.value = false
}
function stopVideoPolling() {
  if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null }
}
const currentImg = computed(() => selIdx.value >= 0 ? images.value[selIdx.value]?.resultUrl : null)
// 当前选中的记录对象(含失败记录, 用于占位/详情/重生成)
const currentSel = computed(() => selIdx.value >= 0 ? images.value[selIdx.value] : null)
const selectedGenFailed = computed(() => currentSel.value && currentSel.value.status === 'failed')
async function loadProjects() { projects.value = await getProjects() }
// 从后端分页加载某项目下历史生成记录 (图片/视频各自分页, 互不混入)
// 加载当前页: 图片加载 historyPage, 视频加载 videoHistoryPage
async function loadProjectImages(id, newIds = null) {
  if (!id) { images.value = []; selIdx.value = -1; imageTotal.value = 0; videos.value = []; videoSelIdx.value = -1; videoTotal.value = 0; return }
  try {
    // 新生成记录优先跳到第 1 页 (最新在前), 便于自动定位
    if (newIds && newIds.length) {
      historyPage.value = 1
      videoHistoryPage.value = 1
    }
    await Promise.all([loadImagePage(id), loadVideoPage(id)])
    // 优先选中本次刚生成的记录(按 id 定位), 否则默认显示最新一张
    let imgSel = 0
    if (newIds && newIds.length) {
      const found = images.value.findIndex(g => newIds.includes(g.id))
      if (found >= 0) imgSel = found
    }
    selIdx.value = images.value.length ? imgSel : -1
    let vidSel = 0
    if (newIds && newIds.length) {
      const found = videos.value.findIndex(g => newIds.includes(g.id))
      if (found >= 0) vidSel = found
    }
    videoSelIdx.value = videos.value.length ? vidSel : -1
  } catch (e) { console.error('加载历史记录失败', e); images.value = []; selIdx.value = -1; imageTotal.value = 0; videos.value = []; videoSelIdx.value = -1; videoTotal.value = 0 }
}

// 图片历史分页加载 (后端仅返回终态: completed/failed, total 与展示一致)
async function loadImagePage(id) {
  const page = await getGenerations(id, { type: 'image', page: historyPage.value, size: HISTORY_PAGE_SIZE })
  images.value = page?.records || []
  imageTotal.value = page?.total || 0
  // 仅保留当前页范围内的选中(切换页后重置选中, 避免越界)
  if (selIdx.value >= images.value.length) selIdx.value = -1
}

// 视频历史分页加载 (后端仅返回终态)
async function loadVideoPage(id) {
  const page = await getGenerations(id, { type: 'video', page: videoHistoryPage.value, size: HISTORY_PAGE_SIZE })
  videos.value = page?.records || []
  videoTotal.value = page?.total || 0
  if (videoSelIdx.value >= videos.value.length) videoSelIdx.value = -1
}

// 图片历史翻页 (后端)
async function changeImagePage(p) {
  const max = historyTotalPages.value
  const next = Math.min(Math.max(p, 1), max)
  if (next === historyPage.value) return
  historyPage.value = next
  selIdx.value = -1
  await loadImagePage(currentProjectId.value)
}

// 视频历史翻页 (后端)
async function changeVideoPage(p) {
  const max = videoHistoryTotalPages.value
  const next = Math.min(Math.max(p, 1), max)
  if (next === videoHistoryPage.value) return
  videoHistoryPage.value = next
  videoSelIdx.value = -1
  await loadVideoPage(currentProjectId.value)
}
async function newProject() {
  const p = await createProject({ name: 'New Project', mode: 'image', model: selectedImageModel.value || props.currentModel })
  projects.value.unshift(p); currentProjectId.value = p.id
  images.value = []; selIdx.value = -1; historyPage.value = 1; imageTotal.value = 0
  videos.value = []; videoSelIdx.value = -1; videoHistoryPage.value = 1; videoTotal.value = 0
  prompt.value = ''
}
function openDetail(gen) {
  detailGen.value = gen
}
function closeDetail() {
  detailGen.value = null
}
/** 将详情中的参数套用到当前控制面板 */
function applyParams({ gen, params }) {
  if (!params || Object.keys(params).length === 0) {
    ElMessage.warning('该记录没有可套用的参数')
    return
  }
  const applied = []
  if (params.prompt) { prompt.value = params.prompt; applied.push('提示词') }
  if (params.size) size.value = params.size
  else if (gen.size) size.value = gen.size
  if (params.size || gen.size) applied.push('尺寸')
  if (params.style) { styleVal.value = params.style; applied.push('风格') }
  if (params.negativePrompt != null) { negativePrompt.value = params.negativePrompt; applied.push('反向提示词') }
  if (params.n != null) { n.value = params.n; applied.push('数量') }
  if (params.promptExtend != null) { promptExtend.value = params.promptExtend; applied.push('智能改写') }
  if (params.watermark != null) { watermark.value = params.watermark; applied.push('水印') }
  // 参考图(base64 未存储)无法回填
  if (params.hasReferImage) {
    ElMessage.info('参考图未保存，无法回填（仅记录使用了参考图）')
  }
  closeDetail()
  ElMessage.success('已套用：' + (applied.join('、') || '无可用字段'))
}
/** 把后端存的 duration 字符串(如 "5s")转成秒数 */
function parseDurationToSeconds(d) {
  if (typeof d === 'number') return d
  const m = String(d).match(/(\d+)/)
  return m ? parseInt(m[1], 10) : 5
}
/** 套用视频参数到视频控制面板 */
function applyVideoParams({ params }) {
  if (!params || Object.keys(params).length === 0) {
    ElMessage.warning('该记录没有可套用的参数')
    return
  }
  const applied = []
  if (params.prompt) { videoPrompt.value = params.prompt; applied.push('提示词') }
  if (params.aspectRatio) { videoAspect.value = params.aspectRatio; applied.push('比例') }
  if (params.duration) { videoDuration.value = parseDurationToSeconds(params.duration); applied.push('时长') }
  if (params.negativePrompt != null) { videoNegativePrompt.value = params.negativePrompt; applied.push('反向提示词') }
  if (params.seed != null) { videoSeed.value = params.seed; applied.push('随机种子') }
  if (params.model) {
    selectedVideoModel.value = params.model
    emit('update:currentModel', params.model)
    applied.push('模型')
  }
  // 首帧/尾帧(base64 未存储)无法回填
  const unfilled = []
  if (params.hasReferImage) unfilled.push('首帧参考图')
  if (params.hasEndImage) unfilled.push('尾帧参考图')
  if (unfilled.length) {
    ElMessage.info(unfilled.join('、') + '未保存，无法回填（仅记录使用了参考图）')
  }
  closeDetail()
  ElMessage.success('已套用：' + (applied.join('、') || '无可用字段'))
}
// 隐式确保有归属项目(直接生成但尚无项目时), 不重置用户输入
async function ensureProject() {
  if (currentProjectId.value) return
  const p = await createProject({ name: 'New Project', mode: 'image', model: selectedImageModel.value || props.currentModel })
  projects.value.unshift(p); currentProjectId.value = p.id
}
async function switchProject(id) {
  currentProjectId.value = id
  historyPage.value = 1
  videoHistoryPage.value = 1
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
      imageTotal.value = 0
      historyPage.value = 1
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
function onVideoUpload(e) { const f = e.target.files[0]; if (!f) return; const r = new FileReader(); r.onload = ev => videoReferImage.value = ev.target.result; r.readAsDataURL(f) }
function onVideoEndUpload(e) { const f = e.target.files[0]; if (!f) return; const r = new FileReader(); r.onload = ev => videoEndImage.value = ev.target.result; r.readAsDataURL(f) }
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
      model: selectedImageModel.value, prompt: prompt.value, size: size.value,
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
    const msg = extractMessage(e) || e
    ElMessage.error('提交生成任务失败：' + msg)
  } finally {
    // 关键: 无论成功/失败/刷新异常, 都必须复位 generating, 否则按钮会卡死, 之后点击被静默忽略
    generating.value = false
  }
}
onMounted(async () => {
  await loadProjects()
  // 模型列表由父组件注入(props), 此处初始化各自的默认选中(以对应类型为准)
  syncDefaultModels()
  if (projects.value.length) {
    currentProjectId.value = projects.value[0].id
    await loadProjectImages(currentProjectId.value)
  }
  await loadThreadPoolStats()
  // 每 6s 刷新线程池状态, 展示实时可用线程(频率已调低, 降低请求压力)
  threadTimer = setInterval(loadThreadPoolStats, 6000)
})
onUnmounted(() => { if (threadTimer) clearInterval(threadTimer); stopVideoPolling() })
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

/* ===== 图片/视频 切换 Tab ===== */
.gen-type-tabs { display: flex; gap: 6px; margin-bottom: 16px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-pill); padding: 4px; }
.gtab { flex: 1; text-align: center; padding: 8px 0; font-size: 13px; color: var(--text-2); border-radius: var(--radius-pill); cursor: pointer; transition: all 0.2s var(--ease); user-select: none; }
.gtab:hover { color: var(--text); background: var(--surface-hover); }
.gtab.active { color: #fff; background: linear-gradient(135deg, var(--accent), var(--accent-2)); box-shadow: 0 2px 12px var(--accent-glow); }

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
.model-select-wrap { flex: 1; min-width: 0; }

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
.param-hint { margin: 6px 2px 0; color: var(--text-3); font-size: 12px; }
.num-input {
  width: 120px; background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-card); padding: 7px 10px; color: var(--text);
  font-size: 14px; outline: none; font-family: inherit;
}
.num-input:focus { border-color: var(--accent); background: var(--surface-2); }

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

/* ===== 失败记录占位(破裂图片) ===== */
.failed-preview {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; max-width: 85%; padding: 40px 48px; text-align: center;
  background: rgba(255, 255, 255, 0.02);
  border: 1px dashed rgba(248, 113, 113, 0.35); border-radius: 16px;
  color: var(--text-3);
}
.failed-preview-icon {
  width: 96px; height: 96px; color: rgba(248, 113, 113, 0.55);
  filter: drop-shadow(0 0 18px rgba(248, 113, 113, 0.15));
}
.failed-preview-icon svg { width: 100%; height: 100%; }
.failed-preview-title { font-size: 16px; font-weight: 600; color: var(--danger); margin: 0; }
.failed-preview-msg {
  font-size: 12px; color: var(--text-2); margin: 0; max-width: 420px;
  word-break: break-all; line-height: 1.6;
  background: rgba(248, 113, 113, 0.08); border-radius: 8px; padding: 8px 12px;
}
.thumb-broken {
  display: flex; align-items: center; justify-content: center;
  width: 100%; height: 100%; font-size: 24px;
  color: rgba(248, 113, 113, 0.5);
  background:
    radial-gradient(circle at 30% 30%, rgba(248, 113, 113, 0.08), transparent 60%),
    rgba(255, 255, 255, 0.03);
}
.thumb.failed { border-color: rgba(248, 113, 113, 0.3); opacity: 0.7; }
.thumb.failed:hover { opacity: 1; }
.thumb.failed.active { border-color: var(--danger); box-shadow: 0 0 0 3px rgba(248, 113, 113, 0.2); opacity: 1; }

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

/* ===== 视频阶段式进度 ===== */
.video-progress {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 14px; width: 100%; max-width: 420px; padding: 24px;
}
.vp-stage-icon { font-size: 46px; animation: vp-bob 2.2s ease-in-out infinite; }
@keyframes vp-bob { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-8px); } }
.vp-status { font-size: 16px; font-weight: 600; color: var(--text); letter-spacing: 0.02em; }
.vp-bar {
  width: 100%; height: 8px; border-radius: var(--radius-pill);
  background: rgba(255, 255, 255, 0.08); overflow: hidden; border: 1px solid var(--border);
}
.vp-bar > i {
  display: block; height: 100%; border-radius: var(--radius-pill);
  background: linear-gradient(90deg, var(--accent), var(--accent-2), var(--success));
  background-size: 200% 100%;
  transition: width 0.5s var(--ease);
  animation: vp-flow 1.6s linear infinite;
}
@keyframes vp-flow { 0% { background-position: 0% 0; } 100% { background-position: 200% 0; } }
.vp-metrics { display: flex; gap: 10px; margin-top: 4px; }
.vp-metric { flex: 1; background: rgba(125,108,240,0.06); border: 1px solid var(--border-1); border-radius: 8px; padding: 4px 8px; display: flex; flex-direction: column; gap: 2px; }
.vp-metric-label { font-size: 11px; color: var(--text-2); }
.vp-metric-value { font-size: 14px; font-weight: 600; color: var(--primary); font-variant-numeric: tabular-nums; }
.vp-stage-track { display: flex; gap: 10px; margin-top: 4px; }
.vp-stage {
  font-size: 12px; padding: 4px 12px; border-radius: var(--radius-pill);
  background: var(--surface); border: 1px solid var(--border); color: var(--text-3);
  transition: all 0.3s var(--ease);
}
.vp-stage.active { color: var(--accent); border-color: var(--accent); background: var(--accent-glow); }
.vp-stage.done { color: var(--success); border-color: rgba(52, 211, 153, 0.4); background: rgba(52, 211, 153, 0.12); }
.vp-tip { font-size: 11px; color: var(--text-3); margin: 0; text-align: center; }


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
.thumb-video { width: 100%; height: 100%; object-fit: cover; background: #000; }
.thumb.active { opacity: 1; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }
.thumb-detail {
  position: absolute; right: 3px; top: 3px; z-index: 2;
  width: 18px; height: 18px; border-radius: 50%; line-height: 16px; text-align: center;
  background: rgba(0,0,0,0.6); color: #fff; font-size: 11px; border: none; cursor: pointer;
  opacity: 0; transition: opacity 0.15s ease;
}
.thumb:hover .thumb-detail { opacity: 1; }

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
