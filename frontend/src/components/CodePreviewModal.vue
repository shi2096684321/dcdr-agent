<template>
  <div v-if="visible" class="code-preview-modal" @click.self="close" @keydown.esc="close">
    <div class="cp-panel">
      <div class="cp-head">
        <span class="cp-icon">▶</span>
        <span class="cp-title">运行预览</span>
        <span class="cp-lang">{{ langLabel(lang) }}</span>
        <div class="cp-actions">
          <button class="cp-btn" @click="copy">{{ copied ? '✓ 已复制' : '复制代码' }}</button>
          <button class="cp-btn" @click="openInNewTab">新窗口打开</button>
          <button class="cp-btn cp-close" @click="close">关闭</button>
        </div>
      </div>
      <!-- 沙箱 iframe: 只给脚本/表单/弹窗权限, 不给 allow-same-origin,
           生成的代码无法访问宿主页面的 localStorage / Cookie / DOM -->
      <iframe
        :key="frameKey"
        class="cp-frame"
        :srcdoc="doc"
        sandbox="allow-scripts allow-modals allow-forms allow-popups"
        referrerpolicy="no-referrer"
        title="代码运行预览"
      ></iframe>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { buildPreviewDoc, langLabel, copyText } from '@/composables/useCodeBlock'

const props = defineProps({
  visible: { type: Boolean, default: false },
  code: { type: String, default: '' },
  lang: { type: String, default: '' }
})
const emit = defineEmits(['update:visible'])

const copied = ref(false)
// 每次打开都换 key, 强制重建 iframe(避免上一次运行的定时器/状态残留)
const frameKey = ref(0)
const doc = computed(() => buildPreviewDoc(props.code, props.lang))

watch(() => props.visible, (v) => {
  if (v) {
    frameKey.value += 1
    copied.value = false
  }
})

function close() {
  emit('update:visible', false)
}

async function copy() {
  const ok = await copyText(props.code)
  if (!ok) return
  copied.value = true
  setTimeout(() => { copied.value = false }, 1500)
}

// 新窗口打开: 用 Blob URL, 带 noopener 切断 opener, 源码页无法反向操作本应用
function openInNewTab() {
  const blob = new Blob([doc.value], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  window.open(url, '_blank', 'noopener,noreferrer')
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}

function onKeydown(e) {
  if (e.key === 'Escape' && props.visible) close()
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.code-preview-modal { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.72); z-index: 9999; display: flex; align-items: center; justify-content: center; padding: 32px; animation: cpFade 0.18s ease; }
@keyframes cpFade { from { opacity: 0; } to { opacity: 1; } }
.cp-panel { width: min(1100px, 100%); height: min(760px, 100%); background: #fff; border-radius: 14px; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 24px 72px rgba(0, 0, 0, 0.4); }
.cp-head { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-bottom: 1px solid #e8ecf4; background: #f8fafc; }
.cp-icon { color: #2e9e6b; font-size: 12px; }
.cp-title { font-size: 14px; font-weight: 600; color: #1f2937; }
.cp-lang { font-size: 11px; color: #2563eb; background: #e8f0ff; border-radius: 6px; padding: 2px 8px; font-family: monospace; }
.cp-actions { margin-left: auto; display: flex; gap: 8px; }
.cp-btn { border: 1px solid #d7dded; background: #fff; color: #4a5568; font-size: 12px; padding: 5px 12px; border-radius: 8px; cursor: pointer; transition: all 0.15s ease; }
.cp-btn:hover { border-color: #667eea; color: #667eea; }
.cp-close:hover { border-color: #f56c6c; color: #f56c6c; }
.cp-frame { flex: 1; width: 100%; border: none; background: #fff; }
</style>
