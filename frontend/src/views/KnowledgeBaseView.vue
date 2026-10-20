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
            <el-tag size="small">{{ currentKb.chunkSize || 1000 }} / {{ currentKb.chunkOverlap || 200 }}</el-tag>
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
                <span class="doc-actions" v-if="canEditKb(currentKb)">
                  <button class="doc-action-btn" @click="reprocessDoc(doc)" title="重新入库"
                          :disabled="doc.status === 'indexing'">
                    <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20 12A8 8 0 1 1 17.2 6.1"/><path d="M20 4.6V9.4H15.2"/></svg>
                  </button>
                  <button class="doc-action-btn danger" @click="removeDoc(doc)" title="删除">
                    <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4.6 7.2H19.4"/><path d="M9.4 7.2V5.4C9.4 4.8 9.8 4.4 10.4 4.4H13.6C14.2 4.4 14.6 4.8 14.6 5.4V7.2"/><path d="M6.6 7.2L7.4 19C7.4 19.6 7.9 20 8.5 20H15.5C16.1 20 16.6 19.6 16.6 19L17.4 7.2"/></svg>
                  </button>
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
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  getKnowledgeBases, createKnowledgeBase, deleteKnowledgeBase,
  listDocuments, uploadDocument, deleteDocument, reprocessDocument,
  searchKnowledgeBase, searchKnowledgeBases
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

// 创建弹窗
const createDialogVisible = ref(false)
const createForm = ref({ name: '', description: '', visibility: 'USER' })
const creating = ref(false)

// 检索测试
const searchQuery = ref('')
const searchTopK = ref(5)
const searchResults = ref([])
const searching = ref(false)
const searchDone = ref(false)
// 多知识库合并检索 (V3.2)
const multiSearch = ref(false)
const multiSearchKbIds = ref([])

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
  createForm.value = { name: '', description: '', visibility: 'USER' }
  createDialogVisible.value = true
}

async function confirmCreate() {
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  creating.value = true
  try {
    // 普通用户只能创建用户级知识库(平台级选项仅 admin 可见, 此处二次兜底)
    const visibility = isAdmin.value ? (createForm.value.visibility || 'USER') : 'USER'
    const kb = await createKnowledgeBase({
      name: createForm.value.name.trim(),
      description: createForm.value.description || null,
      visibility
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
function fileIcon(type) {
  const map = { pdf: '📕', docx: '📘', doc: '📘', txt: '📄', md: '📝', markdown: '📝', html: '🌐', htm: '🌐' }
  return map[type] || '📄'
}

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

onMounted(() => { loadKnowledgeBases() })
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
.dh-act { width: 68px; flex: none; }

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
.doc-actions { width: 68px; flex: none; display: flex; gap: 4px; justify-content: flex-end; }
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
</style>
