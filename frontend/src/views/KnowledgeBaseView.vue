<template>
  <div class="kb-mode">
    <!-- 左侧: 知识库列表 -->
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed}">
      <div class="sidebar-header">
        <button class="new-btn" @click="openCreateDialog">+ 新建知识库</button>
        <button class="collapse-btn" @click="sidebarCollapsed = true" title="收起">«</button>
      </div>
      <div class="kb-list">
        <div v-for="kb in knowledgeBases" :key="kb.id"
             class="kb-item" :class="{active: kb.id === currentKbId}"
             @click="selectKb(kb.id)">
          <div class="kb-info">
            <div class="kb-name">
              {{ kb.name }}
              <el-tag size="small" :type="kb.visibility === 'PLATFORM' ? 'warning' : 'info'" class="kb-vis-tag">
                {{ kb.visibility === 'PLATFORM' ? '平台级' : '用户级' }}
              </el-tag>
            </div>
            <div class="kb-meta">
              <span>{{ kb.docCount || 0 }} 文档</span>
              <span>·</span>
              <span>{{ kb.embeddingModel || '默认' }}</span>
            </div>
            <div class="kb-desc" v-if="kb.description" :title="kb.description">{{ kb.description }}</div>
          </div>
          <button v-if="canEditKb(kb)" class="kb-delete-btn" @click.stop="removeKb(kb)" title="删除"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4.6 7.2H19.4"/><path d="M9.4 7.2V5.4C9.4 4.8 9.8 4.4 10.4 4.4H13.6C14.2 4.4 14.6 4.8 14.6 5.4V7.2"/><path d="M6.6 7.2L7.4 19C7.4 19.6 7.9 20 8.5 20H15.5C16.1 20 16.6 19.6 16.6 19L17.4 7.2"/></svg></button>
        </div>
        <div v-if="!knowledgeBases.length && !loading" class="empty-tip">
          暂无知识库<br/>点击上方按钮创建
        </div>
      </div>
      <!-- 与「记忆」的分工: 常驻显示, 因为"这条该放哪"的疑问是在浏览时才产生的 -->
      <div class="kb-scope-tip">
        这里放<b>成篇资料</b>（保留原文、按需检索引用）；关于你的偏好与结论请放「记忆」
      </div>
    </aside>
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false">»</button>

    <!-- 右侧: 文档管理 + 检索测试 -->
    <div class="kb-main">
      <template v-if="currentKb">
        <div class="kb-toolbar">
          <div class="kb-toolbar-left">
            <h2 class="kb-title">{{ currentKb.name }}</h2>
            <el-tag size="small" :type="currentKb.visibility === 'PLATFORM' ? 'warning' : 'info'">
              {{ currentKb.visibility === 'PLATFORM' ? '平台级' : '用户级' }}
            </el-tag>
            <el-tag size="small" type="info">{{ currentKb.docCount || 0 }} 文档</el-tag>
            <el-tag size="small" :title="splitterHint(currentKb.chunkStrategy)">
              {{ strategyLabel(currentKb.chunkStrategy) }} · {{ currentKb.chunkSize || 1000 }}/{{ currentKb.chunkOverlap || 200 }}
            </el-tag>
            <button v-if="canEditKb(currentKb)" class="chunk-cfg-btn" @click="openChunkDialog" title="修改切分策略与块大小">切分设置</button>
            <span class="kb-desc-full" v-if="currentKb.description" :title="currentKb.description">{{ currentKb.description }}</span>
          </div>
          <div class="kb-toolbar-right">
            <button class="refresh-btn" @click="refreshAll" :disabled="refreshing" title="刷新列表与文档状态">
              <span :class="{spin: refreshing}">⟳</span>
              {{ refreshing ? '刷新中…' : '刷新' }}
            </button>
            <label v-if="canEditKb(currentKb)" class="upload-btn gradient-bg" :class="{disabled: uploading}">
              <input type="file" ref="fileInput" @change="handleUpload" accept=".pdf,.docx,.txt,.md,.markdown,.html,.htm" hidden :disabled="uploading" />
              <span v-if="!uploading"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M16.4 8.2L9.2 15.4C8 16.6 6.1 16.6 4.9 15.4C3.7 14.2 3.7 12.3 4.9 11.1L12.1 3.9C13.7 2.3 16.3 2.3 17.9 3.9C19.5 5.5 19.5 8.1 17.9 9.7L10.7 16.9C9.7 17.9 8.1 17.9 7.1 16.9C6.1 15.9 6.1 14.3 7.1 13.3L13.7 6.7"/></svg>上传文档</span>
              <span v-else>上传中…</span>
            </label>
            <el-tag v-else type="info" effect="plain">平台级知识库 · 只读</el-tag>
          </div>
        </div>

        <div class="kb-body">
          <!-- 文档表 -->
          <div class="doc-panel">
            <div class="doc-head">
              <span class="dh-name">文档</span>
              <span class="dh-size">大小 · 分块</span>
              <span class="dh-status">状态</span>
              <span class="dh-act"></span>
            </div>
            <div class="doc-list">
              <div v-for="doc in documents" :key="doc.id" class="doc-item">
                <span class="doc-name" :title="doc.filename">{{ doc.filename }}</span>
                <span class="doc-meta">{{ formatSize(doc.fileSize) }} · {{ doc.chunkCount || 0 }} 块</span>
                <span class="doc-status" :class="'st-' + String(doc.status || '').toLowerCase()">
                  {{ statusLabel(doc.status) }}
                  <el-tooltip v-if="doc.errorMsg" :content="doc.errorMsg" placement="top">
                    <span class="error-hint">!</span>
                  </el-tooltip>
                </span>
                <span class="doc-actions">
                  <button v-if="(doc.chunkCount || 0) > 0" class="doc-action-btn" @click="openChunks(doc)"
                          title="查看分块">
                    <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M8 6h12"/><path d="M8 12h12"/><path d="M8 18h12"/><path d="M4 6h.01"/><path d="M4 12h.01"/><path d="M4 18h.01"/></svg>
                  </button>
                  <template v-if="canEditKb(currentKb)">
                    <button class="doc-action-btn" @click="reprocessDoc(doc)" title="重新入库"
                            :disabled="doc.status === 'indexing'">
                      <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20 12A8 8 0 1 1 17.2 6.1"/><path d="M20 4.6V9.4H15.2"/></svg>
                    </button>
                    <button class="doc-action-btn danger" @click="removeDoc(doc)" title="删除">
                      <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4.6 7.2H19.4"/><path d="M9.4 7.2V5.4C9.4 4.8 9.8 4.4 10.4 4.4H13.6C14.2 4.4 14.6 4.8 14.6 5.4V7.2"/><path d="M6.6 7.2L7.4 19C7.4 19.6 7.9 20 8.5 20H15.5C16.1 20 16.6 19.6 16.6 19L17.4 7.2"/></svg>
                    </button>
                  </template>
                </span>
              </div>
              <div v-if="!documents.length && !docsLoading" class="empty-docs">
                <p>暂无文档</p>
                <span>支持 PDF / Word / TXT / Markdown / HTML</span>
              </div>
            </div>
          </div>

          <!-- 检索测试：与文档表同屏。原来藏在第二个 tab 里，调完参数要来回切，
               看不到"这条知识库到底好不好用" -->
          <aside class="search-panel">
            <div class="sp-head">
              <span class="sp-title">检索测试</span>
              <span class="sp-mode">HYBRID · RRF</span>
            </div>
            <textarea class="sp-query" v-model="searchQuery" rows="3"
                      placeholder="输入测试问题，查看命中片段与相似度"
                      @keydown.ctrl.enter="runSearch"></textarea>
            <div class="sp-params">
              <div class="sp-row">
                <span class="sp-k">召回条数 topK</span>
                <el-input-number v-model="searchTopK" :min="1" :max="20" :step="1"
                                 size="small" controls-position="right" style="width: 96px" />
              </div>
              <div class="sp-row">
                <span class="sp-k">多知识库</span>
                <el-switch v-model="multiSearch" size="small" />
              </div>
              <el-select v-if="multiSearch" v-model="multiSearchKbIds" multiple collapse-tags
                         placeholder="选择知识库" size="small" style="width: 100%">
                <el-option v-for="k in knowledgeBases" :key="k.id" :label="k.name" :value="k.id" />
              </el-select>
            </div>
            <button class="sp-run" @click="runSearch" :disabled="searching || !searchQuery.trim()">
              {{ searching ? '检索中…' : '检索' }}
            </button>
            <div class="sp-results">
              <div v-for="(r, idx) in searchResults" :key="r.chunkId" class="result-item">
                <div class="result-header">
                  <span class="result-score" :style="{ color: scoreColor(r.score) }">{{ (r.score * 100).toFixed(1) }}%</span>
                  <span class="result-rank">#{{ idx + 1 }}</span>
                  <span class="result-file" :title="r.filename || r.documentId">{{ r.filename || r.documentId }}</span>
                </div>
                <div class="result-content">{{ r.content }}</div>
              </div>
              <div v-if="!searchResults.length && searchDone" class="empty-results">
                未检索到相关片段（可调低相似度阈值或换问题重试）
              </div>
              <div v-if="!searchResults.length && !searchDone" class="empty-results">
                输入问题后检索，这里会列出命中的块与相似度
              </div>
            </div>
          </aside>
        </div>
      </template>

      <div v-else class="kb-empty">
        <div class="empty-icon large"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 5.2C5 4 6 3.2 7.2 3.2H18.6V17.4H7.2C6 17.4 5 18.2 5 19.4V5.2Z"/><path d="M18.6 17.4V21H7.2"/><path d="M9 7.6H14.6"/></svg></div>
        <h2>知识库管理</h2>
        <p>选择左侧知识库或新建一个，开始管理文档与检索测试</p>
        <button class="new-here-btn gradient-bg" @click="openCreateDialog">+ 新建知识库</button>
      </div>
    </div>

    <!-- 创建知识库弹窗 -->
    <el-dialog v-model="createDialogVisible" title="新建知识库" width="480px">
      <el-form :model="createForm" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="如：产品手册" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="3"
                    placeholder="可选，知识库用途说明" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="切分策略">
          <el-select v-model="createForm.chunkStrategy" style="width: 100%">
            <el-option v-for="s in splitterOptions" :key="s.code" :label="s.label" :value="s.code" />
          </el-select>
          <div class="split-tip">{{ splitterHint(createForm.chunkStrategy) }}</div>
        </el-form-item>
        <el-form-item label="块大小">
          <el-input-number v-model="createForm.chunkSize" :min="100" :max="8000" :step="100"
                           controls-position="right" style="width: 140px" />
          <span class="split-unit">字符 · 越大上下文越完整，但检索精度会下降</span>
        </el-form-item>
        <el-form-item label="重叠字符">
          <el-input-number v-model="createForm.chunkOverlap" :min="0" :max="createForm.chunkSize - 1" :step="50"
                           controls-position="right" style="width: 140px" />
          <span class="split-unit">字符 · 块间重复内容，避免语义断裂</span>
        </el-form-item>
        <el-form-item>
          <el-button size="small" text type="primary" @click="openPreview(createForm)">预览切分效果 →</el-button>
        </el-form-item>
        <el-form-item v-if="isAdmin" label="可见性" required>
          <el-radio-group v-model="createForm.visibility">
            <el-radio value="USER">用户级</el-radio>
            <el-radio value="PLATFORM">平台级</el-radio>
          </el-radio-group>
          <div class="vis-tip">
            <span v-if="createForm.visibility === 'PLATFORM'">平台级：由管理员维护，对所有对话无感注入 RAG 检索范围（用户不可见）</span>
            <span v-else>用户级：用户私有，需在对话时手动选择才会参与 RAG 检索</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmCreate" :loading="creating">创建</el-button>
      </template>
    </el-dialog>

    <!-- 切分设置弹窗 -->
    <el-dialog v-model="chunkDialogVisible" title="切分设置" width="500px">
      <div class="chunk-dialog-tip">
        分块配置决定文档入库时如何切片。修改只影响<b>之后</b>入库的文档，
        存量片段需点文档行的「重新入库」才会按新配置重切。
      </div>
      <el-form :model="chunkForm" label-width="96px">
        <el-form-item label="切分策略">
          <el-select v-model="chunkForm.chunkStrategy" style="width: 100%">
            <el-option v-for="s in splitterOptions" :key="s.code" :label="s.label" :value="s.code" />
          </el-select>
          <div class="split-tip">{{ splitterHint(chunkForm.chunkStrategy) }}</div>
        </el-form-item>
        <el-form-item label="块大小">
          <el-input-number v-model="chunkForm.chunkSize" :min="100" :max="8000" :step="100"
                           controls-position="right" style="width: 140px" />
          <span class="split-unit">字符</span>
        </el-form-item>
        <el-form-item label="重叠字符">
          <el-input-number v-model="chunkForm.chunkOverlap" :min="0" :max="chunkForm.chunkSize - 1" :step="50"
                           controls-position="right" style="width: 140px" />
          <span class="split-unit">字符，需小于块大小</span>
        </el-form-item>
        <el-form-item>
          <el-button size="small" text type="primary" @click="openPreview(chunkForm)">预览切分效果 →</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chunkDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmChunkUpdate" :loading="savingChunk">保存</el-button>
      </template>
    </el-dialog>

    <!-- 切分预览弹窗: 只切文本, 不入库/不向量化 -->
    <el-dialog v-model="previewDialogVisible" title="切分预览" width="640px" top="8vh">
      <div class="chunk-dialog-tip">
        仅对文本试切分，<b>不入库、不向量化</b>。真实入库的片段还会带 <b>[文件: …]</b> 元信息前缀，
        开启重叠时块长也会略大于块大小。当前参数：<b>{{ previewParamsLabel }}</b>
      </div>
      <el-input v-model="previewText" type="textarea" :rows="5" maxlength="200000"
                placeholder="粘贴一段有代表性的文本（如一章节、一段手册），看看会被切成什么样" />
      <div class="preview-actions">
        <el-button size="small" type="primary" @click="runPreview"
                   :loading="previewLoading" :disabled="!previewText.trim()">
          {{ previewLoading ? '切分中…' : '开始预览' }}
        </el-button>
        <span class="preview-hint" v-if="previewResult">
          原文 {{ previewResult.totalChars }} 字符
        </span>
      </div>

      <template v-if="previewResult">
        <div class="preview-stats">
          <span>共 <b>{{ previewResult.chunkCount }}</b> 块</span>
          <span>平均 {{ previewResult.avgChars }} 字符</span>
          <span>最短 {{ previewResult.minChars }}</span>
          <span>最长 {{ previewResult.maxChars }}</span>
        </div>
        <div class="preview-list">
          <div v-for="c in previewResult.chunks" :key="c.index" class="preview-item">
            <div class="preview-item-head">
              <span class="preview-index">#{{ c.index + 1 }}</span>
              <span class="preview-len">{{ c.length }} 字符</span>
            </div>
            <div class="preview-content">{{ c.content }}<span v-if="c.contentTruncated" class="preview-ellipsis">…</span></div>
          </div>
        </div>
        <div v-if="previewResult.truncated" class="preview-more">
          仅展示前 {{ previewResult.chunks.length }} 块，共 {{ previewResult.chunkCount }} 块
        </div>
      </template>
      <div v-else-if="!previewLoading" class="preview-empty">
        粘贴文本后点「开始预览」，这里会列出每块的序号、长度和内容
      </div>

      <template #footer>
        <el-button @click="previewDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 已入库分块详情: 真实内容, 含元信息前缀 -->
    <el-dialog v-model="docChunksVisible" :title="`分块详情 · ${docChunksDoc?.filename || ''}`"
               width="640px" top="8vh">
      <div class="chunk-dialog-tip">
        这是<b>真实入库内容</b>：包含 <b>[文件: …]</b> 元信息前缀，开启重叠时块会略大于块大小。
      </div>
      <div v-if="docChunksTotal" class="preview-stats">
        <span>共 <b>{{ docChunksTotal }}</b> 块</span>
        <span>第 {{ docChunksPage + 1 }} / {{ docChunksTotalPages }} 页</span>
        <span>每页 {{ DOC_CHUNKS_PAGE_SIZE }} 块</span>
      </div>
      <div class="preview-list chunk-view-list" v-loading="docChunksLoading">
        <div v-for="c in docChunks" :key="c.chunkIndex" class="preview-item">
          <div class="preview-item-head">
            <span class="preview-index">#{{ c.chunkIndex + 1 }}</span>
            <span class="preview-len">
              {{ c.length }} 字符<template v-if="c.tokenCount"> · ~{{ c.tokenCount }} tokens</template>
            </span>
          </div>
          <div class="preview-content">{{ c.content }}</div>
        </div>
        <div v-if="!docChunks.length && !docChunksLoading" class="preview-empty">该文档暂无分块</div>
      </div>
      <div class="chunk-pager" v-if="docChunksTotalPages > 1">
        <el-button size="small" :disabled="docChunksPage <= 0 || docChunksLoading"
                   @click="loadChunkPage(docChunksPage - 1)">上一页</el-button>
        <span class="chunk-pager-info">{{ docChunksPage + 1 }} / {{ docChunksTotalPages }}</span>
        <el-button size="small" :disabled="docChunksPage >= docChunksTotalPages - 1 || docChunksLoading"
                   @click="loadChunkPage(docChunksPage + 1)">下一页</el-button>
      </div>
      <template #footer>
        <el-button @click="docChunksVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  getKnowledgeBases, getSplitterOptions, previewSplit, createKnowledgeBase, updateKnowledgeBase, deleteKnowledgeBase,
  listDocuments, uploadDocument, deleteDocument, reprocessDocument, listChunks,
  searchKnowledgeBase, searchKnowledgeBases, extractMessage
} from '@/api'

const authStore = useAuthStore()
// 当前用户是否为管理员(管理员可维护平台级知识库; 普通用户仅管理自己的用户级)
const isAdmin = computed(() => authStore.isAdmin)

// 知识库是否可编辑: admin 全部可编辑; 普通用户仅能编辑自己创建的 USER 级(平台级只读)
function canEditKb(kb) {
  if (!kb) return false
  if (isAdmin.value) return true
  return kb.visibility !== 'PLATFORM'
}

const knowledgeBases = ref([])
const currentKbId = ref(null)
const currentKb = computed(() => knowledgeBases.value.find(k => k.id === currentKbId.value))
const documents = ref([])
const loading = ref(false)
const docsLoading = ref(false)
const uploading = ref(false)
const refreshing = ref(false)
const sidebarCollapsed = ref(false)
const fileInput = ref(null)
const activeTab = ref('documents')

// 可选切分策略(后端接口返回; 接口不可用时用内置兜底, 保证旧版后端也能建库)
const DEFAULT_SPLITTERS = [
  { code: 'recursive', label: '递归字符', description: '段落→换行→句子→空格逐级降级，通用默认策略' },
  { code: 'fixed', label: '固定长度', description: '按字符数等长切分，速度最快；可能在句子中间截断' },
  { code: 'sentence', label: '句子切分', description: '优先按句号/问号/叹号切分，尽量不切断完整句子' },
  { code: 'markdown', label: 'Markdown 结构', description: '按标题层级切分，每块带上所属标题，适合技术文档' }
]
const splitterOptions = ref(DEFAULT_SPLITTERS)
const DEFAULT_CHUNK = { chunkStrategy: 'recursive', chunkSize: 1000, chunkOverlap: 200 }

// 创建弹窗
const createDialogVisible = ref(false)
const createForm = ref({ name: '', description: '', visibility: 'USER', ...DEFAULT_CHUNK })
const creating = ref(false)

// 切分设置弹窗
const chunkDialogVisible = ref(false)
const chunkForm = ref({ ...DEFAULT_CHUNK })
const savingChunk = ref(false)

// 切分预览(纯试切, 不入库)
const previewDialogVisible = ref(false)
const previewText = ref('')
const previewLoading = ref(false)
const previewResult = ref(null)
const previewParams = ref({ ...DEFAULT_CHUNK })
const previewParamsLabel = computed(() => {
  const p = previewParams.value
  return `${strategyLabel(p.chunkStrategy)} · ${p.chunkSize}/${p.chunkOverlap}`
})

// 已入库分块查看(与"切分设置"的 chunkForm 区分命名)
const docChunksVisible = ref(false)
const docChunksDoc = ref(null)
const docChunks = ref([])
const docChunksTotal = ref(0)
const docChunksPage = ref(0)
const docChunksLoading = ref(false)
const DOC_CHUNKS_PAGE_SIZE = 20
const docChunksTotalPages = computed(() =>
  Math.max(1, Math.ceil(docChunksTotal.value / DOC_CHUNKS_PAGE_SIZE)))

// 检索测试
const searchQuery = ref('')
const searchTopK = ref(5)
const searchResults = ref([])
const searching = ref(false)
const searchDone = ref(false)
// 多知识库合并检索 (V3.2)
const multiSearch = ref(false)
const multiSearchKbIds = ref([])

async function loadSplitterOptions() {
  try {
    const options = await getSplitterOptions()
    if (Array.isArray(options) && options.length) splitterOptions.value = options
  } catch {
    // 旧版后端没有该接口时静默用内置列表, 不影响知识库其它操作
  }
}

function strategyLabel(code) {
  return splitterOptions.value.find(s => s.code === code)?.label || code || '递归字符'
}

function splitterHint(code) {
  return splitterOptions.value.find(s => s.code === code)?.description || '按字符切分文档'
}

/** 前端先拦一道非法组合, 后端仍会校验(纵深防御) */
function validateChunkForm(form) {
  if (!form.chunkStrategy) {
    ElMessage.warning('请选择切分策略')
    return false
  }
  if (!Number.isInteger(form.chunkSize) || form.chunkSize < 100 || form.chunkSize > 8000) {
    ElMessage.warning('块大小需在 100 ~ 8000 字符之间')
    return false
  }
  if (!Number.isInteger(form.chunkOverlap) || form.chunkOverlap < 0 || form.chunkOverlap >= form.chunkSize) {
    ElMessage.warning('重叠字符需 >= 0 且小于块大小')
    return false
  }
  return true
}

async function loadKnowledgeBases() {
  loading.value = true
  try {
    knowledgeBases.value = await getKnowledgeBases()
    if (knowledgeBases.value.length && !currentKbId.value) {
      selectKb(knowledgeBases.value[0].id)
    }
  } catch (e) {
    ElMessage.error('加载知识库列表失败：' + (e?.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

async function selectKb(id) {
  if (currentKbId.value === id) return
  currentKbId.value = id
  searchResults.value = []
  searchDone.value = false
  await loadDocuments()
}

async function loadDocuments() {
  if (!currentKbId.value) return
  docsLoading.value = true
  try {
    documents.value = await listDocuments(currentKbId.value)
  } catch (e) {
    ElMessage.error('加载文档列表失败：' + (e?.message || '未知错误'))
    documents.value = []
  } finally {
    docsLoading.value = false
  }
}

/** 刷新: 重新拉取知识库列表 + 当前库文档列表(查看最新文档解析状态/文档数) */
async function refreshAll() {
  if (refreshing.value) return
  refreshing.value = true
  const kbId = currentKbId.value
  try {
    // 重新拉知识库列表(刷新 docCount 等统计)
    const list = await getKnowledgeBases()
    knowledgeBases.value = list
    // 若刷新期间当前库被删除, 列表不再包含它
    if (kbId && !knowledgeBases.value.some(k => k.id === kbId)) {
      currentKbId.value = null
      documents.value = []
    } else {
      // 保持当前选中并重新加载文档
      await loadDocuments()
    }
    ElMessage.success('已刷新')
  } catch (e) {
    ElMessage.error('刷新失败：' + (e?.message || '未知错误'))
  } finally {
    refreshing.value = false
  }
}

function openCreateDialog() {
  createForm.value = { name: '', description: '', visibility: 'USER', ...DEFAULT_CHUNK }
  createDialogVisible.value = true
}

async function confirmCreate() {
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  if (!validateChunkForm(createForm.value)) return
  creating.value = true
  try {
    // 普通用户只能创建用户级知识库(平台级选项仅 admin 可见, 此处二次兜底)
    const visibility = isAdmin.value ? (createForm.value.visibility || 'USER') : 'USER'
    const kb = await createKnowledgeBase({
      name: createForm.value.name.trim(),
      description: createForm.value.description || null,
      visibility,
      chunkStrategy: createForm.value.chunkStrategy,
      chunkSize: createForm.value.chunkSize,
      chunkOverlap: createForm.value.chunkOverlap
    })
    knowledgeBases.value.unshift(kb)
    createDialogVisible.value = false
    ElMessage.success('知识库已创建')
    selectKb(kb.id)
  } catch (e) {
    ElMessage.error('创建失败：' + (e?.message || '未知错误'))
  } finally {
    creating.value = false
  }
}

// ===== 切分预览 =====
/** 用当前表单的参数快照打开预览弹窗(参数改了需要重新打开, 避免看到"旧参数 + 新文案"的错配) */
function openPreview(form) {
  previewParams.value = {
    chunkStrategy: form.chunkStrategy || DEFAULT_CHUNK.chunkStrategy,
    chunkSize: form.chunkSize || DEFAULT_CHUNK.chunkSize,
    chunkOverlap: Number.isInteger(form.chunkOverlap) ? form.chunkOverlap : DEFAULT_CHUNK.chunkOverlap
  }
  previewResult.value = null
  previewDialogVisible.value = true
}

async function runPreview() {
  if (!previewText.value.trim()) {
    ElMessage.warning('请先粘贴一段样本文本')
    return
  }
  previewLoading.value = true
  try {
    previewResult.value = await previewSplit({
      text: previewText.value,
      ...previewParams.value,
      maxChunks: 20
    })
  } catch (e) {
    ElMessage.error('预览失败：' + (extractMessage(e) || '未知错误'))
  } finally {
    previewLoading.value = false
  }
}

// ===== 已入库分块 =====
async function openChunks(doc) {
  docChunksDoc.value = doc
  docChunksVisible.value = true
  await loadChunkPage(0)
}

async function loadChunkPage(page) {
  if (!docChunksDoc.value) return
  docChunksLoading.value = true
  try {
    const res = await listChunks(currentKbId.value, docChunksDoc.value.id, page, DOC_CHUNKS_PAGE_SIZE)
    docChunks.value = res?.chunks || []
    docChunksTotal.value = res?.total || 0
    docChunksPage.value = res?.page ?? page
  } catch (e) {
    ElMessage.error('加载分块失败：' + (extractMessage(e) || '未知错误'))
    docChunks.value = []
  } finally {
    docChunksLoading.value = false
  }
}

// ===== 切分设置 =====
function openChunkDialog() {
  if (!currentKb.value) return
  chunkForm.value = {
    chunkStrategy: currentKb.value.chunkStrategy || DEFAULT_CHUNK.chunkStrategy,
    chunkSize: currentKb.value.chunkSize || DEFAULT_CHUNK.chunkSize,
    chunkOverlap: currentKb.value.chunkOverlap ?? DEFAULT_CHUNK.chunkOverlap
  }
  chunkDialogVisible.value = true
}

async function confirmChunkUpdate() {
  if (!currentKb.value || !validateChunkForm(chunkForm.value)) return
  savingChunk.value = true
  try {
    const updated = await updateKnowledgeBase(currentKb.value.id, {
      chunkStrategy: chunkForm.value.chunkStrategy,
      chunkSize: chunkForm.value.chunkSize,
      chunkOverlap: chunkForm.value.chunkOverlap
    })
    // 就地更新列表与 currentKb, 免去整表刷新导致的选中态跳动
    const idx = knowledgeBases.value.findIndex(k => k.id === updated.id)
    if (idx >= 0) knowledgeBases.value.splice(idx, 1, updated)
    chunkDialogVisible.value = false
    ElMessage.success('切分配置已保存')
    if (!documents.value.length) return
    try {
      await ElMessageBox.confirm(
        `是否立即对当前知识库的 ${documents.value.length} 个文档全部重新入库？不重新入库的话，已有片段仍按旧配置切分。`,
        '应用新配置',
        { type: 'info', confirmButtonText: '立即重新入库', cancelButtonText: '稍后再说' }
      )
      await reindexAllDocs()
    } catch { /* 用户选择稍后再说 */ }
  } catch (e) {
    ElMessage.error('保存失败：' + (extractMessage(e) || '未知错误'))
  } finally {
    savingChunk.value = false
  }
}

/** 逐文档触发重新入库(后端异步执行), 仅跳过正在入库的文档 */
async function reindexAllDocs() {
  const targets = documents.value.filter(d => d.status !== 'indexing')
  if (!targets.length) return
  let ok = 0
  for (const doc of targets) {
    try {
      await reprocessDocument(currentKbId.value, doc.id)
      doc.status = 'pending'
      ok++
    } catch (e) {
      ElMessage.warning(`「${doc.filename}」触发失败：${extractMessage(e) || '未知错误'}`)
    }
  }
  ElMessage.success(`已触发 ${ok} 个文档重新入库`)
  setTimeout(() => loadDocuments(), 2000)
}

async function removeKb(kb) {
  try {
    await ElMessageBox.confirm(
      `确定删除知识库「${kb.name}」吗？所有文档与向量将一并删除，不可恢复。`,
      '删除知识库',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  try {
    await deleteKnowledgeBase(kb.id)
    knowledgeBases.value = knowledgeBases.value.filter(k => k.id !== kb.id)
    if (currentKbId.value === kb.id) {
      currentKbId.value = null
      documents.value = []
    }
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.message || '未知错误'))
  }
}

// ===== 文档上传 =====
async function handleUpload(e) {
  const file = e.target.files?.[0]
  if (!file || !currentKbId.value) return
  uploading.value = true
  try {
    await uploadDocument(currentKbId.value, file)
    ElMessage.success(`${file.name} 上传成功，正在异步入库`)
    await loadDocuments()
    // 同步刷新 KB 列表的 docCount
    const kb = knowledgeBases.value.find(k => k.id === currentKbId.value)
    if (kb) kb.docCount = (kb.docCount || 0) + 1
  } catch (err) {
    ElMessage.error('上传失败：' + (extractMessage(err) || '未知错误'))
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function removeDoc(doc) {
  try {
    await ElMessageBox.confirm(`确定删除文档「${doc.filename}」吗？`, '删除文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' })
  } catch { return }
  try {
    await deleteDocument(currentKbId.value, doc.id)
    documents.value = documents.value.filter(d => d.id !== doc.id)
    const kb = knowledgeBases.value.find(k => k.id === currentKbId.value)
    if (kb) kb.docCount = Math.max(0, (kb.docCount || 0) - 1)
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.message || '未知错误'))
  }
}

async function reprocessDoc(doc) {
  try {
    await reprocessDocument(currentKbId.value, doc.id)
    doc.status = 'pending'
    ElMessage.success(`文档「${doc.filename}」已触发重新入库`)
    // 轮询状态(简单实现, 2 秒后查一次)
    setTimeout(() => loadDocuments(), 2000)
  } catch (e) {
    ElMessage.error('重新入库失败：' + (e?.message || '未知错误'))
  }
}

// ===== 检索测试 =====
async function runSearch() {
  if (!searchQuery.value.trim()) return
  let ids
  if (multiSearch.value) {
    // 多知识库模式: 未选则默认当前知识库
    ids = multiSearchKbIds.value.length ? multiSearchKbIds.value : [currentKbId.value]
    if (!ids.length) {
      ElMessage.warning('请至少选择或创建一个知识库')
      return
    }
  } else {
    if (!currentKbId.value) return
    ids = [currentKbId.value]
  }
  searching.value = true
  searchDone.value = false
  searchResults.value = []
  try {
    searchResults.value = multiSearch.value
      ? await searchKnowledgeBases(ids, searchQuery.value.trim(), searchTopK.value)
      : await searchKnowledgeBase(ids[0], searchQuery.value.trim(), searchTopK.value)
    searchDone.value = true
  } catch (e) {
    ElMessage.error('检索失败：' + (extractMessage(e) || '未知错误'))
  } finally {
    searching.value = false
  }
}

// 知识库 ID → 名称(多库检索结果标注来源)
function kbName(id) {
  if (!id) return ''
  const k = knowledgeBases.value.find(x => x.id === id)
  return k ? k.name : id
}

// ===== 工具方法 =====

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function formatDate(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

function statusLabel(s) {
  return { pending: '待解析', indexing: '解析中', completed: '已就绪', failed: '失败' }[s] || s
}

function statusTagType(s) {
  return { pending: 'info', indexing: 'warning', completed: 'success', failed: 'danger' }[s] || 'info'
}

function scoreColor(s) {
  if (s >= 0.7) return 'var(--dcdr-success)'
  if (s >= 0.5) return 'var(--dcdr-warning)'
  return 'var(--dcdr-text-3)'
}

onMounted(() => {
  loadSplitterOptions()
  loadKnowledgeBases()
})
</script>

<style scoped>
.kb-mode { flex: 1; display: flex; overflow: hidden; height: 100%; position: relative; }

/* 侧边栏 */
.sidebar { width: 300px; background: linear-gradient(180deg, var(--dcdr-surface) 0%, var(--dcdr-surface) 100%); border-right: 1px solid var(--dcdr-border); display: flex; flex-direction: column; box-shadow: 2px 0 12px rgba(0,0,0,0.03); transition: width 0.25s ease; }
.sidebar.collapsed { width: 0; min-width: 0; border-right: none; overflow: hidden; }
.sidebar-header { padding: 20px 16px; border-bottom: 1px solid var(--dcdr-border); position: relative; }
.collapse-btn { position: absolute; right: 10px; top: 22px; width: 26px; height: 26px; border: none; border-radius: 6px; background: var(--dcdr-bg-subtle); color: var(--dcdr-text-3); cursor: pointer; font-size: 16px; line-height: 1; }
.collapse-btn:hover { background: var(--dcdr-accent-wash); color: var(--dcdr-accent); }
.sidebar-expand { position: absolute; left: 0; top: 14px; z-index: 50; width: 26px; height: 36px; border: none; border-radius: 0 8px 8px 0; background: var(--dcdr-accent); color: #fff; cursor: pointer; font-size: 16px; box-shadow: 2px 0 8px rgba(0,0,0,0.15); }
.new-btn { width: 100%; height: 38px; color: var(--dcdr-accent-soft); font-size: 13px; font-weight: 500; cursor: pointer; border-radius: var(--dcdr-radius-inner); border: 1px solid var(--dcdr-accent-line); background: var(--dcdr-accent-wash); transition: background 0.2s ease, border-color 0.2s ease; }
.new-btn:hover { background: var(--dcdr-active); border-color: var(--dcdr-accent); }

.kb-list { flex: 1; overflow-y: auto; padding: 12px 10px; }
.kb-list::-webkit-scrollbar { width: 4px; }
.kb-list::-webkit-scrollbar-thumb { background: var(--dcdr-bg); border-radius: 4px; }
.kb-item { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-radius: 10px; cursor: pointer; margin-bottom: 6px; transition: all 0.2s ease; }
.kb-item:hover { background: var(--dcdr-bg-subtle); }
.kb-item.active { background: linear-gradient(135deg, var(--dcdr-accent-wash) 0%, var(--dcdr-accent-wash) 100%); border: 1px solid var(--dcdr-accent-wash); }
.kb-icon { font-size: 20px; flex-shrink: 0; }
.kb-info { flex: 1; min-width: 0; }
.kb-name { font-size: 14px; color: var(--dcdr-text-1); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: flex; align-items: center; gap: 6px; }
.kb-name .kb-vis-tag { transform: scale(0.85); transform-origin: left center; flex-shrink: 0; }
.kb-item.active .kb-name { color: var(--dcdr-accent); }
.kb-meta { font-size: 11px; color: var(--dcdr-text-3); margin-top: 2px; display: flex; gap: 4px; }
.kb-desc { font-size: 12px; color: var(--dcdr-text-2); margin-top: 4px; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.kb-delete-btn .ic { width: 14px; height: 14px; }
.kb-delete-btn { border: none; background: transparent; cursor: pointer; color: var(--dcdr-text-3); opacity: 0; padding: 4px 6px; border-radius: 6px; font-size: 13px; flex-shrink: 0; transition: all 0.2s; }
.kb-item:hover .kb-delete-btn { opacity: 1; }
.kb-delete-btn:hover { color: var(--dcdr-danger); background: rgba(245,108,108,0.1); }
.empty-tip { text-align: center; color: var(--dcdr-text-3); font-size: 13px; line-height: 1.8; padding: 40px 10px; }
/* 与「记忆」的分工说明: 常驻在侧栏底部, 不占主区域; .kb-list 是 flex:1, 故它会自然沉底 */
.kb-scope-tip { padding: 10px 14px; font-size: 11.5px; line-height: 1.6; color: var(--dcdr-text-3); border-top: 1px solid var(--dcdr-border); }

/* 主区域 */
.kb-main { flex: 1; display: flex; flex-direction: column; background: var(--dcdr-bg-subtle); overflow: hidden; }
.kb-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 16px 24px; background: var(--dcdr-surface); border-bottom: 1px solid var(--dcdr-border); gap: 12px; }
.kb-toolbar-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.kb-title { font-size: 18px; font-weight: 600; color: var(--dcdr-text-1); margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 360px; }
.kb-desc-full { flex: 1; min-width: 0; font-size: 12px; color: var(--dcdr-text-3); margin-left: 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upload-btn .ic { width: 15px; height: 15px; }
.upload-btn { display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 16px; border-radius: 10px; color: #fff; font-size: 13px; cursor: pointer; user-select: none; transition: all 0.25s; box-shadow: 0 3px 10px var(--dcdr-accent-line); }
.upload-btn:hover:not(.disabled) { transform: translateY(-1px); box-shadow: 0 5px 14px var(--dcdr-accent-line); }
.upload-btn.disabled { opacity: 0.5; cursor: not-allowed; }
.refresh-btn {
  display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 14px;
  border: 1px solid var(--dcdr-border); border-radius: 10px; background: var(--dcdr-surface); color: var(--dcdr-text-2);
  font-size: 13px; cursor: pointer; user-select: none; transition: all 0.2s;
}
.refresh-btn:hover:not(:disabled) { border-color: var(--dcdr-accent); color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }
.refresh-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.refresh-btn .spin { display: inline-block; animation: kb-spin 0.8s linear infinite; }
@keyframes kb-spin { to { transform: rotate(360deg); } }
.chunk-cfg-btn {
  height: 28px; padding: 0 10px; flex: none;
  border: 1px solid var(--dcdr-border); border-radius: 8px;
  background: var(--dcdr-bg-subtle); color: var(--dcdr-text-2);
  font-family: inherit; font-size: 11px; cursor: pointer; white-space: nowrap;
  transition: all 0.2s;
}
.chunk-cfg-btn:hover { border-color: var(--dcdr-accent); color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }

/* 文档表 + 检索测试 同屏 */
.kb-body { flex: 1; display: flex; gap: 16px; padding: 0 24px 20px; overflow: hidden; }

.doc-panel {
  flex: 1; min-width: 0; display: flex; flex-direction: column;
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-inner); overflow: hidden;
}
.doc-head {
  display: flex; align-items: center; gap: 10px; height: 36px; flex: none;
  padding: 0 16px; background: var(--dcdr-surface-faint);
  border-bottom: 1px solid var(--dcdr-border);
}
.doc-head span {
  font-family: var(--dcdr-font-mono); font-size: 9.5px; letter-spacing: 1px;
  color: var(--dcdr-text-3); text-transform: uppercase;
}
.dh-name { flex: 1; }
.dh-size { width: 150px; flex: none; }
.dh-status { width: 68px; flex: none; }
.dh-act { width: 96px; flex: none; }

.doc-list { flex: 1; overflow-y: auto; }
.doc-item {
  display: flex; align-items: center; gap: 10px; height: 54px; padding: 0 16px;
  border-bottom: 1px solid var(--dcdr-border); transition: background 0.15s ease;
}
.doc-item:last-child { border-bottom: none; }
.doc-item:hover { background: var(--dcdr-hover); }
.doc-name { flex: 1; min-width: 0; font-size: 12px; color: var(--dcdr-text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.doc-meta { width: 150px; flex: none; font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-3); }
.doc-status {
  width: 68px; flex: none; display: flex; align-items: center; gap: 4px;
  font-family: var(--dcdr-font-mono); font-size: 9.5px; color: var(--dcdr-success);
}
.doc-status.st-indexing { color: var(--dcdr-warning); }
.doc-status.st-failed { color: var(--dcdr-danger); }
.error-hint { color: var(--dcdr-danger); cursor: help; font-weight: 700; }
.doc-actions { width: 96px; flex: none; display: flex; gap: 4px; justify-content: flex-end; }
.doc-action-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 26px; height: 26px; border: none; border-radius: 7px; background: transparent;
  cursor: pointer; color: var(--dcdr-text-3); transition: all 0.15s;
}
.doc-action-btn .ic { width: 15px; height: 15px; }
.doc-action-btn:hover { background: var(--dcdr-accent-wash); color: var(--dcdr-accent); }
.doc-action-btn.danger:hover { background: var(--dcdr-accent-wash); color: var(--dcdr-danger); }
.doc-action-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.empty-docs { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; height: 100%; min-height: 200px; color: var(--dcdr-text-3); }
.empty-docs p { font-size: 13px; }
.empty-docs span { font-size: 11px; color: var(--dcdr-text-3); }

/* 检索测试面板 */
.search-panel {
  width: 340px; flex: none; display: flex; flex-direction: column; gap: 12px;
  padding: 14px; background: var(--dcdr-surface-faint);
  border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner);
  overflow-y: auto;
}
.sp-head { display: flex; align-items: center; justify-content: space-between; }
.sp-title { font-size: 12px; font-weight: 500; color: var(--dcdr-text-1); }
.sp-mode { font-family: var(--dcdr-font-mono); font-size: 9px; letter-spacing: 0.8px; color: var(--dcdr-text-3); }
.sp-query {
  width: 100%; resize: none; padding: 10px; font-family: inherit; font-size: 11.5px; line-height: 1.6;
  color: var(--dcdr-text-1); background: var(--dcdr-surface-faint);
  border: 1px solid var(--dcdr-accent-line); border-radius: 10px; outline: none;
}
.sp-query::placeholder { color: var(--dcdr-text-3); }
.sp-params { display: flex; flex-direction: column; gap: 8px; }
.sp-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.sp-k { font-size: 11px; color: var(--dcdr-text-2); }
.sp-run {
  height: 32px; border: 1px solid var(--dcdr-accent-line); border-radius: 999px;
  background: var(--dcdr-accent-wash); color: var(--dcdr-accent-soft);
  font-family: inherit; font-size: 12px; cursor: pointer; transition: all 0.15s;
}
.sp-run:hover:not(:disabled) { background: var(--dcdr-active); }
.sp-run:disabled { opacity: 0.45; cursor: not-allowed; }
.sp-results { display: flex; flex-direction: column; gap: 8px; }
.result-item { padding: 10px; border-radius: 10px; background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); }
.result-item:hover { border-color: var(--dcdr-accent-line); }
.result-header { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.result-score { font-family: var(--dcdr-font-mono); font-size: 9.5px; font-weight: 700; }
.result-rank { font-family: var(--dcdr-font-mono); font-size: 9px; color: var(--dcdr-text-3); }
.result-file {
  font-family: var(--dcdr-font-mono); font-size: 9px; color: var(--dcdr-text-3);
  margin-left: auto; max-width: 130px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.result-content { font-size: 10.5px; line-height: 1.6; color: var(--dcdr-text-2); display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.empty-results { padding: 16px 4px; font-size: 10.5px; line-height: 1.7; color: var(--dcdr-text-3); }

.search-results { display: flex; flex-direction: column; gap: 10px; }
.result-item { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 14px 16px; transition: all 0.2s; }
.result-item:hover { border-color: var(--dcdr-accent-wash); box-shadow: 0 2px 8px var(--dcdr-accent-wash); }
.result-header { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.result-rank { font-size: 13px; font-weight: 700; color: var(--dcdr-accent); background: var(--dcdr-accent-wash); padding: 2px 10px; border-radius: 12px; }
.result-score { font-size: 13px; font-weight: 600; }
.result-chunk { font-size: 11px; color: var(--dcdr-text-3); margin-left: auto; }
.result-content { font-size: 13px; line-height: 1.7; color: var(--dcdr-text-1); background: var(--dcdr-bg-subtle); padding: 10px 12px; border-radius: 8px; border-left: 3px solid var(--dcdr-accent-wash); white-space: pre-wrap; word-break: break-word; max-height: 200px; overflow-y: auto; }

.empty-results { text-align: center; padding: 60px 20px; color: var(--dcdr-text-3); font-size: 13px; }

/* 空状态 */
.kb-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--dcdr-text-3); }
.kb-empty .empty-icon.large svg { width: 44px; height: 44px; stroke: var(--dcdr-text-3); }
.empty-icon.large { font-size: 72px; margin-bottom: 16px; }
.kb-empty h2 { font-size: 22px; color: var(--dcdr-text-1); margin: 0 0 8px; }
.kb-empty p { font-size: 14px; margin: 0 0 24px; }
.new-here-btn { height: 40px; padding: 0 24px; border: none; border-radius: 10px; color: #fff; font-size: 14px; cursor: pointer; box-shadow: 0 4px 15px var(--dcdr-accent-line); }

/* 可见性提示 */
.vis-tip { margin-top: 6px; font-size: 12px; color: var(--dcdr-text-3); line-height: 1.5; }

/* 切分配置 */
.split-tip { margin-top: 5px; font-size: 11.5px; line-height: 1.5; color: var(--dcdr-text-3); }
.split-unit { margin-left: 8px; font-size: 11.5px; color: var(--dcdr-text-3); }
.chunk-dialog-tip {
  margin-bottom: 14px; padding: 10px 12px; border-radius: 8px;
  background: var(--dcdr-accent-wash); color: var(--dcdr-text-2);
  font-size: 12px; line-height: 1.6;
}
.chunk-dialog-tip b { color: var(--dcdr-accent); font-weight: 600; }

/* 切分预览 */
.preview-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.preview-hint { font-size: 11.5px; color: var(--dcdr-text-3); }
.preview-stats {
  display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; padding: 8px 10px;
  border-radius: 8px; background: var(--dcdr-bg-subtle);
  font-size: 11.5px; color: var(--dcdr-text-2);
}
.preview-stats b { color: var(--dcdr-accent); }
.preview-list {
  margin-top: 10px; max-height: 42vh; overflow-y: auto;
  display: flex; flex-direction: column; gap: 8px;
}
.preview-item {
  border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 8px 10px;
  background: var(--dcdr-surface-faint);
}
.preview-item-head {
  display: flex; justify-content: space-between; margin-bottom: 4px;
  font-family: var(--dcdr-font-mono); font-size: 9.5px; color: var(--dcdr-text-3);
}
.preview-index { color: var(--dcdr-accent); font-weight: 700; }
.preview-content {
  font-size: 11.5px; line-height: 1.65; color: var(--dcdr-text-2);
  white-space: pre-wrap; word-break: break-word; max-height: 140px; overflow-y: auto;
}
.preview-ellipsis { color: var(--dcdr-text-3); }
.preview-more { margin-top: 8px; text-align: center; font-size: 11px; color: var(--dcdr-text-3); }
.preview-empty { padding: 18px 4px; font-size: 11.5px; color: var(--dcdr-text-3); line-height: 1.7; }
.chunk-view-list { max-height: 50vh; min-height: 80px; }
.chunk-pager { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 12px; }
.chunk-pager-info { font-family: var(--dcdr-font-mono); font-size: 10px; color: var(--dcdr-text-3); }
</style>
