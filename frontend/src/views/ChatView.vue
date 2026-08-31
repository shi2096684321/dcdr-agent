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
            <button class="settings-btn" @click.stop="openSettings(s)" title="会话设置(人设/约束)">⚙</button>
            <el-dropdown class="export-dropdown" trigger="click" @command="fmt => exportSession(s, fmt)" @click.stop>
              <button class="export-btn" @click.stop title="导出对话">⬇</button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="markdown">Markdown (.md)</el-dropdown-item>
                  <el-dropdown-item command="json">JSON (.json)</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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
        <div v-if="messages.length === 0 && !streaming" class="welcome">
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

        <!-- 已持久化消息项 -->
        <MessageBubble
          v-for="msg in messages" :key="msg.id"
          :msg="msg" :streaming="streaming"
          :editing="editingSeq === msg.seq"
          v-model:editBuffer="editBuffer"
          @start-edit="startEdit"
          @save-edit="saveEdit"
          @cancel-edit="cancelEdit"
          @retry="retry"
          @preview-image="previewImage"
        />

        <!-- 流式输出中(仅当当前会话正是该流归属会话时展示; 切走则不显示, 返回继续) -->
        <div v-if="streaming && streamSessionId === currentSessionId" class="msg-item msg-assistant">
          <div class="msg-avatar"><img :src="logoUrl" class="avatar-img" alt="DCDR" /></div>
          <div class="msg-content">
            <div class="msg-role">DCDR</div>
            <div v-if="reasoningContent" class="msg-reasoning">
              <details open>
                <summary>💭 推理过程</summary>
                <div v-html="renderMarkdown(reasoningContent)"></div>
              </details>
            </div>
            <div v-if="streamImages.length" class="msg-images">
              <div v-for="(img, idx) in streamImages" :key="idx" class="msg-image-wrapper">
                <img :src="img" :alt="'生成的图片'" class="msg-image"
                     @click="previewImage(img)" loading="lazy" />
              </div>
            </div>
            <div v-if="pipeline" class="search-pipeline">
              <div class="pipeline-head">
                <span class="pipeline-icon">🌐</span>
                <span class="pipeline-title">联网搜索流水线</span>
                <span v-if="pipeline.done" class="pipeline-done">完成</span>
              </div>
              <div class="pipeline-steps">
                <div class="pstep" :class="{ active: pipeline.stage >= 1, done: pipeline.stage > 1 }">
                  <span class="pstep-no">1</span>
                  <span class="pstep-text"><b>搜索与抓取</b><i>实时检索多个资讯源并抓取页面</i></span>
                </div>
                <div class="pstep" :class="{ active: pipeline.stage >= 2, done: pipeline.stage > 2 }">
                  <span class="pstep-no">2</span>
                  <span class="pstep-text"><b>内容筛选与提取</b><i>提取正文、剔除广告噪音、合并去重</i></span>
                </div>
                <div class="pstep" :class="{ active: pipeline.stage >= 3, done: pipeline.stage >= 3 && pipeline.done }">
                  <span class="pstep-no">3</span>
                  <span class="pstep-text"><b>多源整合与生成</b><i>按主题重新编排，生成结构化回答</i></span>
                </div>
              </div>
            </div>
            <div v-else-if="statusHint" class="msg-status">
              <span class="status-dot"></span>{{ statusHint }}
            </div>
            <div v-if="streamContent || reasoningContent" class="msg-bubble streaming" v-html="renderStreamBubble(streamContent)"></div>
            <div v-else-if="!statusHint && !pipeline" class="msg-bubble streaming"><span class="cursor">|</span></div>
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
            <!-- 文生图按钮 -->
            <button class="action-btn" title="文生图（AI 绘图）：请先在输入框填写图片描述" @click="sendImage"
                    :disabled="streaming">🎨</button>
            <!-- 联网搜索开关 -->
            <button class="action-btn" :class="{active: webSearch}" title="联网搜索：开启后优先调用联网检索获取实时/权威信息"
                    @click="$emit('update:webSearch', !webSearch)" :disabled="streaming">🌐</button>
            <!-- 深度思考开关(仅思考型模型生效): 关闭时跳过思考直接作答, 更快更省 -->
            <button class="action-btn" :class="{active: thinkingEnabled}" title="深度思考：开启后模型先思考再作答（仅思考型模型生效），关闭更快更省"
                    @click="$emit('update:thinkingEnabled', !thinkingEnabled)" :disabled="streaming">🧠</button>
            <!-- 图片上传按钮 -->
            <label class="action-btn" title="上传图片/视频">
              <input type="file" ref="fileInput" @change="handleFileSelect" accept="image/*,video/*" multiple hidden />
              📎
            </label>
            <!-- 知识库选择(RAG 检索增强): 仅 admin 可见, 普通用户通过 PLATFORM 级 RAG 自动受益 -->
            <el-select v-if="authStore.isAdmin" v-model="selectedKbIds" multiple collapse-tags collapse-tags-tooltip
                       :max-collapse-tags="1" placeholder="知识库" size="small"
                       class="kb-select" :class="{active: selectedKbIds.length > 0}"
                       :disabled="streaming" :loading="kbLoading"
                       title="选择知识库后, 对话会从知识库检索相关片段增强回答"
                       @visible-change="onKbDropdownOpen">
              <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
            </el-select>
            <!-- V2.3 Agent 选择: 登录用户可见, 选择后对话使用该 Agent 的角色/模型/工具配置 -->
            <el-select v-if="authStore.isLoggedIn" v-model="selectedAgentId" clearable
                       placeholder="Agent" size="small"
                       class="agent-select" :class="{active: !!selectedAgentId}"
                       :disabled="streaming" :loading="agentLoading"
                       title="选择自定义 Agent 后, 对话将使用其角色设定/模型/工具/知识库配置"
                       @visible-change="onAgentDropdownOpen">
              <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" />
            </el-select>
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

    <!-- 会话设置弹窗: 定制会话级 System Prompt(人设/约束), 覆盖全局与场景级默认 -->
    <el-dialog v-model="settingsVisible" title="会话设置 · 人设/约束定制" width="560px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="系统提示词(System Prompt)">
          <el-input v-model="settingsPrompt" type="textarea" :rows="6"
                    placeholder="定义模型在该会话中的角色与行为规则，例如：你是一位专业的Java代码审查专家，回答先给结论、用中文、不超过200字。留空保存可清除。" />
        </el-form-item>
      </el-form>
      <div class="settings-tip">提示：此处人设/约束优先级最高，仅对当前会话生效，不会影响其他会话。</div>
      <template #footer>
        <el-button @click="settingsVisible = false">取消</el-button>
        <el-button @click="clearSettings" v-if="settingsPrompt">清除</el-button>
        <el-button type="primary" :loading="settingsSaving" @click="saveSettings">保存</el-button>
      </template>
    </el-dialog>

    <!-- 图片预览弹窗 -->
    <ImagePreviewModal v-model:url="previewImageUrl" />
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import {
  getSessions, createSession, getMessages, deleteMessages,
  streamChat, streamChatImage, stopChat,
  renameSession, updateSystemPrompt, getSession, deleteSession, uploadFile,
  buildExportUrl, getKnowledgeBases, getAgents
} from '@/api'
import { ElMessageBox, ElMessage } from 'element-plus'
import logoUrl from '@/assets/dcdr-logo.png'
import MessageBubble from '@/components/MessageBubble.vue'
import ImagePreviewModal from '@/components/ImagePreviewModal.vue'
import { useMarkdown } from '@/composables/useMarkdown'
import { useAuthStore } from '@/stores/auth'

const { renderMarkdown, renderBubbleHtml } = useMarkdown()
// 流式正文实时渲染 Markdown(含引用角标), 与最终落库的 MessageBubble 完全一致
const renderStreamBubble = (text) => renderBubbleHtml(text, streamSources.value)
const vFocus = { mounted: (el) => el.focus() }
const props = defineProps({ currentModel: String, webSearch: { type: Boolean, default: false }, thinkingEnabled: { type: Boolean, default: true } })
const authStore = useAuthStore()

const sessions = ref([])
const currentSessionId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
// 方案 B: 流式输出归属会话隔离。streamSessionId 记录当前流式输出归属的会话。
// 由于同一时刻最多只有一个流式(send/sendImage 均有 streaming 互斥), 全局流式展示变量
// 即代表该唯一活跃流; 切换会话时仅通过 streamSessionId===currentSessionId 控制显示,
// 不中断流, 切回该会话时继续展示, onDone 时只把回答写入归属会话。
const streamSessionId = ref(null)
const interruptedRef = ref(false)
const streamContent = ref('')
const reasoningContent = ref('')
const streamImages = ref([])
const statusHint = ref('')
// 联网搜索三步流水线状态: null 表示非搜索; {stage:1|2|3, done:bool}
const pipeline = ref(null)
const streamSources = ref([])
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
const selectedFiles = ref([])
const previewImageUrl = ref(null)
const sidebarCollapsed = ref(false)

// 会话设置(会话级 System Prompt 定制)
const settingsVisible = ref(false)
const settingsSessionId = ref(null)
const settingsPrompt = ref('')
const settingsSaving = ref(false)

// 知识库选择(RAG 检索增强)
const knowledgeBases = ref([])
const selectedKbIds = ref([])
const kbLoading = ref(false)

async function loadKnowledgeBases() {
  kbLoading.value = true
  try {
    knowledgeBases.value = await getKnowledgeBases()
  } catch (e) {
    console.error('加载知识库列表失败', e)
  } finally {
    kbLoading.value = false
  }
}

// 下拉首次展开时懒加载知识库列表
function onKbDropdownOpen(open) {
  if (open && knowledgeBases.value.length === 0) loadKnowledgeBases()
}

// V2.3 自定义 Agent 选择
const agents = ref([])
const selectedAgentId = ref('')
const agentLoading = ref(false)

async function loadAgents() {
  agentLoading.value = true
  try {
    agents.value = await getAgents()
  } catch (e) {
    console.error('加载 Agent 列表失败', e)
  } finally {
    agentLoading.value = false
  }
}

function onAgentDropdownOpen(open) {
  if (open && agents.value.length === 0) loadAgents()
}

function isImage(file) {
  return file.file.type.startsWith('image/')
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

// ===== 会话设置: 定制会话级 System Prompt(人设/约束) =====
async function openSettings(s) {
  settingsSessionId.value = s.id
  settingsPrompt.value = ''
  try {
    const detail = await getSession(s.id)
    settingsPrompt.value = detail?.systemPrompt || ''
  } catch (e) {
    console.error('加载会话 System Prompt 失败', e)
  }
  settingsVisible.value = true
}

async function saveSettings() {
  if (!settingsSessionId.value) return
  settingsSaving.value = true
  try {
    await updateSystemPrompt(settingsSessionId.value, settingsPrompt.value.trim())
    ElMessage.success('已保存，后续对话按新的人设/约束生效')
    settingsVisible.value = false
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '保存失败'
    ElMessage.error(msg)
  } finally {
    settingsSaving.value = false
  }
}

async function clearSettings() {
  settingsPrompt.value = ''
  await saveSettings()
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
  streamSessionId.value = currentSessionId.value  // 记录该流归属会话(方案 B: 切走不显示、返回继续)
  streamContent.value = ''
  reasoningContent.value = ''
  streamImages.value = []
  streamSources.value = []
  statusHint.value = ''
  pipeline.value = null
  interruptedRef.value = false
  scrollBottom()

  await streamChat(
    {
      sessionId: currentSessionId.value,
      message: text,
      model: props.currentModel,
      images: (userMsg.images || []).map(i => i.url),
      webSearch: props.webSearch,
      thinkingEnabled: props.thinkingEnabled,
      knowledgeBaseIds: selectedKbIds.value.length > 0 ? selectedKbIds.value.slice() : undefined,
      agentId: selectedAgentId.value || undefined
    },
    (chunk) => {
      if (chunk.type === 'status') {
        if (pipeline.value) {
          // 三步流水线: STEP1 检索 → STEP2 提取整理 → STEP3 整合生成
          if (chunk.message.includes('抓取相关页面')) pipeline.value.stage = 1
          else if (chunk.message.includes('合并去重')) pipeline.value.stage = 2
        } else {
          statusHint.value = chunk.message || ''
        }
        scrollBottom(); return
      }
      if (chunk.type === 'error') { statusHint.value = ''; pipeline.value = null; streamContent.value += '\n[错误] ' + (chunk.message || ''); return }
      if (chunk.type === 'interrupted') { statusHint.value = ''; pipeline.value = null; interruptedRef.value = true; return }
      if (chunk.type === 'context_warning') { statusHint.value = ''; contextWarning.value = chunk.message || '对话已较长，建议开启新对话'; return }
      if (chunk.type === 'tool_call') {
        if (chunk.tool === 'image_generation') {
          statusHint.value = '正在调用工具生成图片…'
        } else if (chunk.tool === 'web_search') {
          pipeline.value = { stage: 0, done: false }
        } else {
          statusHint.value = `正在调用工具：${chunk.tool || ''}…`
        }
        scrollBottom(); return
      }
      if (chunk.type === 'tool_result') {
        statusHint.value = ''
        if (chunk.tool === 'web_search') {
          pipeline.value = pipeline.value || { stage: 0, done: false }
          pipeline.value.stage = 3
        }
        // 联网搜索等工具回传引用来源, 累计到本次会话
        // 注意: chunk.result 是喂给模型的检索上下文(含 [[n]] 标记的来源清单),
        // 不应拼进用户可见的正文 —— 来源已通过 sources 卡片单独渲染, 重复展示会一团乱
        if (chunk.sources && chunk.sources.length) {
          streamSources.value = streamSources.value.concat(chunk.sources)
          if (chunk.tool === 'web_search' && pipeline.value) pipeline.value.done = true
        }
        scrollBottom(); return
      }
      if (chunk.type === 'image') { statusHint.value = ''; streamImages.value.push(chunk.imageUrl); scrollBottom(); return }
      if (chunk.type === 'done') {
        statusHint.value = ''
        pipeline.value = null
        // done 块可能携带最终的引用来源
        if (chunk.sources && chunk.sources.length) streamSources.value = streamSources.value.concat(chunk.sources)
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
      // 方案 B: 仅当当前展示的会话就是该流归属会话时, 才把回答写入本地 messages;
      // 若期间已切到其他会话, 不污染当前列表(返回归属会话时靠 getMessages 拉取后端已落库的完整回答)
      const stillOnOwnerSession = streamSessionId.value === currentSessionId.value
      if (stillOnOwnerSession && (streamContent.value || reasoningContent.value || streamImages.value.length)) {
        messages.value.push({
          id: Date.now(),
          role: 'assistant',
          content: streamContent.value,
          reasoningContent: reasoningContent.value,
          images: streamImages.value.length ? streamImages.value.map(u => ({ url: u, name: '' })) : undefined,
          sources: streamSources.value.length ? streamSources.value.slice() : undefined,
          type: streamImages.value.length ? 'image' : 'text',
          interrupted: interruptedRef.value,
          tokenUsage: lastTokenUsage.value,
          durationMs: lastDurationMs.value
        })
      }
      streamContent.value = ''
      reasoningContent.value = ''
      streamImages.value = []
      statusHint.value = ''
      pipeline.value = null
      streamSessionId.value = null
      streaming.value = false
      loadSessions()
      scrollBottom()
      // 将后端 seq 回写到本地消息, 以支持编辑/重试时按 seq 截断历史(同样仅在归属会话当前展示时)
      if (stillOnOwnerSession && currentSessionId.value) {
        getMessages(currentSessionId.value)
          .then(list => {
            // 仅当数量一致时回写后端 seq(用于编辑/重试截断历史);
            // 数量不一致(如刚中断, 本地消息尚未持久化)时保留本地消息, 避免把正在输出的内容冲掉
            if (list && list.length === messages.value.length) {
              list.forEach((m, i) => { if (messages.value[i]) messages.value[i].seq = m.seq })
            }
          })
          .catch(() => {})
      }
    },
    (e) => { streaming.value = false; streamSessionId.value = null; streamContent.value = ''; reasoningContent.value = ''; statusHint.value = ''; ElMessage.error('对话请求失败：' + (e?.message || '网络异常')) }
  )
}

/** 文生图(按钮触发): 将输入框内容作为提示词, 调用 /api/chat/image 即时生成图片 */
async function sendImage() {
  const text = input.value.trim()
  if (!text) { ElMessage.warning('请先在输入框填写图片描述'); return }
  if (streaming.value) return
  contextWarning.value = null
  lastTokenUsage.value = null
  lastDurationMs.value = null
  if (!currentSessionId.value) await newSession()

  const userMsg = { id: Date.now(), role: 'user', content: text }
  messages.value.push(userMsg)
  input.value = ''
  selectedFiles.value = []

  streaming.value = true
  streamSessionId.value = currentSessionId.value  // 记录该流归属会话(方案 B)
  streamContent.value = ''
  reasoningContent.value = ''
  streamImages.value = []
  statusHint.value = '正在生成图片，请稍候…'
  interruptedRef.value = false
  scrollBottom()

  await streamChatImage(
    { sessionId: currentSessionId.value, prompt: text },
    (chunk) => {
      if (chunk.type === 'status') {
        if (pipeline.value) {
          if (chunk.message.includes('抓取相关页面')) pipeline.value.stage = 1
          else if (chunk.message.includes('合并去重')) pipeline.value.stage = 2
        } else {
          statusHint.value = chunk.message || ''
        }
        scrollBottom(); return
      }
      if (chunk.type === 'tool_call') {
        if (chunk.tool === 'image_generation') {
          statusHint.value = '正在生成图片…'
        } else if (chunk.tool === 'web_search') {
          pipeline.value = { stage: 0, done: false }
        } else {
          statusHint.value = `正在调用工具：${chunk.tool || ''}…`
        }
        scrollBottom(); return
      }
      if (chunk.type === 'tool_result') {
        statusHint.value = ''
        if (chunk.tool === 'web_search') {
          pipeline.value = pipeline.value || { stage: 0, done: false }
          pipeline.value.stage = 3
          if (chunk.sources && chunk.sources.length) pipeline.value.done = true
        }
        // 文生图场景下的 tool_result 同样不拼正文(来源已由 sources 卡片展示)
        scrollBottom(); return
      }
      if (chunk.type === 'error') { statusHint.value = ''; streamContent.value += '\n[错误] ' + (chunk.message || ''); return }
      if (chunk.type === 'interrupted') { statusHint.value = ''; interruptedRef.value = true; return }
      if (chunk.type === 'context_warning') { statusHint.value = ''; contextWarning.value = chunk.message || ''; return }
      if (chunk.type === 'image') { statusHint.value = ''; streamImages.value.push(chunk.imageUrl); scrollBottom(); return }
      if (chunk.type === 'done') {
        statusHint.value = ''
        lastTokenUsage.value = chunk.tokenUsage?.total ?? null
        lastDurationMs.value = chunk.durationMs ?? null
        return
      }
      if (chunk.type !== 'chunk') return
      statusHint.value = ''
      if (chunk.reasoningContent) { reasoningContent.value += chunk.reasoningContent; scrollBottom() }
      if (chunk.content) { streamContent.value += chunk.content; scrollBottom() }
    },
    () => {
      // 方案 B: 仅当当前展示会话是该流归属会话时才写入本地 messages
      const stillOnOwnerSession = streamSessionId.value === currentSessionId.value
      if (stillOnOwnerSession && (streamImages.value.length || streamContent.value || reasoningContent.value)) {
        messages.value.push({
          id: Date.now(),
          role: 'assistant',
          content: streamContent.value,
          reasoningContent: reasoningContent.value,
          images: streamImages.value.length ? streamImages.value.map(u => ({ url: u, name: '' })) : undefined,
          type: streamImages.value.length ? 'image' : 'text',
          interrupted: interruptedRef.value,
          tokenUsage: lastTokenUsage.value,
          durationMs: lastDurationMs.value
        })
      }
      streamContent.value = ''
      reasoningContent.value = ''
      streamImages.value = []
      statusHint.value = ''
      pipeline.value = null
      streamSessionId.value = null
      streaming.value = false
      loadSessions()
      scrollBottom()
      // 回写后端 seq, 支持编辑/重试时按 seq 截断历史(仅在归属会话当前展示时)
      if (stillOnOwnerSession && currentSessionId.value) {
        getMessages(currentSessionId.value)
          .then(list => {
            if (list && list.length === messages.value.length) {
              list.forEach((m, i) => { if (messages.value[i]) messages.value[i].seq = m.seq })
            }
          })
          .catch(() => {})
      }
    },
    (e) => { streaming.value = false; streamSessionId.value = null; streamContent.value = ''; reasoningContent.value = ''; streamImages.value = []; statusHint.value = ''; ElMessage.error('文生图请求失败：' + (e?.message || '网络异常')) }
  )
}

/** 中断当前会话(停止生成) */
function stop() {
  if (!streaming.value || !currentSessionId.value) return
  interruptedRef.value = true
  stopChat(currentSessionId.value)
}

/** 导出对话 (F-27): 直接触发浏览器下载, 后端返回 Content-Disposition: attachment */
function exportSession(s, format) {
  const url = buildExportUrl(s.id, format)
  // 用 a 标签触发下载(比 window.open 更可靠, 不会开新标签页)
  const a = document.createElement('a')
  a.href = url
  a.download = ''
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  ElMessage.success(`正在导出「${s.title}」为 ${format.toUpperCase()}`)
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
.rename-btn, .delete-btn, .export-btn, .settings-btn { border: none; background: transparent; cursor: pointer; font-size: 13px; color: #909399; opacity: 0; padding: 4px 6px; border-radius: 6px; flex-shrink: 0; transition: all 0.2s; }
.session-item:hover .rename-btn, .session-item:hover .delete-btn, .session-item:hover .export-btn, .session-item:hover .settings-btn { opacity: 1; }
.rename-btn:hover { color: #667eea; background: rgba(102,126,234,0.1); }
.settings-btn:hover { color: #e6a23c; background: rgba(230,162,60,0.1); }
.export-btn:hover { color: #67c23a; background: rgba(103,194,58,0.1); }
.delete-btn:hover { color: #f56c6c; background: rgba(245,108,108,0.1); }
.export-dropdown { flex-shrink: 0; }
.settings-tip { font-size: 12px; color: #909399; line-height: 1.5; margin-top: -8px; }
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

/* 流式输出项(复用消息项样式) */
.msg-item { display: flex; gap: 14px; margin-bottom: 28px; max-width: 900px; margin-left: auto; margin-right: auto; width: 100%; animation: fadeInUp 0.3s ease; }
@keyframes fadeInUp { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
.msg-avatar { width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 13px; font-weight: 600; flex-shrink: 0; overflow: hidden; box-shadow: 0 3px 10px rgba(0,0,0,0.1); }
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.msg-content { flex: 1; min-width: 0; }
.msg-role { font-size: 13px; color: #909399; margin-bottom: 6px; font-weight: 500; }
.msg-images { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.msg-image-wrapper { position: relative; }
.msg-image { max-width: 280px; max-height: 240px; border-radius: 12px; object-fit: cover; cursor: pointer; transition: all 0.25s ease; box-shadow: 0 4px 12px rgba(0,0,0,0.1); border: 1px solid #e8ecf4; }
.msg-image:hover { transform: scale(1.02); box-shadow: 0 6px 20px rgba(0,0,0,0.15); }
.msg-reasoning { margin-bottom: 8px; }
.msg-reasoning details { background: linear-gradient(135deg, #f8f9fc 0%, #f0f2f7 100%); border: 1px solid #e4e7ed; border-radius: 10px; padding: 10px 14px; }
.msg-reasoning summary { cursor: pointer; color: #606266; font-size: 12px; font-weight: 500; user-select: none; outline: none; padding: 4px 0; }
.msg-reasoning details > div { color: #606266; font-size: 13px; line-height: 1.7; margin-top: 10px; }
.msg-reasoning details > div :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 8px; overflow-x: auto; }
.msg-reasoning details > div :deep(code) { background: #fff; padding: 2px 8px; border-radius: 4px; }
.msg-bubble { padding: 14px 18px; border-radius: 14px; background: #fff; border: 1px solid #e8ecf4; font-size: 14px; line-height: 1.8; box-shadow: 0 2px 8px rgba(0,0,0,0.04); word-break: break-word; }
.msg-bubble.streaming { border-color: #667eea33; }
.msg-bubble :deep(pre) { background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); color: #e0e0e0; padding: 16px; border-radius: 10px; overflow-x: auto; margin: 10px 0; }
.msg-bubble :deep(code) { background: #f5f7fa; padding: 3px 8px; border-radius: 5px; font-size: 13px; color: #e83e8c; }
.msg-bubble :deep(p) { margin: 8px 0; }
.msg-bubble :deep(img) { max-width: 100%; border-radius: 8px; margin: 8px 0; }
.msg-status { display: inline-flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 12px; background: #667eea11; color: #667eea; font-size: 14px; }
.msg-status .status-dot { width: 8px; height: 8px; border-radius: 50%; background: #667eea; animation: statusPulse 1s ease-in-out infinite; }
@keyframes statusPulse { 0%, 100% { opacity: 0.3; transform: scale(0.8); } 50% { opacity: 1; transform: scale(1.2); } }
/* 联网搜索三步流水线面板 */
.search-pipeline { background: linear-gradient(135deg, #f5f7ff 0%, #eef1fb 100%); border: 1px solid #d9e0f7; border-radius: 14px; padding: 14px 16px; margin-bottom: 4px; }
.pipeline-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.pipeline-icon { font-size: 16px; }
.pipeline-title { font-weight: 700; color: #2d3a8c; font-size: 14px; }
.pipeline-done { margin-left: auto; font-size: 11px; color: #2e9e6b; background: #2e9e6b1a; padding: 2px 10px; border-radius: 20px; font-weight: 600; }
.pipeline-steps { display: flex; flex-direction: column; gap: 10px; }
.pstep { display: flex; gap: 12px; align-items: flex-start; opacity: 0.45; transition: all 0.3s ease; }
.pstep.active, .pstep.done { opacity: 1; }
.pstep-no { flex-shrink: 0; width: 26px; height: 26px; border-radius: 50%; background: #c7d2fe; color: #fff; font-size: 13px; font-weight: 700; display: flex; align-items: center; justify-content: center; transition: all 0.3s; }
.pstep.active .pstep-no { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); box-shadow: 0 0 0 4px #667eea22; }
.pstep.done .pstep-no { background: linear-gradient(135deg, #34d399 0%, #059669 100%); }
.pstep-text { display: flex; flex-direction: column; line-height: 1.35; }
.pstep-text b { font-size: 13.5px; color: #303133; }
.pstep-text i { font-size: 11.5px; color: #8a90a3; font-style: normal; }
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
.action-btn.active { background: linear-gradient(135deg, rgba(102,126,234,0.18) 0%, rgba(118,75,162,0.15) 100%); color: #667eea; box-shadow: inset 0 0 0 1px rgba(102,126,234,0.35); }
/* 知识库多选下拉: 与 action-btn 同高, 选中时高亮 */
.kb-select { width: 130px; flex-shrink: 0; }
.kb-select :deep(.el-select__wrapper) { min-height: 36px; border-radius: 10px; background: #f5f7fa; box-shadow: none; border: 1px solid transparent; transition: all 0.2s; }
.kb-select :deep(.el-select__wrapper:hover) { background: #667eea15; }
.kb-select.active :deep(.el-select__wrapper) { background: linear-gradient(135deg, rgba(102,126,234,0.18) 0%, rgba(118,75,162,0.15) 100%); box-shadow: inset 0 0 0 1px rgba(102,126,234,0.35); }
.kb-select :deep(.el-select__placeholder) { font-size: 13px; color: #606266; }
.kb-select :deep(.el-select__tags-text) { font-size: 12px; }
/* V2.3 Agent 选择下拉: 复用 kb-select 样式 */
.agent-select { width: 120px; flex-shrink: 0; }
.agent-select :deep(.el-select__wrapper) { min-height: 36px; border-radius: 10px; background: #f5f7fa; box-shadow: none; border: 1px solid transparent; transition: all 0.2s; }
.agent-select :deep(.el-select__wrapper:hover) { background: #667eea15; }
.agent-select.active :deep(.el-select__wrapper) { background: linear-gradient(135deg, rgba(102,126,234,0.18) 0%, rgba(118,75,162,0.15) 100%); box-shadow: inset 0 0 0 1px rgba(102,126,234,0.35); }
.agent-select :deep(.el-select__placeholder) { font-size: 13px; color: #606266; }
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
</style>
