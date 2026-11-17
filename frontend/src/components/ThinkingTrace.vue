<template>
  <div class="tt" :class="{ 'is-live': live, 'is-open': open }">
    <button class="tt-head" type="button" @click="toggle">
      <span class="tt-mark" :class="live ? 'running' : 'finished'">
        <span v-if="live" class="tt-spin"></span>
        <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.2 4.2L19 7"/></svg>
      </span>
      <span class="tt-title">{{ live ? '思考中' : '思考' }}</span>
      <span v-if="live && elapsed > 0" class="tt-meta">{{ elapsed }}s</span>
      <span v-if="toolCount" class="tt-meta">{{ toolCount }} 个工具</span>
      <svg class="tt-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 10l4 4 4-4"/></svg>
    </button>

    <div v-show="open" ref="bodyEl" class="tt-body">
      <template v-for="(seg, i) in view" :key="i">
        <!-- 思考文本：与工具步骤同一条时间线，顺序就是模型"先想什么、再做什么"的真实顺序 -->
        <div v-if="seg.kind === 'reason'" class="tt-reason" v-html="renderReasoning(seg.text)" @click="onReasonClick"></div>

        <!-- 状态提示：准备阶段的临时说明（识别图片/检索知识库…），做完即被下一步覆盖 -->
        <div v-else-if="seg.kind === 'note'" class="tt-note">{{ seg.text }}</div>

        <!-- 工具步骤 -->
        <div v-else class="tt-tool" :class="seg.status">
          <button class="tt-tool-row" type="button" @click="toggleTool(i)">
            <span class="tt-tool-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, k) in seg.icon" :key="k" :d="d" />
              </svg>
            </span>
            <span class="tt-tool-label">{{ seg.label }}</span>
            <span v-if="seg.target" class="tt-tool-target" :title="seg.target">{{ seg.target }}</span>
            <span class="tt-tool-right">
              <span v-if="seg.cost" class="tt-tool-cost">{{ seg.cost }}</span>
              <span v-if="seg.status === 'running'" class="tt-spin small"></span>
              <svg v-else-if="seg.status === 'error'" class="tt-state fail" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M8 8l8 8M16 8l-8 8"/></svg>
              <svg v-else class="tt-state ok" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 12.5l3.5 3.5L18 7.5"/></svg>
              <svg class="tt-chevron small" :class="{ flipped: expanded.has(i) }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 10l4 4 4-4"/></svg>
            </span>
          </button>
          <div v-if="expanded.has(i)" class="tt-tool-detail">
            <template v-if="seg.argsText">
              <div class="tt-detail-label">参数</div>
              <pre class="tt-pre">{{ seg.argsText }}</pre>
            </template>
            <template v-if="seg.resultText">
              <div class="tt-detail-label">结果</div>
              <pre class="tt-pre">{{ seg.resultText }}</pre>
            </template>
            <div v-if="!seg.argsText && !seg.resultText" class="tt-detail-label">这一步没有可展示的参数或结果</div>
            <button v-if="seg.opId" class="tt-undo" type="button" @click.stop="$emit('undo-op', seg.opId)">
              撤销这次改动
            </button>
          </div>
        </div>
      </template>

      <!-- 思考中/工具执行中的实时提示，跟在最后一步后面，像"正在读第 3 个文件" -->
      <div v-if="live && liveNote" class="tt-live">
        <span class="think-dots"><i></i><i></i><i></i></span>
        <span>{{ liveNote }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 思考流：把「模型的想法」和「工具的执行」放在同一个折叠容器里，按发生顺序排成一条时间线。
 *
 * <p>为什么不分成「思考面板 + 工具面板」两块：那样用户读到的顺序是"先看完所有想法，再看到所有工具"，
 * 而真实过程是想了 → 读了文件 → 又想了 → 又搜了一下。分成两块等于把时间线拆散，
 * 还在两处各占一份纵向空间；合起来之后，一次回答的过程是从头到尾顺着读下来的。
 *
 * <p>折叠策略：流式过程中保持展开并自动跟随最新一步；正文一开始输出就自动收起（用户在等答案，
 * 过程信息不该继续挤占视线）；用户手动点过之后一律尊重手动选择，不再自动改动。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useMarkdown } from '@/composables/useMarkdown'
import { useCodeBlock } from '@/composables/useCodeBlock'
import { clipText, describeTool, formatCost, prettyArgs } from '@/utils/toolTrace'

const props = defineProps({
  /** 时间线段落：{kind:'reason', text} | {kind:'tool', name, args, result, status, ms, opId} | {kind:'note', text} */
  segments: { type: Array, default: () => [] },
  /** 是否仍在流式生成（决定展开/计时/动画） */
  live: { type: Boolean, default: false },
  /** 初始是否展开（「任务对话」这类回看页面传 true：用户点进来就想看过程） */
  defaultOpen: { type: Boolean, default: false },
  /** 已思考秒数（流式态由父组件计时） */
  elapsed: { type: Number, default: 0 },
  /** 正文是否已经开始输出（一开始输出就自动收起，把视线让给答案） */
  hasAnswer: { type: Boolean, default: false },
  /** 流式态的临时状态提示（"正在识别图片…"），跟在时间线末尾 */
  liveNote: { type: String, default: '' }
})
const emit = defineEmits(['undo-op', 'preview-code'])

const { renderMarkdown } = useMarkdown()
const renderReasoning = (text) => renderMarkdown(text, { codeActions: true })
const { handleCodeClick } = useCodeBlock((payload) => emit('preview-code', payload))
function onReasonClick(e) {
  handleCodeClick(e)
}

const open = ref(props.defaultOpen || props.live)
/** 用户是否手动干预过展开状态：干预之后所有"自动收起/自动展开"都让位 */
const manual = ref(false)
function toggle() {
  open.value = !open.value
  manual.value = true
}

const expanded = ref(new Set())
function toggleTool(i) {
  const next = new Set(expanded.value)
  if (next.has(i)) next.delete(i)
  else next.add(i)
  expanded.value = next
}

const toolCount = computed(() => props.segments.filter(s => s.kind === 'tool').length)

/** 模板用的视图模型：把工具名/参数翻译成人能读的一行，避免模板里再算一遍 */
const view = computed(() => props.segments.map((seg) => {
  if (seg.kind !== 'tool') return seg
  const info = describeTool(seg.name, seg.args)
  return {
    kind: 'tool',
    name: seg.name,
    id: seg.id,
    status: seg.status || 'done',
    opId: seg.opId || '',
    label: info.label,
    target: info.target,
    icon: info.icon,
    cost: formatCost(seg.ms),
    argsText: prettyArgs(seg.args),
    // 结果只在用户主动展开时渲染，但取值先截断：工具结果上万的场景很常见
    resultText: seg.result ? clipText(seg.result) : ''
  }
}))

// 流式结束(通常是消息已落库、该组件被换掉)后收起，保持与历史消息一致的默认态
watch(() => props.live, (v) => {
  if (v) {
    open.value = true
    manual.value = false
  } else if (!manual.value) {
    open.value = props.defaultOpen
  }
})

// 正文开始输出 → 自动收起（用户已经在读答案了，过程块继续展开会把答案顶出视野）
watch(() => props.hasAnswer, (v) => {
  if (v && props.live && !manual.value) open.value = false
})

const bodyEl = ref(null)
/** 内容尾部签名：思考文本按 token 增长、工具步骤按条增加，两者都要触发自动跟随 */
const tail = computed(() => {
  const n = props.segments.length
  if (!n) return 0
  const last = props.segments[n - 1]
  return n * 100000 + (last.text ? last.text.length : 0) + (last.status === 'done' ? 1 : 0)
})
watch(tail, () => {
  if (!props.live || !open.value) return
  nextTick(() => {
    const el = bodyEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
})
</script>

<style scoped>
.tt {
  margin-bottom: 10px;
  border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-inner);
  background: var(--dcdr-surface-2);
  overflow: hidden;
  transition: border-color 0.2s var(--dcdr-ease), background 0.2s var(--dcdr-ease);
}
.tt.is-live { border-color: var(--dcdr-accent-line); background: var(--dcdr-surface); }

/* 头部是一整条可点区域：折叠容器的点击热区不该只有一个 12px 的小三角 */
.tt-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: transparent;
  border: none;
  cursor: pointer;
  text-align: left;
  font-size: 12.5px;
  color: var(--dcdr-text-2);
}
.tt-head:hover { background: var(--dcdr-hover); }
.tt-mark { display: inline-flex; align-items: center; justify-content: center; width: 15px; height: 15px; flex: none; color: var(--dcdr-text-3); }
.tt-mark.finished { color: var(--dcdr-success); }
.tt-mark svg { width: 13px; height: 13px; }
.tt-title { font-weight: 600; color: var(--dcdr-text-1); }
.is-live .tt-title { color: var(--dcdr-accent); }
.tt-meta { font-size: 11.5px; color: var(--dcdr-text-3); }
.tt-meta + .tt-meta::before { content: '·'; margin-right: 6px; }
.tt-chevron { width: 14px; height: 14px; margin-left: auto; color: var(--dcdr-text-3); transition: transform 0.2s var(--dcdr-ease); }
.tt.is-open .tt-chevron { transform: rotate(180deg); }
.tt-chevron.small { margin-left: 0; width: 12px; height: 12px; }
.tt-chevron.flipped { transform: rotate(180deg); }

/* 时间线主体：限高 + 内部滚动，避免"一段长思考把答案顶到两屏之外" */
.tt-body {
  max-height: 420px;
  overflow-y: auto;
  padding: 2px 12px 10px;
  border-top: 1px solid var(--dcdr-border);
}

/* 思考文本 */
.tt-reason {
  font-size: 13px;
  line-height: 1.75;
  color: var(--dcdr-text-2);
  padding: 6px 0 6px 12px;
  border-left: 2px solid var(--dcdr-border);
  margin: 6px 0;
  word-break: break-word;
}
.tt-reason :deep(p) { margin: 4px 0; }
.tt-reason :deep(ul), .tt-reason :deep(ol) { margin: 4px 0; padding-left: 20px; }
.tt-reason :deep(li) { margin: 2px 0; }
.tt-reason :deep(code) { background: var(--dcdr-bg-subtle); padding: 1px 6px; border-radius: 4px; font-size: 12.5px; }
.tt-reason :deep(a) { color: var(--dcdr-accent); }
.tt-note { font-size: 12.5px; color: var(--dcdr-text-3); padding: 4px 0 4px 12px; }

/* 思考里的代码块：复制/运行预览按钮照旧可用（与正文同一套 useCodeBlock 逻辑） */
.tt-reason :deep(.code-block) { margin: 8px 0; border-radius: 10px; overflow: hidden; border: 1px solid var(--dcdr-code-border); }
.tt-reason :deep(.code-block pre) { margin: 0; padding: 12px; background: var(--dcdr-code-bg); color: var(--dcdr-code-fg); overflow-x: auto; font-size: 12.5px; }
.tt-reason :deep(.code-block-bar) { display: flex; align-items: center; gap: 8px; padding: 5px 10px; background: var(--dcdr-code-bar); border-bottom: 1px solid var(--dcdr-code-border); }
.tt-reason :deep(.code-lang) { font-size: 11px; color: var(--dcdr-code-dim); font-family: var(--dcdr-font-mono); }
.tt-reason :deep(.code-actions) { margin-left: auto; display: flex; gap: 6px; }
.tt-reason :deep(.code-action) { border: 1px solid var(--dcdr-code-border); background: transparent; color: var(--dcdr-code-dim); font-size: 11px; padding: 2px 9px; border-radius: 6px; cursor: pointer; }
.tt-reason :deep(.code-action:hover) { border-color: var(--dcdr-accent); color: var(--dcdr-accent-soft); background: var(--dcdr-accent-wash); }
.tt-reason :deep(.code-action.is-done) { border-color: var(--dcdr-success); color: var(--dcdr-code-ok); }

/* 工具步骤行 */
.tt-tool { border-radius: 8px; margin: 4px 0; background: var(--dcdr-surface-faint); }
.tt-tool.running { background: var(--dcdr-accent-wash); }
.tt-tool-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 9px;
  background: transparent;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  text-align: left;
  font-size: 12.5px;
  color: var(--dcdr-text-2);
}
.tt-tool-row:hover { background: var(--dcdr-hover); }
.tt-tool-icon { display: inline-flex; flex: none; color: var(--dcdr-text-3); }
.tt-tool-icon svg { width: 13px; height: 13px; }
.tt-tool-label { font-weight: 600; color: var(--dcdr-text-1); flex: none; }
.tt-tool-target {
  font-family: var(--dcdr-font-mono);
  font-size: 11.5px;
  color: var(--dcdr-text-2);
  background: var(--dcdr-bg-subtle);
  border-radius: 5px;
  padding: 1px 6px;
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tt-tool-right { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; flex: none; }
.tt-tool-cost { font-size: 11px; color: var(--dcdr-text-3); }
.tt-state { width: 12px; height: 12px; }
.tt-state.ok { color: var(--dcdr-success); }
.tt-state.fail { color: var(--dcdr-danger); }

/* 展开后的参数 / 结果 */
.tt-tool-detail { padding: 2px 10px 9px 30px; }
.tt-detail-label { font-size: 11px; color: var(--dcdr-text-3); margin: 6px 0 3px; }
.tt-pre {
  margin: 0;
  padding: 7px 9px;
  background: var(--dcdr-bg-subtle);
  border: 1px solid var(--dcdr-border);
  border-radius: 6px;
  font-family: var(--dcdr-font-mono);
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--dcdr-text-2);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow: auto;
}
.tt-undo {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--dcdr-text-2);
  background: var(--dcdr-surface);
  border: 1px solid var(--dcdr-border);
  border-radius: 6px;
  padding: 2px 10px;
  cursor: pointer;
}
.tt-undo:hover { color: var(--dcdr-accent); border-color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }

/* 实时提示 */
.tt-live { display: flex; align-items: center; gap: 7px; padding: 6px 0 2px 2px; font-size: 12.5px; color: var(--dcdr-text-2); }

/* 旋转圈：思考中与"正在执行"共用 */
.tt-spin {
  width: 12px; height: 12px; flex: none;
  border: 1.6px solid var(--dcdr-accent-line);
  border-top-color: var(--dcdr-accent);
  border-radius: 50%;
  animation: ttSpin 0.75s linear infinite;
}
.tt-spin.small { width: 11px; height: 11px; border-width: 1.4px; }
@keyframes ttSpin { to { transform: rotate(360deg); } }

/* 三点跳动：沿用首页已有的 think-dots 视觉语言 */
.think-dots { display: inline-flex; gap: 3px; align-items: center; }
.think-dots i { width: 4px; height: 4px; border-radius: 50%; background: var(--dcdr-accent); animation: ttDot 1.1s ease-in-out infinite; }
.think-dots i:nth-child(2) { animation-delay: 0.15s; }
.think-dots i:nth-child(3) { animation-delay: 0.3s; }
@keyframes ttDot { 0%, 100% { opacity: 0.3; transform: translateY(0); } 50% { opacity: 1; transform: translateY(-2px); } }
</style>
