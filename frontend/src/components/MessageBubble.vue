<template>
  <div v-if="!isHidden" class="msg-item" :class="'msg-' + msg.role">
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
               @click="$emit('preview-image', img.url || img)" loading="lazy" />
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
          <summary>推理过程</summary>
          <div v-html="renderReasoning(msg.reasoningContent)" @click="onBubbleClick"></div>
        </details>
      </div>

      <!-- 编辑态 -->
      <div v-if="editing" class="edit-box">
        <textarea :value="editBuffer"
                  @input="$emit('update:editBuffer', $event.target.value)"
                  class="edit-textarea" placeholder="编辑消息内容..."
                  @keydown.ctrl.enter="$emit('save-edit', msg)" @keydown.meta.enter="$emit('save-edit', msg)"></textarea>
        <div class="msg-actions">
          <button class="btn btn-small btn-primary" @click="$emit('save-edit', msg)">保存并重新生成</button>
          <button class="btn btn-small" @click="$emit('cancel-edit')">取消</button>
        </div>
      </div>
      <template v-else>
        <!-- 引用来源汇总行(正文上方): 联网搜索显示网页 favicon, 知识库片段显示📚 -->
        <div v-if="hasSources && msg.content" class="search-summary">
          <svg v-if="allKbSources" class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M5 5.5C5 4.4 5.9 3.6 7 3.6h11v14.6H7c-1.1 0-2 .8-2 1.9V5.5z"/><path d="M18 18.2v3.2H7"/></svg>
          <svg v-else class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="11" cy="11" r="6.6"/><path d="M20 20l-3.6-3.6"/></svg>
          <span class="search-text">{{ allKbSources ? `已参考 ${msg.sources.length} 个知识库片段` : `已阅读 ${msg.sources.length } 个网页` }}</span>
          <div v-if="!allKbSources" class="favicon-stack">
            <img v-for="(f, i) in topFavicons" :key="i" :src="f" class="favicon-img"
                 :style="{ zIndex: topFavicons.length - i }" loading="lazy"
                 @error="onFaviconError" />
          </div>
        </div>

        <div v-if="msg.content" class="msg-bubble" v-html="renderBubble(msg.content)" @click="onBubbleClick"></div>

        <!-- 正文下方精简 favicon 汇总(二次强化"有来源"的感知) -->
        <div v-if="hasSources && msg.content" class="search-summary-bottom">
          <div v-if="!allKbSources" class="favicon-stack">
            <img v-for="(f, i) in topFavicons" :key="i" :src="f" class="favicon-img"
                 :style="{ zIndex: topFavicons.length - i }" loading="lazy"
                 @error="onFaviconError" />
          </div>
          <span class="summary-count">{{ allKbSources ? `${msg.sources.length} 个知识库片段` : `${msg.sources.length} 个网页` }}</span>
          <button class="sources-toggle-mini" @click="sourcesOpen = !sourcesOpen">
            {{ sourcesOpen ? '收起来源' : '查看来源' }}
          </button>
        </div>

        <!-- 引用来源卡片(可展开) -->
        <div v-if="hasSources" v-show="sourcesOpen" class="sources-list">
          <article v-for="(s, idx) in msg.sources" :key="idx"
                   class="source-card" :class="{ highlight: activeCite && (s.index || (idx + 1)) === activeCite }"
                   :ref="el => registerSourceCard(el, s.index || (idx + 1))">
            <div class="source-card-head">
              <span class="source-index">{{ s.index || (idx + 1) }}</span>
              <img v-if="faviconOf(s.url)" :src="faviconOf(s.url)" class="source-favicon" loading="lazy" @error="onFaviconError" />
              <a v-if="s.url" class="source-title" :href="s.url" target="_blank" rel="noopener noreferrer"
                 v-html="renderInline(s.title || s.url)"></a>
              <span v-else class="source-title source-title-plain" v-html="renderInline(s.title || '知识库片段')"></span>
              <span class="source-host">{{ hostOf(s.url) }}</span>
            </div>
            <div class="source-snippet" v-html="sourceBodyHtml(s, idx)"></div>
            <button v-if="hasLongBody(s, idx)" class="source-toggle"
                    @click="toggleSource(idx)">{{ expandedSources.has(idx) ? '收起 ▲' : '展开全文 ▼' }}</button>
          </article>
        </div>

        <!-- 工具调用过程(折叠): 本轮模型调用的工具清单 -->
        <div v-if="toolList.length" class="tool-invoke">
          <button class="tool-invoke-toggle" @click="toolOpen = !toolOpen">
            <svg class="tool-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 6.2a4 4 0 0 1 5.3 5.1l-8.6 8.6a2.6 2.6 0 0 1-3.7-3.7l8.6-8.6"/><path d="M4.4 4.4l3.2 3.2"/></svg>
            <span>已调用 {{ toolList.length }} 个工具</span>
            <span class="tool-caret">{{ toolOpen ? '收起' : '展开' }}</span>
          </button>
          <div v-if="toolOpen" class="tool-invoke-list">
            <div v-for="(t, i) in toolList" :key="i" class="tool-item">
              <div class="tool-item-head">
                <span class="tool-badge">{{ t.name || 'tool' }}</span>
                <span v-if="t.id" class="tool-id">{{ t.id }}</span>
              </div>
              <pre v-if="t.args" class="tool-args">{{ t.args }}</pre>
            </div>
          </div>
        </div>

        <div v-if="msg.interrupted" class="msg-interrupted">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4.5l8.2 14.5H3.8L12 4.5z"/><path d="M12 10v4M12 17h.01"/></svg>
          已中断，以下为已生成的内容
        </div>

        <!-- 元信息 -->
        <div v-if="msg.role === 'assistant' && (msg.tokenUsage != null || msg.durationMs != null)" class="msg-meta">
          <span v-if="msg.durationMs != null">{{ (msg.durationMs / 1000).toFixed(1) }}s</span>
          <span v-if="msg.tokenUsage != null">{{ msg.tokenUsage }} token</span>
        </div>

        <!-- 操作: 编辑历史消息 / 重新生成助手回答 -->
        <div v-if="!streaming" class="msg-actions">
          <button v-if="msg.role === 'user'" class="btn btn-small" @click="$emit('start-edit', msg)" title="编辑并重新生成">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20h4l9.5-9.5a2.1 2.1 0 0 0-3-3L5 17v3z"/></svg>
            编辑
          </button>
          <button v-if="msg.role === 'assistant'" class="btn btn-small" @click="$emit('retry', msg)" title="重新生成此回答">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M19.8 4v4.2h-4.2"/></svg>
            重新生成
          </button>
        </div>

        <!-- V3.1 F-48 回答反馈(收集): 点赞 / 点踩; 点踩可多选原因 -->
        <div v-if="canFeedback" class="msg-feedback">
          <button class="fb-btn" :class="{ active: feedback === 'up' }" :disabled="fbSubmitting"
                  @click="quickUp" :title="feedback === 'up' ? '已点赞，点击取消' : '这个回答有帮助'">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M7 10.5v9H4.4c-.8 0-1.4-.6-1.4-1.4v-6.2c0-.8.6-1.4 1.4-1.4H7z"/><path d="M7 10.5l4-6.3c1.3.2 2 1 2 2.3v2.6h5.1c1.3 0 2.3 1.2 2 2.5l-1.3 6c-.2.9-1 1.6-2 1.6H7"/></svg>
          </button>
          <button class="fb-btn" :class="{ active: feedback === 'down' }" :disabled="fbSubmitting"
                  @click="openDownPicker" :title="feedback === 'down' ? '已点踩，点击修改' : '这个回答没帮助'">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M17 13.5v-9h2.6c.8 0 1.4.6 1.4 1.4v6.2c0 .8-.6 1.4-1.4 1.4H17z"/><path d="M17 13.5l-4 6.3c-1.3-.2-2-1-2-2.3v-2.6H5.9c-1.3 0-2.3-1.2-2-2.5l1.3-6c.2-.9 1-1.6 2-1.6H17"/></svg>
          </button>
          <span v-if="feedback" class="fb-state">
            {{ feedback === 'up' ? '已点赞' : '已点踩' }}
            <button class="fb-undo" @click="withdraw" :disabled="fbSubmitting">撤销</button>
          </span>
        </div>

        <!-- 点踩原因面板: 采集"为什么不好"的结构化标签, 这是反馈能被归因的前提 -->
        <div v-if="pickerOpen" class="fb-picker">
          <div class="fb-picker-head">
            <span class="fb-picker-title">{{ feedback === 'down' ? '修改反馈内容' : '哪里不满意？' }}</span>
            <span class="fb-picker-tip">可多选（最多 5 个）</span>
          </div>
          <div class="fb-tags">
            <button v-for="t in reasonTags" :key="t.code" class="fb-tag"
                    :class="{ on: picked.includes(t.code) }" @click="toggleTag(t.code)">{{ t.label }}</button>
          </div>
          <textarea v-model="fbComment" class="fb-comment" rows="2" maxlength="1000"
                    placeholder="补充说明（可选）：例如回答缺少出处、步骤跳太快、格式不便阅读…"></textarea>
          <div class="fb-picker-actions">
            <button class="btn btn-small" @click="closePicker" :disabled="fbSubmitting">取消</button>
            <button class="btn btn-small btn-primary" @click="submitDown" :disabled="fbSubmitting">提交</button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onBeforeUpdate } from 'vue'
import logoUrl from '@/assets/dcdr-logo.png'
import { useMarkdown } from '@/composables/useMarkdown'
import { useCodeBlock } from '@/composables/useCodeBlock'
import { submitFeedback, withdrawFeedback, getFeedbackReasonTags, extractMessage } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({
  msg: { type: Object, required: true },
  streaming: { type: Boolean, default: false },
  editing: { type: Boolean, default: false },
  editBuffer: { type: String, default: '' }
})
const emit = defineEmits(['update:editBuffer', 'start-edit', 'save-edit', 'cancel-edit', 'retry', 'preview-image', 'preview-code', 'cite-click', 'feedback'])

const { renderMarkdown, renderBubbleHtml } = useMarkdown()
// 推理过程同样允许复制代码块(与正文共用同一套按钮与处理逻辑)
const renderReasoning = (text) => renderMarkdown(text, { codeActions: true })
// 代码块按钮实现「复制 / 运行预览」: 预览交给父级统一弹窗
const { handleCodeClick } = useCodeBlock((payload) => emit('preview-code', payload))

const hasSources = computed(() => props.msg?.sources && props.msg.sources.length > 0)
// 是否全部为知识库片段来源(RAG): 决定汇总行文案与是否显示网页 favicon
const allKbSources = computed(() => hasSources.value
  && props.msg.sources.every(s => s.source === 'knowledge_base'))
// 对用户隐藏的工具往返中间消息: role=tool, 或 role=assistant 仅有 toolCalls 无正文的空壳
// (后端 getMessages 已过滤, 此处为兜底, 避免前端本地流式态出现空气泡)
const isHidden = computed(() => {
  const m = props.msg
  if (!m) return true
  if (m.role === 'tool') return true
  if (m.role === 'assistant'
      && m.toolCalls && m.toolCalls.length
      && !m.content && !m.reasoningContent) {
    return true
  }
  return false
})
// 工具调用清单(本轮模型调用的工具), 折叠展示在回答下方
const toolOpen = ref(false)
const toolList = computed(() => {
  const inv = props.msg?.toolInvocations
  if (!inv || !inv.length) return []
  return inv.map(t => {
    const raw = t.arguments != null ? String(t.arguments) : ''
    const args = raw.length > 800 ? raw.slice(0, 800) + '\n…(参数过长已截断)' : raw
    return { name: t.name, id: t.id, args }
  })
})
// 默认收起来源卡片(正文上下两处汇总已足够展示来源存在; 用户点"查看来源"或角标时展开)
const sourcesOpen = ref(false)
const expandedSources = ref(new Set())
const activeCite = ref(0)
const sourceCards = new Map()
function registerSourceCard(el, index) {
  if (el) sourceCards.set(index, el)
  else sourceCards.delete(index)
}
onBeforeUpdate(() => sourceCards.clear())

// === favicon 工具 ===
// 用 Google s2 favicon 服务按域名取图标(国内可访问, 比 deepseek 自建 cdn 更通用)
function faviconOf(url) {
  const host = hostOf(url)
  if (!host) return ''
  return `https://www.google.com/s2/favicons?domain=${encodeURIComponent(host)}&sz=32`
}
// 取 URL 的 host, 失败返回空串
function hostOf(url) {
  if (!url) return ''
  try {
    const u = url.startsWith('http') ? url : 'https://' + url
    return new URL(u).host || ''
  } catch (e) {
    return ''
  }
}
// 正文上下汇总行用: 前 4 个不重复域名的 favicon
const topFavicons = computed(() => {
  if (!hasSources.value) return []
  const seen = new Set()
  const out = []
  for (const s of props.msg.sources) {
    const h = hostOf(s.url)
    if (h && !seen.has(h)) {
      seen.add(h)
      out.push(faviconOf(s.url))
    }
    if (out.length >= 4) break
  }
  return out
})
function onFaviconError(e) {
  // favicon 加载失败: 隐藏 img, 避免显示裂图
  if (e.target) e.target.style.display = 'none'
}

// 把模型正文里的 [[n]] 与行内 [n] 引用标记渲染成圆形数字角标(deepseek 风格)
// 复用 useMarkdown.renderBubbleHtml, 与流式输出保持完全一致
function renderBubble(text) {
  return renderBubbleHtml(text, props.msg?.sources)
}

function renderInline(text) {
  if (!text) return ''
  return renderMarkdown(text).replace(/^<p>/, '').replace(/<\/p>$/, '')
}

const BODY_LIMIT = 1200
function sourceBodyHtml(s, idx) {
  const raw = (s.content && String(s.content).trim())
      ? String(s.content)
      : (s.snippet || '')
  if (!raw.trim()) return '<p class="source-empty">（该来源未能提取到正文，仅作链接参考）</p>'
  const collapsed = idx >= 3 && !expandedSources.value.has(idx) && raw.length > BODY_LIMIT
  const shown = collapsed ? raw.slice(0, BODY_LIMIT) + '…' : raw
  return renderMarkdown(shown)
}
function hasLongBody(s, idx) {
  const raw = (s.content && String(s.content).trim()) ? String(s.content) : (s.snippet || '')
  return idx >= 3 && raw.length > BODY_LIMIT
}
function toggleSource(idx) {
  const next = new Set(expandedSources.value)
  if (next.has(idx)) next.delete(idx)
  else next.add(idx)
  expandedSources.value = next
}

// 点击正文内角标: 角标本身是 <a href> 已能跳转, 这里额外展开来源面板并高亮(便于用户回看)
let citeTimer = null
function onBubbleClick(e) {
  // 代码块上的「复制 / 运行预览」按钮优先处理(与角标互斥)
  if (e.target instanceof Element && e.target.closest('.code-copy, .code-preview')) {
    handleCodeClick(e)
    return
  }
  const el = e.target.closest('.cite')
  if (!el) return
  const idx = Number(el.dataset.cite)
  if (!sourcesOpen.value) sourcesOpen.value = true
  activeCite.value = idx
  const card = sourceCards.get(idx)
  if (card) {
    setTimeout(() => card.scrollIntoView({ behavior: 'smooth', block: 'center' }), 100)
  }
  clearTimeout(citeTimer)
  citeTimer = setTimeout(() => { activeCite.value = 0 }, 2000)
  emit('cite-click', idx)
}

// ===== V3.1 F-48 回答反馈(点赞/点踩收集) =====
// 反馈以 (sessionId, seq) 定位回答: seq 是后端 messages 表的会话内序号, 刷新后依然稳定。
// 因此流式刚结束时 (seq 尚未回写) 不展示反馈按钮, 避免提交到错误的回答上。
// 注: 不跟随 streaming 隐藏 —— 评价的是已完成的回答, 与正在生成的回答无关
const canFeedback = computed(() => props.msg?.role === 'assistant'
  && props.msg?.seq != null
  && !props.msg?.error)
// 反馈状态以父级传入的 msg.feedback 为准 (父级在提交成功后回写), 保证与后端一致
const feedback = computed(() => props.msg?.feedback || null)

const fbSubmitting = ref(false)
const pickerOpen = ref(false)
const picked = ref([])
const fbComment = ref('')
const reasonTags = ref([])

// 后端不可用时的兜底标签; 正常情况下以后端 /api/feedback/reason-tags 为准
const FALLBACK_TAGS = [
  { code: 'INACCURATE', label: '内容不准确' },
  { code: 'IRRELEVANT', label: '答非所问' },
  { code: 'INCOMPLETE', label: '回答不完整' },
  { code: 'OUTDATED', label: '信息过时' },
  { code: 'FORMAT', label: '排版/格式差' },
  { code: 'VERBOSE', label: '太啰嗦' },
  { code: 'UNSAFE', label: '内容不安全' },
  { code: 'OTHER', label: '其他' }
]

async function loadReasonTags() {
  if (reasonTags.value.length) return
  try {
    const list = await getFeedbackReasonTags()
    reasonTags.value = (list && list.length) ? list : FALLBACK_TAGS
  } catch (e) {
    reasonTags.value = FALLBACK_TAGS
  }
}

/** 点赞: 再次点击则取消 */
async function quickUp() {
  if (feedback.value === 'up') return withdraw()
  await submit('up', [], null)
}

/** 点踩: 先选原因再提交; 已点踩时点击用于修改 */
async function openDownPicker() {
  await loadReasonTags()
  picked.value = []
  fbComment.value = ''
  pickerOpen.value = true
}

function closePicker() {
  pickerOpen.value = false
}

function toggleTag(code) {
  const i = picked.value.indexOf(code)
  if (i >= 0) picked.value.splice(i, 1)
  else if (picked.value.length < 5) picked.value.push(code)
  else ElMessage.warning('最多选择 5 个原因')
}

async function submitDown() {
  const ok = await submit('down', picked.value.slice(), fbComment.value.trim() || null)
  if (ok) pickerOpen.value = false
}

/**
 * 提交反馈。
 * @param {'up'|'down'} rating
 * @param {string[]} tags 原因标签 code
 * @param {string|null} comment 补充说明
 * @returns {Promise<boolean>} 是否成功
 */
async function submit(rating, tags, comment) {
  const seq = props.msg?.seq
  const sessionId = props.msg?.sessionId
  if (seq == null || !sessionId) return false
  fbSubmitting.value = true
  try {
    await submitFeedback({
      sessionId,
      messageSeq: seq,
      rating,
      reasonTags: tags && tags.length ? tags : undefined,
      comment: comment || undefined
    })
    emit('feedback', { seq, rating })
    return true
  } catch (e) {
    ElMessage.error('反馈提交失败：' + extractMessage(e))
    return false
  } finally {
    fbSubmitting.value = false
  }
}

/** 撤销反馈 (幂等) */
async function withdraw() {
  const seq = props.msg?.seq
  const sessionId = props.msg?.sessionId
  if (seq == null || !sessionId) return
  fbSubmitting.value = true
  try {
    await withdrawFeedback(sessionId, seq)
    pickerOpen.value = false
    emit('feedback', { seq, rating: null })
  } catch (e) {
    ElMessage.error('撤销失败：' + extractMessage(e))
  } finally {
    fbSubmitting.value = false
  }
}
</script>

<style scoped>
.msg-item { display: flex; gap: 14px; margin-bottom: 28px; max-width: 900px; margin-left: auto; margin-right: auto; width: 100%; animation: fadeInUp 0.3s ease; }
@keyframes fadeInUp { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
.msg-avatar { width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 13px; font-weight: 600; flex-shrink: 0; overflow: hidden; box-shadow: 0 3px 10px rgba(0,0,0,0.1); }
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.msg-content { flex: 1; min-width: 0; }
.msg-role { font-size: 13px; color: var(--dcdr-text-3); margin-bottom: 6px; font-weight: 500; }

/* 图片消息 */
.msg-images { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.msg-image-wrapper { position: relative; }
.msg-image { max-width: 280px; max-height: 240px; border-radius: 12px; object-fit: cover; cursor: pointer; transition: all 0.25s ease; box-shadow: 0 4px 12px rgba(0,0,0,0.1); border: 1px solid var(--dcdr-border); }
.msg-image:hover { transform: scale(1.02); box-shadow: 0 6px 20px rgba(0,0,0,0.15); }
.image-name { font-size: 11px; color: var(--dcdr-text-3); text-align: center; margin-top: 4px; max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 视频消息 */
.msg-videos { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.msg-video-wrapper { position: relative; }
.msg-video { max-width: 400px; max-height: 300px; border-radius: 12px; background: #000; }
.video-name { font-size: 11px; color: var(--dcdr-text-3); text-align: center; margin-top: 4px; max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 推理过程 */
.msg-reasoning { margin-bottom: 8px; }
.msg-reasoning details { background: linear-gradient(135deg, var(--dcdr-surface) 0%, var(--dcdr-bg-subtle) 100%); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 10px 14px; }
.msg-reasoning summary { cursor: pointer; color: var(--dcdr-text-2); font-size: 12px; font-weight: 500; user-select: none; outline: none; padding: 4px 0; }
.msg-reasoning details > div { color: var(--dcdr-text-2); font-size: 13px; line-height: 1.7; margin-top: 10px; }
.msg-reasoning details > div :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 8px; overflow-x: auto; }
.msg-reasoning details > div :deep(code) { background: var(--dcdr-surface); padding: 2px 8px; border-radius: 4px; }

/* 联网搜索汇总行(正文上方) */
.search-summary { display: flex; align-items: center; gap: 8px; padding: 6px 14px 8px 0; font-size: 13px; color: var(--dcdr-text-2); }
.search-icon { width: 14px; height: 14px; flex: none; }
.search-text { font-weight: 500; }
.favicon-stack { display: flex; }
.favicon-stack .favicon-img { width: 18px; height: 18px; border-radius: 50%; border: 1.5px solid var(--dcdr-border); background: var(--dcdr-bg-subtle); object-fit: cover; margin-left: -6px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
.favicon-stack .favicon-img:first-child { margin-left: 0; }

/* 消息气泡 */
.msg-bubble { padding: 14px 18px; border-radius: 14px; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); font-size: 14px; line-height: 1.8; box-shadow: 0 2px 8px rgba(0,0,0,0.04); word-break: break-word; }
.msg-bubble.streaming { border-color: var(--dcdr-accent-wash); }
.msg-bubble :deep(pre) { background: var(--dcdr-code-bg); color: var(--dcdr-code-fg); padding: 16px; border-radius: 10px; overflow-x: auto; margin: 10px 0; }
.msg-bubble :deep(code) { background: var(--dcdr-bg-subtle); padding: 3px 8px; border-radius: 5px; font-size: 13px; color: var(--dcdr-accent-2); }
.msg-bubble :deep(p) { margin: 8px 0; }
.msg-bubble :deep(img) { max-width: 100%; border-radius: 8px; margin: 8px 0; }
/* 代码块: 语言标签 + 复制 / 运行预览 工具条(正文与推理过程共用) */
.msg-bubble :deep(.code-block), .msg-reasoning details > div :deep(.code-block) { margin: 10px 0; border-radius: 11px; overflow: hidden; border: 1px solid var(--dcdr-code-border); }
.msg-bubble :deep(.code-block pre), .msg-reasoning details > div :deep(.code-block pre) { margin: 0; border-radius: 0; }
.msg-bubble :deep(.code-block-bar), .msg-reasoning details > div :deep(.code-block-bar) { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--dcdr-code-bar); border-bottom: 1px solid var(--dcdr-code-border); }
.msg-bubble :deep(.code-lang), .msg-reasoning details > div :deep(.code-lang) { font-size: 11px; color: var(--dcdr-code-dim); font-family: monospace; letter-spacing: 0.5px; }
.msg-bubble :deep(.code-actions), .msg-reasoning details > div :deep(.code-actions) { margin-left: auto; display: flex; gap: 6px; }
.msg-bubble :deep(.code-action), .msg-reasoning details > div :deep(.code-action) { border: 1px solid var(--dcdr-code-border); background: transparent; color: var(--dcdr-code-dim); font-size: 11px; line-height: 1.6; padding: 2px 10px; border-radius: 6px; cursor: pointer; transition: all 0.15s ease; }
.msg-bubble :deep(.code-action:hover), .msg-reasoning details > div :deep(.code-action:hover) { border-color: var(--dcdr-accent); color: #eaf2ff; background: rgba(46,167,255,0.18); }
.msg-bubble :deep(.code-action.is-done), .msg-reasoning details > div :deep(.code-action.is-done) { border-color: var(--dcdr-success); color: var(--dcdr-code-ok); }
.msg-bubble :deep(h1) { font-size: 20px; font-weight: 700; margin: 16px 0 10px; color: var(--dcdr-text-1); border-bottom: 2px solid var(--dcdr-border); padding-bottom: 6px; }
.msg-bubble :deep(h2) { font-size: 17px; font-weight: 700; margin: 14px 0 8px; color: var(--dcdr-text-1); }
.msg-bubble :deep(h3) { font-size: 15px; font-weight: 600; margin: 12px 0 6px; color: var(--dcdr-text-1); }
.msg-bubble :deep(ul), .msg-bubble :deep(ol) { margin: 8px 0; padding-left: 22px; }
.msg-bubble :deep(li) { margin: 4px 0; }
.msg-bubble :deep(blockquote) { border-left: 3px solid var(--dcdr-border); background: var(--dcdr-surface); margin: 10px 0; padding: 8px 14px; color: var(--dcdr-text-2); border-radius: 0 8px 8px 0; }
.msg-bubble :deep(strong) { color: var(--dcdr-text-1); }
.msg-bubble :deep(table) { border-collapse: collapse; margin: 10px 0; width: 100%; font-size: 13px; }
.msg-bubble :deep(th), .msg-bubble :deep(td) { border: 1px solid var(--dcdr-border); padding: 6px 10px; text-align: left; }
.msg-bubble :deep(th) { background: var(--dcdr-bg-subtle); font-weight: 600; }

/* 引用角标: 圆形数字(deepseek 风格) */
.msg-bubble :deep(.cite) { display: inline-flex; align-items: center; justify-content: center; width: 18px; height: 18px; min-width: 18px; border-radius: 50%; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); color: #fff; font-size: 11px; font-weight: 600; text-decoration: none; margin: 0 2px; vertical-align: super; line-height: 1; cursor: pointer; transition: transform 0.15s ease, box-shadow 0.15s ease; box-shadow: 0 1px 3px var(--dcdr-accent-line); position: relative; top: -1px; }
.msg-bubble :deep(.cite:hover) { transform: scale(1.15); box-shadow: 0 2px 8px var(--dcdr-accent-line); }
.msg-bubble :deep(.cite .cite-num) { pointer-events: none; }

/* 正文下方精简汇总行 */
.search-summary-bottom { display: flex; align-items: center; gap: 8px; padding: 10px 0 4px; }
.summary-count { font-size: 12.5px; color: var(--dcdr-text-3); }
.sources-toggle-mini { border: 1px solid var(--dcdr-border); background: var(--dcdr-surface); color: var(--dcdr-text-2); font-size: 12px; font-weight: 500; padding: 3px 10px; border-radius: 16px; cursor: pointer; transition: all 0.2s ease; }
.sources-toggle-mini:hover { border-color: var(--dcdr-accent); color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }

/* 引用来源卡片列表 */
.sources-list { margin-top: 10px; display: flex; flex-direction: column; gap: 10px; }
.source-card { padding: 12px 14px; border: 1px solid var(--dcdr-border); border-radius: 12px; background: var(--dcdr-surface); box-shadow: 0 2px 8px rgba(0,0,0,0.04); scroll-margin: 80px; transition: all 0.2s ease; }
.source-card.highlight { border-color: var(--dcdr-accent); box-shadow: 0 0 0 2px var(--dcdr-accent-wash), 0 2px 12px var(--dcdr-accent-wash); animation: citeFlash 1.2s ease; }
@keyframes citeFlash { 0% { background: var(--dcdr-accent-wash); } 100% { background: var(--dcdr-surface); } }
.source-card-head { display: flex; align-items: center; gap: 8px; }
.source-index { flex-shrink: 0; width: 22px; height: 22px; border-radius: 50%; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); color: #fff; font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; }
.source-favicon { width: 16px; height: 16px; border-radius: 50%; flex-shrink: 0; background: var(--dcdr-bg-subtle); }
.source-title { font-size: 14px; font-weight: 600; color: var(--dcdr-text-1); line-height: 1.5; text-decoration: none; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-title:hover { color: var(--dcdr-accent); text-decoration: underline; }
.source-title-plain { cursor: default; }
.source-title-plain:hover { color: var(--dcdr-text-1); text-decoration: none; }
.source-host { font-size: 11px; color: var(--dcdr-text-3); flex-shrink: 0; }
.source-snippet { font-size: 13px; line-height: 1.7; color: var(--dcdr-text-2); margin-top: 8px; word-break: break-word; }
.source-snippet :deep(p) { margin: 4px 0; }
.source-snippet :deep(strong) { color: var(--dcdr-text-1); }
.source-snippet :deep(code) { background: var(--dcdr-bg-subtle); padding: 2px 6px; border-radius: 5px; font-size: 12.5px; color: var(--dcdr-accent-2); }
.source-snippet :deep(ul), .source-snippet :deep(ol) { margin: 4px 0; padding-left: 20px; }
.source-snippet :deep(li) { margin: 2px 0; }
.source-snippet :deep(a) { color: var(--dcdr-accent); }
.source-empty { color: var(--dcdr-text-3); font-style: italic; }
.source-toggle { margin-top: 6px; background: none; border: none; color: var(--dcdr-accent); font-size: 12.5px; cursor: pointer; padding: 2px 0; }
.source-toggle:hover { text-decoration: underline; }
.msg-interrupted { margin-top: 6px; display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--dcdr-warning); background: color-mix(in srgb, var(--dcdr-warning) 10%, transparent); border: 1px solid color-mix(in srgb, var(--dcdr-warning) 32%, transparent); border-radius: 8px; padding: 4px 10px; }
.msg-interrupted svg { width: 13px; height: 13px; flex: none; }

/* 工具调用折叠提示 */
.tool-invoke { margin-top: 8px; border: 1px solid var(--dcdr-border); border-radius: 10px; background: var(--dcdr-surface); overflow: hidden; }
.tool-invoke-toggle { width: 100%; display: flex; align-items: center; gap: 6px; padding: 7px 12px; background: transparent; border: none; cursor: pointer; font-size: 12px; color: var(--dcdr-text-2); text-align: left; }
.tool-invoke-toggle:hover { background: var(--dcdr-bg-subtle); }
.tool-icon { width: 13px; height: 13px; flex: none; }
.tool-caret { margin-left: auto; color: var(--dcdr-text-3); }
.tool-invoke-list { padding: 0 12px 10px; display: flex; flex-direction: column; gap: 8px; }
.tool-item { border: 1px solid var(--dcdr-border); border-radius: 8px; background: var(--dcdr-surface); padding: 8px 10px; }
.tool-item-head { display: flex; align-items: center; gap: 8px; }
.tool-badge { font-size: 12px; font-weight: 600; color: var(--dcdr-accent); background: var(--dcdr-accent-wash); border-radius: 6px; padding: 2px 8px; }
.tool-id { font-size: 11px; color: var(--dcdr-text-3); font-family: monospace; }
.tool-args { margin: 6px 0 0; font-size: 11px; line-height: 1.5; color: var(--dcdr-text-2); background: var(--dcdr-surface); border-radius: 6px; padding: 6px 8px; white-space: pre-wrap; word-break: break-word; max-height: 240px; overflow: auto; }

/* 光标动画 */
.cursor { animation: blink 1s infinite; color: var(--dcdr-accent); font-weight: bold; }
@keyframes blink { 50% { opacity: 0; } }

/* 消息操作(编辑/重试) */
.msg-actions { margin-top: 8px; display: flex; gap: 8px; }
.btn { display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--dcdr-border); background: var(--dcdr-surface); color: var(--dcdr-text-2); padding: 5px 12px; border-radius: 8px; font-size: 12px; cursor: pointer; transition: all 0.2s; }
.btn:hover { border-color: var(--dcdr-accent); color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }
.btn-small { padding: 4px 10px; font-size: 12px; }
.btn-small svg { width: 12px; height: 12px; }
.btn-primary { background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); border-color: transparent; color: #fff; }
.btn-primary:hover { color: #fff; opacity: 0.9; }

/* 编辑历史消息 */
.edit-box { background: var(--dcdr-surface); border: 1px solid var(--dcdr-accent-wash); border-radius: 14px; padding: 10px; box-shadow: 0 2px 8px var(--dcdr-accent-wash); }
.edit-textarea { width: 100%; min-height: 84px; resize: vertical; border: 1px solid var(--dcdr-border); border-radius: 8px; background: var(--dcdr-bg-subtle); color: var(--dcdr-text-1); padding: 10px 12px; font-size: 14px; line-height: 1.6; outline: none; font-family: inherit; box-sizing: border-box; }
.edit-textarea:focus { border-color: var(--dcdr-accent); background: var(--dcdr-surface); }

/* 元信息 */
.msg-meta { margin-top: 8px; display: flex; gap: 10px; font-size: 12px; color: var(--dcdr-text-3); }
.msg-meta span { background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border); border-radius: 8px; padding: 3px 10px; }

/* ===== V3.1 F-48 回答反馈: 点赞/点踩 + 原因选择 ===== */
.msg-feedback { margin-top: 8px; display: flex; align-items: center; gap: 8px; }
.fb-btn { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 28px; border: 1px solid var(--dcdr-border); background: var(--dcdr-surface); border-radius: 8px; cursor: pointer; color: var(--dcdr-text-3); line-height: 1; transition: all 0.2s; }
.fb-btn svg { width: 15px; height: 15px; }
.fb-btn:hover:not(:disabled) { border-color: var(--dcdr-accent); color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }
.fb-btn.active { border-color: transparent; color: var(--dcdr-accent-soft); background: var(--dcdr-accent-wash); box-shadow: inset 0 0 0 1px var(--dcdr-accent-line); }
.fb-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.fb-state { font-size: 12px; color: var(--dcdr-text-3); }
.fb-undo { border: none; background: none; color: var(--dcdr-accent); cursor: pointer; font-size: 12px; padding: 0 2px; }
.fb-undo:hover { text-decoration: underline; }
.fb-picker { margin-top: 8px; padding: 12px 14px; border: 1px solid var(--dcdr-border); border-radius: 12px; background: var(--dcdr-bg-subtle); box-shadow: 0 2px 8px rgba(0,0,0,0.04); animation: fadeInUp 0.2s ease; }
.fb-picker-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 10px; }
.fb-picker-title { font-size: 13px; font-weight: 600; color: var(--dcdr-text-1); }
.fb-picker-tip { font-size: 11.5px; color: var(--dcdr-text-3); }
.fb-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.fb-tag { border: 1px solid var(--dcdr-border); background: var(--dcdr-surface); color: var(--dcdr-text-2); font-size: 12.5px; padding: 5px 12px; border-radius: 16px; cursor: pointer; transition: all 0.2s; }
.fb-tag:hover { border-color: var(--dcdr-accent); color: var(--dcdr-accent); }
.fb-tag.on { border-color: transparent; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); color: #fff; }
.fb-comment { width: 100%; margin-top: 10px; resize: vertical; border: 1px solid var(--dcdr-border); border-radius: 8px; background: var(--dcdr-surface); color: var(--dcdr-text-1); padding: 8px 10px; font-size: 13px; line-height: 1.6; outline: none; font-family: inherit; box-sizing: border-box; }
.fb-comment:focus { border-color: var(--dcdr-accent); }
.fb-picker-actions { margin-top: 10px; display: flex; justify-content: flex-end; gap: 8px; }
</style>
