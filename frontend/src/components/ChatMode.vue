<template>
  <div class="chat-mode">
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed}">
      <div class="sidebar-header">
        <button class="new-btn gradient-bg" @click="newSession">+ 新建对话</button>
        <button class="collapse-btn" @click="sidebarCollapsed = true" title="收起侧边栏">«</button>
      </div>
      <div class="session-list">
        <div v-for="s in sessions" :key="s.id" class="session-item" :class="{active: s.id===currentSessionId}" @click="switchSession(s.id)">
          <template v-if="editingId === s.id">
            <input class="rename-input" v-model="editText" v-focus
                   @click.stop @keyup.enter="saveRename(s.id)" @keyup.esc="cancelRename" @blur="saveRename(s.id)" />
          </template>
          <template v-else>
            <span class="session-title">{{ s.title }}</span>
            <button class="rename-btn" @click.stop="startRename(s)" title="重命名">✎</button>
            <button class="delete-btn" @click.stop="removeSession(s)" title="删除">🗑</button>
          </template>
        </div>
      </div>
    </aside>
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false" title="展开侧边栏">»</button>

    <div class="chat-area">
      <!-- 上下文警告 -->
      <div v-if="contextWarning" class="context-warning">
        <span class="cw-icon">⚠</span>
        <span class="cw-text">{{ contextWarning }}</span>
        <button class="cw-new" @click="newSession">开启新对话</button>
        <button class="cw-close" @click="contextWarning = null" title="忽略">✕</button>
      </div>

      <!-- 消息列表 -->
      <div class="messages" ref="msgBox">
        <div v-if="messages.length === 0" class="welcome">
          <img :src="logoUrl" class="welcome-icon" alt="DCDR" />
          <h2>你好，我是 DCDR</h2>
          <p>支持文字对话、图片理解、视频分析</p>
          <div class="quick-actions">
            <div class="quick-card" @click="quickSend('帮我分析这张图片')">
              <span class="quick-icon">🖼️</span>
              <span>图片理解</span>
            </div>
            <div class="quick-card" @click="quickSend('描述这个视频的内容')">
              <span class="quick-icon">🎬</span>
              <span>视频分析</span>
            </div>
            <div class="quick-card" @click="quickSend('帮我创作一张图片')">
              <span class="quick-icon">🎨</span>
              <span>AI绘图</span>
            </div>
          </div>
        </div>

        <!-- 消息项 -->
        <div v-for="msg in messages" :key="msg.id" class="msg-item" :class="'msg-' + msg.role">
          <div class="msg-avatar" :class="{ 'gradient-bg': msg.role === 'user' }">
            <img v-if="msg.role !== 'user'" :src="logoUrl" class="avatar-img" alt="DCDR" />
            <template v-else>U</template>
          </div>
          <div class="msg-content">
            <div class="msg-role">{{ msg.role === 'user' ? '你' : 'DCDR' }}</div>

            <!-- 图片消息 -->
            <div v-if="msg.images && msg.images.length" class="msg-images">
              <div v-for="(img, idx) in msg.images" :key="idx" class="msg-image-wrapper">
                <img :src="img.url || img" :alt="img.name || '图片'" class="msg-image"
                     @click="previewImage(img.url || img)" loading="lazy" />
                <div v-if="img.name" class="image-name">{{ img.name }}</div>
              </div>
            </div>

            <!-- 视频消息 -->
            <div v-if="msg.videos && msg.videos.length" class="msg-videos">
              <div v-for="(vid, idx) in msg.videos" :key="idx" class="msg-video-wrapper">
                <video :src="vid.url || vid" controls class="msg-video" preload="metadata"></video>
                <div v-if="vid.name" class="video-name">{{ vid.name }}</div>
              </div>
            </div>

            <!-- 推理过程 -->
            <div v-if="msg.reasoningContent" class="msg-reasoning">
              <details>
                <summary>💭 推理过程</summary>
                <div v-html="renderMarkdown(msg.reasoningContent)"></div>
              </details>
            </div>

            <!-- 文本内容 -->
            <div v-if="editingSeq !== null && editingSeq === msg.seq" class="edit-box">
              <textarea v-model="editBuffer" class="edit-textarea" placeholder="编辑消息内容..."
                        @keydown.ctrl.enter="saveEdit(msg)" @keydown.meta.enter="saveEdit(msg)"></textarea>
              <div class="msg-actions">
                <button class="btn btn-small btn-primary" @click="saveEdit(msg)">保存并重新生成</button>
                <button class="btn btn-small" @click="cancelEdit">取消</button>
              </div>
            </div>
            <template v-else>
              <div v-if="msg.content" class="msg-bubble" v-html="renderMarkdown(msg.content)"></div>

              <!-- 元信息 -->
              <div v-if="msg.role === 'assistant' && (msg.tokenUsage != null || msg.durationMs != null)" class="msg-meta">
                <span v-if="msg.durationMs != null">⏱ {{ (msg.durationMs / 1000).toFixed(1) }}s</span>
                <span v-if="msg.tokenUsage != null">🪙 {{ msg.tokenUsage }} token</span>
              </div>

              <!-- 操作: 编辑历史消息 / 重新生成助手回答 -->
              <div v-if="!streaming" class="msg-actions">
                <button v-if="msg.role === 'user'" class="btn btn-small" @click="startEdit(msg)" title="编辑并重新生成">✏️ 编辑</button>
                <button v-if="msg.role === 'assistant'" class="btn btn-small" @click="retry(msg)" title="重新生成此回答">🔄 重新生成</button>
              </div>
            </template>
          </div>
        </div>

        <!-- 流式输出中 -->
        <div v-if="streaming" class="msg-item msg-assistant">
          <div class="msg-avatar"><img :src="logoUrl" class="avatar-img" alt="DCDR" /></div>
          <div class="msg-content">
            <div class="msg-role">DCDR</div>
            <div v-if="reasoningContent" class="msg-reasoning">
              <details open>
                <summary>💭 推理过程</summary>
                <div v-html="renderMarkdown(reasoningContent)"></div>
              </details>
            </div>
            <div v-if="statusHint && !streamContent && !reasoningContent" class="msg-status">
              <span class="status-dot"></span>{{ statusHint }}
            </div>
            <div v-else class="msg-bubble streaming">{{ streamContent }}<span class="cursor">|</span></div>
          </div>
        </div>
      </div>

      <!-- 输入区域 -->
      <div class="input-area">
        <!-- 已选图片预览 -->
        <div v-if="selectedFiles.length" class="selected-files">
          <div v-for="(file, idx) in selectedFiles" :key="idx" class="selected-file">
            <div class="file-preview">
              <img v-if="isImage(file)" :src="file.preview" alt="preview" />
              <div v-else class="file-icon">📁</div>
              <button class="remove-file" @click="removeFile(idx)">✕</button>
            </div>
            <span class="file-name">{{ file.file.name }}</span>
          </div>
        </div>

        <div class="input-box">
          <div class="input-actions">
            <!-- 图片上传按钮 -->
            <label class="action-btn" title="上传图片/视频">
              <input type="file" ref="fileInput" @change="handleFileSelect" accept="image/*,video/*" multiple hidden />
              📎
            </label>
          </div>
          <textarea v-model="input" placeholder="输入消息，Enter发送，Shift+Enter换行"
                    @keydown.enter.exact.prevent="send" rows="1"
                    :style="{ maxHeight: selectedFiles.length ? '80px' : '160px' }"></textarea>
          <button class="send-btn gradient-bg" @click="streaming ? stop() : send()"
                  :disabled="(!input.trim() && !selectedFiles.length) && !streaming">
            {{ streaming ? '■' : '➤' }}
          </button>
        </div>
        <div class="input-hint">支持上传图片、视频进行对话分析</div>
      </div>
    </div>

    <!-- 图片预览弹窗 -->
    <div v-if="previewImageUrl" class="image-preview-modal" @click="previewImageUrl = null">
      <img :src="previewImageUrl" class="preview-image" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { getSessions, createSession, getMessages, streamChat, stopChat, renameSession, deleteSession, uploadFile, deleteMessages } from '../api'
import { ElMessageBox, ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import logoUrl from '../assets/dcdr-logo.png'

const md = new MarkdownIt()
const vFocus = { mounted: (el) => el.focus() }
const props = defineProps({ currentModel: String })

const sessions = ref([])
const currentSessionId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const streamContent = ref('')
const reasoningContent = ref('')
const statusHint = ref('')
const msgBox = ref(null)
const editingId = ref(null)
const editText = ref('')
const contextWarning = ref(null)
const lastTokenUsage = ref(null)
const lastDurationMs = ref(null)

// 消息编辑/重试
const editingSeq = ref(null)
const editBuffer = ref('')

// 文件相关
const fileInput = ref(null)
const selectedFiles = ref([])
const previewImageUrl = ref(null)
const sidebarCollapsed = ref(false)

const renderMarkdown = (text) => md.render(text || '')

function isImage(file) {
  return file.file.type.startsWith('image/')
}

function isVideo(file) {
  return file.file.type.startsWith('video/')
}

async function loadSessions() { sessions.value = await getSessions() }

async function newSession() {
  const s = await createSession({ title: 'New Chat', model: props.currentModel })
  sessions.value.unshift(s)
  currentSessionId.value = s.id
  messages.value = []
}

async function switchSession(id) {
  if (editingId.value) return
  currentSessionId.value = id
  messages.value = await getMessages(id)
  scrollBottom()
}

function startRename(s) {
  editingId.value = s.id
  editText.value = s.title
}

function cancelRename() {
  editingId.value = null
  editText.value = ''
}

async function saveRename(id) {
  if (editingId.value === null) return
  const title = editText.value.trim()
  editingId.value = null
  editText.value = ''
  if (!title) return
  const s = sessions.value.find(x => x.id === id)
  if (s && s.title === title) return
  await renameSession(id, title)
  await loadSessions()
}

async function removeSession(s) {
  if (streaming.value && s.id === currentSessionId.value) return
  try {
    await ElMessageBox.confirm(
      `确定删除对话「${s.title}」吗？此操作不可恢复。`,
      '删除对话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  await deleteSession(s.id)
  sessions.value = sessions.value.filter(x => x.id !== s.id)
  if (s.id === currentSessionId.value) {
    currentSessionId.value = null
    messages.value = []
    contextWarning.value = null
  }
  ElMessage.success('已删除')
}

// 文件选择处理
function handleFileSelect(e) {
  const files = Array.from(e.target.files)
  files.forEach(file => {
    if (file.type.startsWith('image/') || file.type.startsWith('video/')) {
      const reader = new FileReader()
      reader.onload = (ev) => {
        selectedFiles.value.push({
          file,
          preview: ev.target.result,
          uploaded: false,
          url: null
        })
      }
      reader.readAsDataURL(file)
    }
  })
  e.target.value = '' // 重置以便重复选择同一文件
}

function removeFile(idx) {
  selectedFiles.value.splice(idx, 1)
}

function previewImage(url) {
  previewImageUrl.value = url
}

function quickSend(text) {
  input.value = text
  send()
}

function scrollBottom() { nextTick(() => { if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight }) }

async function send(opts = {}) {
  const overrideImages = opts.images || null
  const text = input.value.trim()
  const hasFiles = selectedFiles.value.length > 0

  if ((!text && !hasFiles && !overrideImages) || streaming.value) return
  contextWarning.value = null
  lastTokenUsage.value = null
  lastDurationMs.value = null

  if (!currentSessionId.value) await newSession()

  // 构建用户消息
  const userMsg = { id: Date.now(), role: 'user', content: text }

  // 处理文件上传
  if (hasFiles) {
    userMsg.images = []
    userMsg.videos = []

    for (const sf of selectedFiles.value) {
      try {
        if (!sf.uploaded) {
          const result = await uploadFile(sf.file)
          sf.uploaded = true
          sf.url = result.url || result
        }
        if (isImage(sf)) {
          userMsg.images.push({ url: sf.url, name: sf.file.name })
        } else {
          userMsg.videos.push({ url: sf.url, name: sf.file.name })
        }
      } catch (err) {
        console.error('文件上传失败:', err)
        ElMessage.error(`上传 ${sf.file.name} 失败`)
      }
    }
  } else if (overrideImages) {
    // 编辑/重试时复用已上传的图片(无需重新上传)
    userMsg.images = overrideImages.map(i => typeof i === 'string' ? { url: i, name: '' } : i)
    userMsg.videos = []
  }

  messages.value.push(userMsg)
  input.value = ''
  selectedFiles.value = []

  streaming.value = true
  streamContent.value = ''
  reasoningContent.value = ''
  statusHint.value = ''
  let interrupted = false
  scrollBottom()

  await streamChat(
    {
      sessionId: currentSessionId.value,
      message: text,
      model: props.currentModel,
      images: (userMsg.images || []).map(i => i.url)
    },
    (chunk) => {
      if (chunk.type === 'status') { statusHint.value = chunk.message || ''; scrollBottom(); return }
      if (chunk.type === 'error') { statusHint.value = ''; streamContent.value += '\n[错误] ' + (chunk.message || ''); return }
      if (chunk.type === 'interrupted') { statusHint.value = ''; interrupted = true; return }
      if (chunk.type === 'context_warning') { statusHint.value = ''; contextWarning.value = chunk.message || '对话已较长，建议开启新对话'; return }
      if (chunk.type === 'done') {
        statusHint.value = ''
        lastTokenUsage.value = chunk.tokenUsage?.total ?? null
        lastDurationMs.value = chunk.durationMs ?? null
        return
      }
      if (chunk.type !== 'chunk') return
      statusHint.value = ''
      const ok = (v) => v != null
      if (ok(chunk.reasoningContent)) { reasoningContent.value += chunk.reasoningContent; scrollBottom() }
      if (ok(chunk.content)) { streamContent.value += chunk.content; scrollBottom() }
    },
    () => {
      if (streamContent.value || reasoningContent.value) {
        messages.value.push({
          id: Date.now(),
          role: 'assistant',
          content: streamContent.value,
          reasoningContent: reasoningContent.value,
          interrupted,
          tokenUsage: lastTokenUsage.value,
          durationMs: lastDurationMs.value
        })
      }
      streamContent.value = ''
      reasoningContent.value = ''
      statusHint.value = ''
      streaming.value = false
      loadSessions()
      scrollBottom()
      // 将后端 seq 回写到本地消息, 以支持编辑/重试时按 seq 截断历史
      if (currentSessionId.value) {
        getMessages(currentSessionId.value)
          .then(list => {
            if (list && list.length === messages.value.length) {
              list.forEach((m, i) => { if (messages.value[i]) messages.value[i].seq = m.seq })
            } else if (list && list.length) {
              messages.value = list
            }
          })
          .catch(() => {})
      }
    },
    (e) => { streaming.value = false; streamContent.value = ''; reasoningContent.value = ''; statusHint.value = '' }
  )
}

/** 中断当前会话(停止生成) */
function stop() {
  if (!streaming.value || !currentSessionId.value) return
  stopChat(currentSessionId.value)
}

/** 进入编辑态 */
function startEdit(msg) {
  editingSeq.value = msg.seq
  editBuffer.value = msg.content || ''
}

/** 取消编辑 */
function cancelEdit() {
  editingSeq.value = null
  editBuffer.value = ''
}

/** 保存编辑后的消息: 截断该消息之后的历史并重新生成 */
async function saveEdit(msg) {
  const newContent = (editBuffer.value || '').trim()
  if (!newContent) return
  const seq = msg.seq
  const idx = messages.value.indexOf(msg)
  editingSeq.value = null
  editBuffer.value = ''
  if (seq != null) {
    try { await deleteMessages(currentSessionId.value, seq) } catch (e) { console.error('删除历史消息失败', e) }
  }
  // 截断该消息及其后的所有消息(本地)
  if (idx >= 0) messages.value = messages.value.slice(0, idx)
  input.value = newContent
  await send({ images: msg.images })
}

/** 重新生成助手回答: 移除该助手消息及其前一条用户消息之后的历史, 并重新发送用户消息 */
async function retry(msg) {
  const idx = messages.value.indexOf(msg)
  if (idx < 0) return
  const userMsg = messages.value[idx - 1]
  if (!userMsg || userMsg.role !== 'user') return
  const userSeq = userMsg.seq
  if (userSeq != null) {
    try { await deleteMessages(currentSessionId.value, userSeq) } catch (e) { console.error('删除历史消息失败', e) }
  }
  // 移除用户消息及其后的所有消息(含失败的助手消息)
  messages.value = messages.value.slice(0, idx - 1)
  input.value = userMsg.content
  await send({ images: userMsg.images })
}

onMounted(() => { loadSessions() })
</script>

<style scoped>
.chat-mode { flex: 1; display: flex; overflow: hidden; height: 100%; position: relative; }

/* 侧边栏 */
.sidebar { width: 280px; background: linear-gradient(180deg, #ffffff 0%, #f8f9fc 100%); border-right: 1px solid #e8ecf4; display: flex; flex-direction: column; box-shadow: 2px 0 12px rgba(0,0,0,0.03); transition: width 0.25s ease; }
.sidebar.collapsed { width: 0; min-width: 0; border-right: none; overflow: hidden; }
.sidebar-header { padding: 20px 16px; border-bottom: 1px solid #f0f2f7; position: relative; }
.collapse-btn { position: absolute; right: 10px; top: 22px; width: 26px; height: 26px; border: none; border-radius: 6px; background: #f0f2f7; color: #999; cursor: pointer; font-size: 16px; line-height: 1; }
.collapse-btn:hover { background: rgba(102,126,234,0.12); color: #667eea; }
.sidebar-expand { position: absolute; left: 0; top: 14px; z-index: 50; width: 26px; height: 36px; border: none; border-radius: 0 8px 8px 0; background: #667eea; color: #fff; cursor: pointer; font-size: 16px; box-shadow: 2px 0 8px rgba(0,0,0,0.15); }
.sidebar-expand:hover { background: #5568d3; }
.new-btn { width: 100%; height: 44px; color: #fff; border: none; border-radius: 12px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.3s ease; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); box-shadow: 0 4px 15px rgba(102, 126, 234, 0.35); }
.new-btn:hover { transform: translateY(-1px); box-shadow: 0 6px 20px rgba(102, 126, 234, 0.45); }
.session-list { flex: 1; overflow-y: auto; padding: 12px 10px; }
.session-list::-webkit-scrollbar { width: 4px; }
.session-list::-webkit-scrollbar-thumb { background: #dcdfe6; border-radius: 4px; }
.session-item { padding: 12px 14px; border-radius: 10px; cursor: pointer; margin-bottom: 6px; display: flex; align-items: center; gap: 8px; transition: all 0.2s ease; }
.session-item:hover { background: #f5f7fa; }
.session-item.active { background: linear-gradient(135deg, rgba(102,126,234,0.1) 0%, rgba(118,75,162,0.08) 100%); color: #667eea; font-weight: 500; border: 1px solid rgba(102,126,234,0.15); }
.session-title { font-size: 13px; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rename-btn, .delete-btn { border: none; background: transparent; cursor: pointer; font-size: 13px; color: #909399; opacity: 0; padding: 4px 6px; border-radius: 6px; flex-shrink: 0; transition: all 0.2s; }
.session-item:hover .rename-btn, .session-item:hover .delete-btn { opacity: 1; }
.rename-btn:hover { color: #667eea; background: rgba(102,126,234,0.1); }
.delete-btn:hover { color: #f56c6c; background: rgba(245,108,108,0.1); }
.rename-input { flex: 1; font-size: 13px; border: 2px solid #667eea; border-radius: 8px; padding: 6px 10px; outline: none; min-width: 0; }

/* 聊天区域 */
.chat-area { flex: 1; display: flex; flex-direction: column; background: linear-gradient(180deg, #fafbfc 0%, #f5f7fa 100%); position: relative; overflow: hidden; }
.messages { flex: 1; overflow-y: auto; padding: 24px 32px; display: flex; flex-direction: column; }
.messages::-webkit-scrollbar { width: 6px; }
.messages::-webkit-scrollbar-thumb { background: #dcdfe6; border-radius: 6px; }

/* 欢迎页 */
.welcome { text-align: center; padding-top: 100px; max-width: 600px; margin: 0 auto; }
.welcome-icon { width: 80px; height: 80px; border-radius: 20px; object-fit: cover; margin-bottom: 20px; box-shadow: 0 8px 30px rgba(102,126,234,0.25); }
.welcome h2 { font-size: 28px; color: #1a1a2e; margin-bottom: 10px; font-weight: 700; }
.welcome p { color: #606266; font-size: 15px; margin-bottom: 32px; }
.quick-actions { display: flex; gap: 16px; justify-content: center; flex-wrap: wrap; }
.quick-card { display: flex; align-items: center; gap: 10px; padding: 14px 20px; background: #fff; border: 1px solid #e8ecf4; border-radius: 14px; cursor: pointer; transition: all 0.25s ease; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.quick-card:hover { border-color: #667eea; box-shadow: 0 4px 16px rgba(102,126,234,0.15); transform: translateY(-2px); }
.quick-icon { font-size: 22px; }
.quick-card span:last-child { font-size: 14px; color: #303133; font-weight: 500; }

/* 消息项 */
.msg-item { display: flex; gap: 14px; margin-bottom: 28px; max-width: 900px; margin-left: auto; margin-right: auto; width: 100%; animation: fadeInUp 0.3s ease; }
@keyframes fadeInUp { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
.msg-avatar { width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 13px; font-weight: 600; flex-shrink: 0; overflow: hidden; box-shadow: 0 3px 10px rgba(0,0,0,0.1); }
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.msg-content { flex: 1; min-width: 0; }
.msg-role { font-size: 13px; color: #909399; margin-bottom: 6px; font-weight: 500; }

/* 图片消息 */
.msg-images { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.msg-image-wrapper { position: relative; }
.msg-image { max-width: 280px; max-height: 240px; border-radius: 12px; object-fit: cover; cursor: pointer; transition: all 0.25s ease; box-shadow: 0 4px 12px rgba(0,0,0,0.1); border: 1px solid #e8ecf4; }
.msg-image:hover { transform: scale(1.02); box-shadow: 0 6px 20px rgba(0,0,0,0.15); }
.image-name { font-size: 11px; color: #909399; text-align: center; margin-top: 4px; max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 视频消息 */
.msg-videos { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.msg-video-wrapper { position: relative; }
.msg-video { max-width: 400px; max-height: 300px; border-radius: 12px; background: #000; }
.video-name { font-size: 11px; color: #909399; text-align: center; margin-top: 4px; max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 推理过程 */
.msg-reasoning { margin-bottom: 8px; }
.msg-reasoning details { background: linear-gradient(135deg, #f8f9fc 0%, #f0f2f7 100%); border: 1px solid #e4e7ed; border-radius: 10px; padding: 10px 14px; }
.msg-reasoning summary { cursor: pointer; color: #606266; font-size: 12px; font-weight: 500; user-select: none; outline: none; padding: 4px 0; }
.msg-reasoning details > div { color: #606266; font-size: 13px; line-height: 1.7; margin-top: 10px; }
.msg-reasoning details > div :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 8px; overflow-x: auto; }
.msg-reasoning details > div :deep(code) { background: #fff; padding: 2px 8px; border-radius: 4px; }

/* 消息气泡 */
.msg-bubble { padding: 14px 18px; border-radius: 14px; background: #fff; border: 1px solid #e8ecf4; font-size: 14px; line-height: 1.8; box-shadow: 0 2px 8px rgba(0,0,0,0.04); word-break: break-word; }
.msg-bubble.streaming { border-color: #667eea33; }
.msg-status { display: inline-flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 12px;
  background: #667eea11; color: #667eea; font-size: 14px; }
.msg-status .status-dot { width: 8px; height: 8px; border-radius: 50%; background: #667eea;
  animation: statusPulse 1s ease-in-out infinite; }
@keyframes statusPulse { 0%, 100% { opacity: 0.3; transform: scale(0.8); } 50% { opacity: 1; transform: scale(1.2); } }
.msg-bubble :deep(pre) { background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); color: #e0e0e0; padding: 16px; border-radius: 10px; overflow-x: auto; margin: 10px 0; }
.msg-bubble :deep(code) { background: #f5f7fa; padding: 3px 8px; border-radius: 5px; font-size: 13px; color: #e83e8c; }
.msg-bubble :deep(p) { margin: 8px 0; }
.msg-bubble :deep(img) { max-width: 100%; border-radius: 8px; margin: 8px 0; }

/* 元信息 */
.msg-meta { margin-top: 8px; display: flex; gap: 10px; font-size: 12px; color: #909399; }
.msg-meta span { background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 8px; padding: 3px 10px; }

/* 光标动画 */
.cursor { animation: blink 1s infinite; color: #667eea; font-weight: bold; }
@keyframes blink { 50% { opacity: 0; } }

/* 输入区域 */
.input-area { padding: 16px 28px 20px; background: linear-gradient(180deg, transparent 0%, rgba(255,255,255,0.8) 100%); }

/* 已选文件预览 */
.selected-files { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; max-width: 844px; margin-left: auto; margin-right: auto; }
.selected-file { display: flex; flex-direction: column; align-items: center; gap: 4px; }
.file-preview { position: relative; width: 72px; height: 72px; border-radius: 10px; overflow: hidden; border: 2px solid #667eea44; background: #f5f7fa; }
.file-preview img { width: 100%; height: 100%; object-fit: cover; }
.file-icon { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; font-size: 28px; }
.remove-file { position: absolute; top: 2px; right: 2px; width: 18px; height: 18px; border-radius: 50%; border: none; background: rgba(0,0,0,0.5); color: #fff; font-size: 10px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.remove-file:hover { background: #f56c6c; }
.file-name { font-size: 10px; color: #909399; max-width: 72px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: center; }

/* 输入框 */
.input-box { max-width: 844px; margin: 0 auto; background: #fff; border: 1px solid #dcdfe6; border-radius: 16px; padding: 12px 16px; display: flex; gap: 10px; align-items: flex-end; box-shadow: 0 4px 20px rgba(0,0,0,0.06); transition: all 0.25s ease; }
.input-box:focus-within { border-color: #667eea; box-shadow: 0 4px 25px rgba(102,126,234,0.15); }
.input-actions { display: flex; gap: 4px; padding-bottom: 4px; }
.action-btn { display: flex; align-items: center; justify-content: center; width: 36px; height: 36px; border-radius: 10px; cursor: pointer; font-size: 18px; transition: all 0.2s; color: #606266; background: #f5f7fa; border: none; }
.action-btn:hover { background: #667eea15; color: #667eea; }
.input-box textarea { flex: 1; border: none; outline: none; resize: none; font-size: 14px; line-height: 1.6; max-height: 160px; font-family: inherit; padding: 4px 0; background: transparent; }
.send-btn { width: 40px; height: 40px; border: none; border-radius: 10px; color: #fff; cursor: pointer; font-size: 16px; display: flex; align-items: center; justify-content: center; transition: all 0.25s ease; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); box-shadow: 0 4px 12px rgba(102,126,234,0.35); }
.send-btn:hover:not(:disabled) { transform: scale(1.05); box-shadow: 0 6px 18px rgba(102,126,234,0.45); }
.send-btn:disabled { opacity: 0.4; cursor: not-allowed; transform: none; }

.input-hint { text-align: center; font-size: 12px; color: #c0c4cc; margin-top: 8px; }

/* 上下文警告 */
.context-warning { max-width: 900px; margin: 12px auto 0; display: flex; align-items: center; gap: 10px; padding: 12px 16px; background: linear-gradient(135deg, #fff7e6 0%, #fffbf0 100%); border: 1px solid #ffd591; border-radius: 12px; font-size: 13px; color: #ad6800; animation: fadeInUp 0.3s ease; }
.context-warning .cw-icon { font-size: 16px; }
.context-warning .cw-text { flex: 1; line-height: 1.5; }
.context-warning .cw-new { border: none; background: linear-gradient(135deg, #fa8c16 0%, #ff9c1a 100%); color: #fff; padding: 6px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 500; flex-shrink: 0; transition: all 0.2s; }
.context-warning .cw-new:hover { box-shadow: 0 3px 10px rgba(250,140,22,0.35); }
.context-warning .cw-close { border: none; background: transparent; color: #ad6800; cursor: pointer; font-size: 16px; padding: 4px 8px; border-radius: 6px; flex-shrink: 0; transition: all 0.2s; }
.context-warning .cw-close:hover { background: #ffe7ba; border-radius: 6px; }

/* 图片预览弹窗 */
.image-preview-modal { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.85); z-index: 9999; display: flex; align-items: center; justify-content: center; cursor: pointer; animation: fadeIn 0.2s ease; }
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
.preview-image { max-width: 90vw; max-height: 90vh; object-fit: contain; border-radius: 8px; box-shadow: 0 20px 60px rgba(0,0,0,0.5); }

/* 消息操作(编辑/重试) */
.msg-actions { margin-top: 8px; display: flex; gap: 8px; }
.btn { display: inline-flex; align-items: center; gap: 4px; border: 1px solid #dcdfe6; background: #fff; color: #606266; padding: 5px 12px; border-radius: 8px; font-size: 12px; cursor: pointer; transition: all 0.2s; }
.btn:hover { border-color: #667eea; color: #667eea; background: #667eea0d; }
.btn-small { padding: 4px 10px; font-size: 12px; }
.btn-primary { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border-color: transparent; color: #fff; }
.btn-primary:hover { color: #fff; opacity: 0.9; }

/* 编辑历史消息 */
.edit-box { background: #fff; border: 1px solid #667eea44; border-radius: 14px; padding: 10px; box-shadow: 0 2px 8px rgba(102,126,234,0.08); }
.edit-textarea { width: 100%; min-height: 84px; resize: vertical; border: 1px solid #dcdfe6; border-radius: 8px; background: #fafbff; color: #303133; padding: 10px 12px; font-size: 14px; line-height: 1.6; outline: none; font-family: inherit; box-sizing: border-box; }
.edit-textarea:focus { border-color: #667eea; background: #fff; }
</style>
