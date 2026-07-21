import { ref, onMounted, nextTick } from 'vue'
import { getSessions, createSession, getMessages, streamChat } from '../api'
import MarkdownIt from 'markdown-it'
const md = new MarkdownIt()
const props = defineProps({ currentModel: String })
const sessions = ref([])
const currentSessionId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const streamContent = ref('')
const streamReasoning = ref('')
const msgBox = ref(null)
const renderMarkdown = (text) => md.render(text || '')
async function loadSessions() { sessions.value = await getSessions() }
async function newSession() {
  const s = await createSession({ title: '\u65b0\u5bf9\u8bdd', model: props.currentModel })
  sessions.value.unshift(s)
  currentSessionId.value = s.id
  messages.value = []
}
async function switchSession(id) {
  currentSessionId.value = id
  messages.value = await getMessages(id)
  scrollBottom()
}
function scrollBottom() { nextTick(() => { if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight }) }
async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  if (!currentSessionId.value) await newSession()
  messages.value.push({ id: Date.now(), role: 'user', content: text })
  input.value = ''
  streaming.value = true
  streamContent.value = ''
  streamReasoning.value = ''
  scrollBottom()
  await streamChat(
    { sessionId: currentSessionId.value, message: text, model: props.currentModel },
    (chunk) => {
      if (chunk.type === 'chunk') {
        if (chunk.reasoningContent) streamReasoning.value += chunk.reasoningContent
        if (chunk.content) streamContent.value += chunk.content
        scrollBottom()
      }
      else if (chunk.type === 'error') { streamContent.value += '\n[\u9519\u8bef] ' + chunk.message }
    },
    () => {
      if (streamContent.value) {
        messages.value.push({ id: Date.now(), role: 'assistant', content: streamContent.value })
      }
      streamContent.value = ''
      streamReasoning.value = ''
      streaming.value = false
      loadSessions()
      scrollBottom()
    },
    (e) => { streaming.value = false; streamContent.value = '' }
  )
}
onMounted(() => { loadSessions() })
