<template>
  <div class="chat-mode">
    <aside class="sidebar">
      <div class="sidebar-header">
        <button class="new-btn gradient-bg" @click="newSession">+ 新建对话</button>
      </div>
      <div class="session-list">
        <div v-for="s in sessions" :key="s.id" class="session-item" :class="{active: s.id===currentSessionId}" @click="switchSession(s.id)">
          <template v-if="editingId === s.id">
            <input class="rename-input" v-model="editText" v-focus @click.stop
                   @keyup.enter="saveRename(s.id)" @keyup.esc="cancelRename" @blur="saveRename(s.id)" />
          </template>
          <template v-else>
            <span class="session-title">{{ s.title }}</span>
            <button class="rename-btn" @click.stop="startRename(s)" title="重命名">✎</button>
            <button class="delete-btn" @click.stop="removeSession(s)" title="删除">🗑</button>
          </template>
        </div>
      </div>
    </aside>
    <div class="chat-area">
      <div v-if="contextWarning" class="context-warning">
        <span class="cw-icon">⚠</span>
        <span class="cw-text">{{ contextWarning }}</span>
        <button class="cw-new" @click="newSession">开启新对话</button>
        <button class="cw-close" @click="contextWarning = null" title="忽略">✕</button>
      </div>
      <div class="messages" ref="msgBox">
        <div v-if="messages.length === 0" class="welcome">
          <img :src="logoUrl" class="welcome-icon" alt="DCDR" />
          <h2>你好，我是 DCDR</h2>
          <p>对话模式 - 支持文字对话、图片理解、工具调用</p>
        </div>
        <div v-for="msg in messages" :key="msg.id" class="msg-item">
          <div class="msg-avatar" :class="{ 'gradient-bg': msg.role === 'user' }">
            <img v-if="msg.role !== 'user'" :src="logoUrl" class="avatar-img" alt="DCDR" />
            <template v-else>U</template>
          </div>
          <div class="msg-content">
            <div class="msg-role">{{ msg.role === 'user' ? '用户' : 'Agent' }}</div>
            <div v-if="msg.reasoningContent" class="msg-reasoning">
              <details>
                <summary>推理过程</summary>
                <div v-html="renderMarkdown(msg.reasoningContent)"></div>
              </details>
            </div>
            <div class="msg-bubble" v-html="renderMarkdown(msg.content)"></div>
            <div v-if="msg.role === 'assistant' && (msg.tokenUsage != null || msg.durationMs != null)" class="msg-meta">
              <span v-if="msg.durationMs != null">⏱ {{ (msg.durationMs / 1000).toFixed(1) }}s</span>
              <span v-if="msg.tokenUsage != null">🪙 {{ msg.tokenUsage }} token</span>
            </div>
          </div>
        </div>
        <div v-if="streaming" class="msg-item">
          <div class="msg-avatar"><img :src="logoUrl" class="avatar-img" alt="DCDR" /></div>
          <div class="msg-content">
            <div class="msg-role">Agent</div>
            <div v-if="reasoningContent" class="msg-reasoning">
              <details open>
                <summary>推理过程</summary>
                <div v-html="renderMarkdown(reasoningContent)"></div>
              </details>
            </div>
            <div class="msg-bubble">{{ streamContent }}<span class="cursor">|</span></div>
          </div>
        </div>
      </div>
      <div class="input-area">
        <div class="input-box">
          <textarea v-model="input" placeholder="输入消息, Enter发送? Shift+Enter换行" @keydown.enter.exact.prevent="send" rows="1"></textarea>
          <button class="send-btn gradient-bg" @click="streaming ? stop() : send()" :disabled="!input.trim() && !streaming">
            {{ streaming ? '停止' : '发送' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { getSessions, createSession, getMessages, streamChat, stopChat, renameSession, deleteSession } from '../api'
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
const msgBox = ref(null)
const editingId = ref(null)
const editText = ref('')
const contextWarning = ref(null)
const lastTokenUsage = ref(null)
const lastDurationMs = ref(null)
const renderMarkdown = (text) => md.render(text || '')
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
function scrollBottom() { nextTick(() => { if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight }) }
async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  contextWarning.value = null
  lastTokenUsage.value = null
  lastDurationMs.value = null
  if (!currentSessionId.value) await newSession()
  messages.value.push({ id: Date.now(), role: 'user', content: text })
  input.value = ''
  streaming.value = true
  streamContent.value = ''
  reasoningContent.value = ''
  let interrupted = false
  scrollBottom()
  await streamChat(
    { sessionId: currentSessionId.value, message: text, model: props.currentModel },
    (chunk) => {
      if (chunk.type === 'error') { streamContent.value += '\n[错误] ' + (chunk.message || ''); return }
      if (chunk.type === 'interrupted') { interrupted = true; return }
      if (chunk.type === 'context_warning') { contextWarning.value = chunk.message || '对话已较长，建议开启新对话'; return }
      if (chunk.type === 'done') {
        lastTokenUsage.value = chunk.tokenUsage?.total ?? null
        lastDurationMs.value = chunk.durationMs ?? null
        return
      }
      if (chunk.type !== 'chunk') return
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
      streaming.value = false
      loadSessions()
      scrollBottom()
    },
    (e) => { streaming.value = false; streamContent.value = ''; reasoningContent.value = '' }
  )
}

/** 中断当前会话(停止生成) */
function stop() {
  if (!streaming.value || !currentSessionId.value) return
  stopChat(currentSessionId.value)
}
onMounted(() => { loadSessions() })
</script>

<style scoped>
.chat-mode { flex: 1; display: flex; overflow: hidden; }
.sidebar { width: 260px; background: #fff; border-right: 1px solid #e8e8e8; display: flex; flex-direction: column; }
.sidebar-header { padding: 16px; border-bottom: 1px solid #f0f0f0; }
.new-btn { width: 100%; height: 40px; color: #fff; border: none; border-radius: 8px; font-size: 14px; cursor: pointer; }
.session-list { flex: 1; overflow-y: auto; padding: 8px; }
.session-item { padding: 10px 12px; border-radius: 8px; cursor: pointer; margin-bottom: 4px; display: flex; align-items: center; gap: 8px; }
.session-item:hover { background: #f5f7fa; }
.session-item.active { background: #f0f4ff; color: #667eea; font-weight: 500; }
.session-title { font-size: 13px; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rename-btn { border: none; background: transparent; cursor: pointer; font-size: 13px; color: #909399; opacity: 0; padding: 2px 4px; border-radius: 4px; flex-shrink: 0; }
.session-item:hover .rename-btn { opacity: 1; }
.rename-btn:hover { color: #667eea; background: #e8edff; }
.delete-btn { border: none; background: transparent; cursor: pointer; font-size: 13px; color: #909399; opacity: 0; padding: 2px 4px; border-radius: 4px; flex-shrink: 0; }
.session-item:hover .delete-btn { opacity: 1; }
.delete-btn:hover { color: #f56c6c; background: #fde2e2; }
.rename-input { flex: 1; font-size: 13px; border: 1px solid #667eea; border-radius: 6px; padding: 4px 6px; outline: none; min-width: 0; }
.chat-area { flex: 1; display: flex; flex-direction: column; background: #f7f8fa; }
.messages { flex: 1; overflow-y: auto; padding: 24px; }
.welcome { text-align: center; padding-top: 120px; }
.welcome-icon { width: 72px; height: 72px; border-radius: 16px; object-fit: cover; margin-bottom: 16px; }
.msg-item { display: flex; gap: 12px; margin-bottom: 24px; max-width: 820px; margin-left: auto; margin-right: auto; }
.msg-avatar { width: 36px; height: 36px; border-radius: 8px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 12px; flex-shrink: 0; overflow: hidden; }
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.msg-content { flex: 1; min-width: 0; }
.msg-role { font-size: 12px; color: #909399; margin-bottom: 4px; }
.msg-reasoning { margin-bottom: 6px; }
.msg-reasoning details { background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 8px; padding: 8px 12px; }
.msg-reasoning summary { cursor: pointer; color: #909399; font-size: 12px; user-select: none; outline: none; }
.msg-reasoning details > div { color: #909399; font-size: 13px; line-height: 1.6; margin-top: 8px; }
.msg-reasoning details > div :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 10px; border-radius: 6px; overflow-x: auto; }
.msg-reasoning details > div :deep(code) { background: #fff; padding: 2px 6px; border-radius: 4px; }
.msg-bubble { padding: 12px 16px; border-radius: 8px; background: #fff; border: 1px solid #e8e8e8; font-size: 14px; line-height: 1.7; }
.msg-bubble :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 14px; border-radius: 8px; overflow-x: auto; }
.msg-bubble :deep(code) { background: #f5f7fa; padding: 2px 6px; border-radius: 4px; }
.msg-meta { margin-top: 6px; display: flex; gap: 12px; font-size: 12px; color: #909399; }
.msg-meta span { background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 6px; padding: 2px 8px; }
.cursor { animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0; } }
.input-area { padding: 16px 24px 20px; }
.context-warning { max-width: 820px; margin: 12px auto 0; display: flex; align-items: center; gap: 10px; padding: 10px 14px; background: #fff7e6; border: 1px solid #ffd591; border-radius: 10px; font-size: 13px; color: #ad6800; }
.context-warning .cw-icon { font-size: 15px; }
.context-warning .cw-text { flex: 1; line-height: 1.5; }
.context-warning .cw-new { border: none; background: #fa8c16; color: #fff; padding: 5px 12px; border-radius: 6px; cursor: pointer; font-size: 13px; flex-shrink: 0; }
.context-warning .cw-new:hover { background: #d46b08; }
.context-warning .cw-close { border: none; background: transparent; color: #ad6800; cursor: pointer; font-size: 14px; padding: 2px 6px; border-radius: 4px; flex-shrink: 0; }
.context-warning .cw-close:hover { background: #ffe7ba; }
.input-box { max-width: 820px; margin: 0 auto; background: #fff; border: 1px solid #dcdfe6; border-radius: 12px; padding: 12px 16px; display: flex; gap: 12px; align-items: flex-end; }
.input-box textarea { flex: 1; border: none; outline: none; resize: none; font-size: 14px; line-height: 1.6; max-height: 160px; font-family: inherit; }
.send-btn { width: 36px; height: 36px; border: none; border-radius: 8px; color: #fff; cursor: pointer; font-size: 12px; }
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
