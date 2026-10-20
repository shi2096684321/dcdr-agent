import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/**
 * 主题状态。
 *
 * 三态：auto（跟随系统）/ light / dark。
 * 生效方式是在 <html> 上写两个东西：
 *   - data-theme="dark|light"  → 我们自己组件的令牌（theme.css）
 *   - class="dark"             → Element Plus 的暗色变量
 * 两个都写，是为了不依赖任何构建期插件，也不给 EP 的组件单独造一套样式。
 *
 * 首屏防闪烁在 index.html 的内联脚本里做（比 Vue 挂载早），
 * 这里的 init() 只负责兜底 + 监听系统外观变化。
 */
export const THEME_STORAGE_KEY = 'dcdr-theme'

export const THEME_MODES = [
  { value: 'auto', label: '跟随系统', hint: '随操作系统外观自动切换' },
  { value: 'light', label: '浅色', hint: '始终使用浅色主题' },
  { value: 'dark', label: '深色', hint: '始终使用深色主题' }
]

const VALID = THEME_MODES.map((m) => m.value)

function readStored() {
  try {
    const v = localStorage.getItem(THEME_STORAGE_KEY)
    // 首次访问默认深色：深色是这个产品的品牌主张，用户可在切换器里改成跟随系统
    return VALID.includes(v) ? v : 'dark'
  } catch {
    return 'dark'
  }
}

function systemPrefersDark() {
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  } catch {
    return true
  }
}

function prefersReducedMotion() {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref(readStored())
  const systemDark = ref(systemPrefersDark())

  const resolved = computed(() => (mode.value === 'auto' ? (systemDark.value ? 'dark' : 'light') : mode.value))
  const isDark = computed(() => resolved.value === 'dark')
  const label = computed(() => THEME_MODES.find((m) => m.value === mode.value)?.label ?? '跟随系统')

  /** 把主题写到 <html> 上（无过渡） */
  function apply(theme = resolved.value) {
    const root = document.documentElement
    root.setAttribute('data-theme', theme)
    root.classList.toggle('dark', theme === 'dark')
    root.style.colorScheme = theme
  }

  /** 带 200ms 过渡地切换；过渡类只挂 220ms，避免常驻拖慢渲染 */
  function applyWithTransition(theme) {
    const root = document.documentElement
    if (prefersReducedMotion()) {
      apply(theme)
      return
    }
    root.classList.add('theme-switching')
    apply(theme)
    window.setTimeout(() => root.classList.remove('theme-switching'), 220)
  }

  function setMode(next) {
    if (!VALID.includes(next)) return
    mode.value = next
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next)
    } catch {
      /* 隐私模式下写不进去，忽略即可 */
    }
    applyWithTransition(resolved.value)
  }

  /** 给顶栏单按钮用：在深/浅之间来回 */
  function toggle() {
    setMode(isDark.value ? 'light' : 'dark')
  }

  let inited = false

  function init() {
    if (inited) return
    inited = true

    // 首屏已经是内联脚本设好的，这里只兜底一次
    apply(resolved.value)

    try {
      const mq = window.matchMedia('(prefers-color-scheme: dark)')
      const onChange = (e) => {
        systemDark.value = e.matches
        if (mode.value === 'auto') applyWithTransition(resolved.value)
      }
      mq.addEventListener ? mq.addEventListener('change', onChange) : mq.addListener(onChange)
    } catch {
      /* matchMedia 不可用就不跟随系统 */
    }
  }

  return { mode, resolved, isDark, label, systemDark, setMode, toggle, init, apply }
})
