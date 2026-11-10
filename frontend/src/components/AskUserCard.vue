<template>
  <div class="ask-card" :class="{ answered }">
    <div class="ask-head">
      <span class="ask-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5 8.6 8.6 0 0 1-3.7-.8L3 21l1.8-5.1A8.5 8.5 0 1 1 21 11.5z" />
        </svg>
      </span>
      <span class="ask-title">{{ answered ? '已确认' : '需要你确认' }}</span>
      <span v-if="!answered && totalCount > 1" class="ask-progress">{{ answeredCount }} / {{ totalCount }}</span>
    </div>

    <div v-for="q in questions" :key="q.id" class="ask-question">
      <div class="ask-question-head">
        <span v-if="q.header" class="ask-tag">{{ q.header }}</span>
        <span class="ask-question-text">{{ q.question }}</span>
        <span v-if="q.multiSelect" class="ask-multi">可多选</span>
      </div>

      <!-- 已回答: 只读展示, 让用户能回看当时选了什么 -->
      <div v-if="answered" class="ask-answer-view">{{ answerView(q) }}</div>

      <template v-else>
        <div class="ask-options">
          <button
            v-for="(opt, i) in q.options"
            :key="opt.value || i"
            type="button"
            class="ask-option"
            :class="{ selected: isSelected(q, opt) }"
            @click="onOption(q, opt)"
          >
            <span class="ask-letter">{{ letter(i) }}</span>
            <span class="ask-option-body">
              <span class="ask-option-label">{{ opt.label }}</span>
              <span v-if="opt.description" class="ask-option-desc">{{ opt.description }}</span>
            </span>
          </button>
        </div>
        <!-- 自定义回答: 用户永远可以自己说, 不被选项框住 -->
        <div class="ask-custom">
          <span class="ask-letter">{{ letter(q.options ? q.options.length : 0) }}</span>
          <input
            v-model="customInputs[q.id]"
            type="text"
            class="ask-custom-input"
            placeholder="以上都不合适？直接输入你的回答"
            @keydown.enter.prevent="onCustomEnter(q)"
          />
        </div>
      </template>
    </div>

    <div v-if="!answered" class="ask-foot">
      <button type="button" class="ask-skip" @click="onSkip">跳过</button>
      <button v-if="!immediateSubmit" type="button" class="ask-submit" :disabled="!allAnswered" @click="onSubmit">
        完成
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive } from 'vue'

const props = defineProps({
  /** 后端的 questions: [{id, header, question, options:[{value,label,description}], multiSelect}] */
  questions: { type: Array, default: () => [] },
  /** 是否已答复(历史回放时由父组件按消息顺序判定) */
  answered: { type: Boolean, default: false },
  /** 已答复时展示的答案文本(用户那条消息的内容) */
  answerText: { type: String, default: '' }
})

const emit = defineEmits(['submit', 'skip'])

const selected = reactive({})
const customInputs = reactive({})

const totalCount = computed(() => props.questions.length)

/**
 * 单题单选: 点一下就提交。
 * <p>多题或多选必须先"完成", 否则用户点了第一题的选项就被当成整体答案发出去,
 * 后面的题等于没问 —— 这是 CodeBuddy 的判定口径, 抄过来避免踩同一个坑。
 */
const immediateSubmit = computed(() =>
  props.questions.length === 1 && !props.questions[0].multiSelect
)

const answeredCount = computed(() =>
  props.questions.filter(q => (selected[q.id] || []).length > 0 || (customInputs[q.id] || '').trim()).length
)

const allAnswered = computed(() => answeredCount.value === totalCount.value && totalCount.value > 0)

function letter(index) {
  return String.fromCharCode(65 + index)
}

function isSelected(q, opt) {
  return (selected[q.id] || []).includes(opt.value)
}

function onOption(q, opt) {
  if (immediateSubmit.value) {
    selected[q.id] = [opt.value]
    submit({ [q.id]: [opt.value] }, opt.label)
    return
  }
  const current = selected[q.id] || []
  if (q.multiSelect) {
    selected[q.id] = current.includes(opt.value)
      ? current.filter(v => v !== opt.value)
      : [...current, opt.value]
  } else {
    selected[q.id] = [opt.value]
  }
}

function onCustomEnter(q) {
  const text = (customInputs[q.id] || '').trim()
  if (text) submit({ [q.id]: [text] }, text)
}

function onSubmit() {
  const answers = {}
  const labels = []
  for (const q of props.questions) {
    const picked = [...(selected[q.id] || [])]
    const custom = (customInputs[q.id] || '').trim()
    if (custom) picked.push(custom)
    if (picked.length) {
      answers[q.id] = picked
      labels.push(picked.join(', '))
    }
  }
  if (!Object.keys(answers).length) return
  submit(answers, labels.join('；'))
}

/** 跳过也要发出去: 让模型明确"用户不想回答", 而不是以为自己在干等 */
function onSkip() {
  emit('skip')
}

function submit(answers, text) {
  emit('submit', { answers, text })
}

function answerView(q) {
  // 历史里只有用户那条消息的文本, 没有逐题的原始选择; 单题时它就是答案, 多题时退化成整段回显
  return props.answerText || '（已跳过）'
}
</script>

<style scoped>
.ask-card {
  margin: 10px 0 0 46px;
  padding: 14px 16px;
  border: 1px solid var(--dcdr-accent-line);
  border-radius: 14px;
  background: var(--dcdr-surface-faint);
  max-width: 720px;
}
.ask-card.answered { border-color: var(--dcdr-border); }

.ask-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.ask-icon { display: inline-flex; color: var(--dcdr-accent); }
.ask-icon svg { width: 15px; height: 15px; }
.ask-title { font-size: 13px; font-weight: 600; color: var(--dcdr-text-1); }
.ask-progress { margin-left: auto; font-size: 12px; color: var(--dcdr-text-3); font-variant-numeric: tabular-nums; }

.ask-question + .ask-question { margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--dcdr-border); }
.ask-question-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.ask-tag {
  padding: 1px 7px; border-radius: 6px; font-size: 11px;
  color: var(--dcdr-accent); background: var(--dcdr-accent-wash);
}
.ask-question-text { font-size: 13px; color: var(--dcdr-text-1); }
.ask-multi { font-size: 11px; color: var(--dcdr-text-3); }

.ask-options { display: flex; flex-direction: column; gap: 6px; }
.ask-option {
  display: flex; align-items: flex-start; gap: 9px; width: 100%;
  padding: 9px 11px; text-align: left; font-family: inherit;
  border: 1px solid var(--dcdr-border); border-radius: 10px;
  background: transparent; color: var(--dcdr-text-1); cursor: pointer;
  transition: border-color .16s ease, background .16s ease;
}
.ask-option:hover { border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); }
.ask-option.selected { border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); }
.ask-letter {
  flex: none; width: 18px; height: 18px; border-radius: 5px; font-size: 11px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--dcdr-border); color: var(--dcdr-text-2);
}
.ask-option.selected .ask-letter { background: var(--dcdr-accent); color: #fff; }
.ask-option-body { display: flex; flex-direction: column; gap: 2px; }
.ask-option-label { font-size: 13px; }
.ask-option-desc { font-size: 12px; color: var(--dcdr-text-3); }

.ask-custom {
  display: flex; align-items: center; gap: 9px;
  margin-top: 6px; padding: 2px 11px 2px 11px;
  border: 1px solid var(--dcdr-border); border-radius: 10px;
}
.ask-custom-input {
  flex: 1; padding: 8px 0; border: none; outline: none; background: transparent;
  font-family: inherit; font-size: 13px; color: var(--dcdr-text-1);
}
.ask-custom-input::placeholder { color: var(--dcdr-text-3); }

.ask-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
.ask-skip, .ask-submit {
  padding: 5px 14px; border-radius: 8px; font-family: inherit; font-size: 12px; cursor: pointer;
  border: 1px solid var(--dcdr-border); background: transparent; color: var(--dcdr-text-2);
}
.ask-skip:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); }
.ask-submit { border-color: transparent; background: var(--dcdr-accent); color: #fff; }
.ask-submit:disabled { opacity: .45; cursor: not-allowed; }

.ask-answer-view {
  padding: 8px 11px; border-radius: 10px; font-size: 13px;
  color: var(--dcdr-text-2); background: var(--dcdr-surface-faint);
}
</style>
