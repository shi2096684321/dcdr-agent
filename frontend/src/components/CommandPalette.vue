<template>
  <Teleport to="body">
    <Transition name="cmdk">
      <div v-if="modelValue" class="cmdk-mask" @click.self="close">
        <div class="cmdk-panel" role="dialog" aria-modal="true" aria-label="命令面板">
          <div class="cmdk-input-row">
            <span class="cmdk-icon" v-html="ICON_SEARCH" />
            <input
              ref="inputRef"
              v-model="keyword"
              class="cmdk-input"
              type="text"
              placeholder="搜索页面、动作…"
              autocomplete="off"
              spellcheck="false"
              @keydown="onKeydown"
            />
            <kbd class="cmdk-kbd">ESC</kbd>
          </div>

          <div class="cmdk-list">
            <p v-if="!groups.length" class="cmdk-empty">没有匹配的命令</p>
            <template v-for="group in groups" :key="group.title">
              <p class="cmdk-group">{{ group.title }}</p>
              <button
                v-for="item in group.items"
                :key="item.id"
                type="button"
                class="cmdk-item"
                :class="{ 'is-active': activeId === item.id }"
                @mouseenter="activeId = item.id"
                @click="pick(item)"
              >
                <span class="cmdk-item-label">{{ item.label }}</span>
                <span class="cmdk-item-hint">{{ item.hint }}</span>
              </button>
            </template>
          </div>

          <div class="cmdk-foot">
            <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
            <span><kbd>↵</kbd> 执行</span>
            <span><kbd>⌘K</kbd> 开关</span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { THEME_MODES, useThemeStore } from '@/stores/theme'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  navItems: { type: Array, default: () => [] },
  isAdmin: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'select'])

const theme = useThemeStore()
const keyword = ref('')
const activeId = ref('')
const inputRef = ref(null)

const ICON_SEARCH = `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="11" cy="11" r="7.2" stroke="currentColor" stroke-width="1.9"/><path d="M16.4 16.4L21 21" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"/></svg>`

/** 全部可执行命令，分组只是为了视觉分段 */
const allGroups = computed(() => {
  const groups = [
    {
      title: '页面',
      items: props.navItems.map((n, i) => ({
        id: `nav:${n.value}`,
        type: 'nav',
        value: n.value,
        label: n.label,
        hint: `⌘${i + 1}`
      }))
    },
    {
      title: '主题',
      items: THEME_MODES.map((m) => ({
        id: `theme:${m.value}`,
        type: 'theme',
        value: m.value,
        label: m.label,
        hint: theme.mode === m.value ? '当前' : m.hint
      }))
    }
  ]

  if (props.isAdmin) {
    groups.push({
      title: '管理',
      items: [{ id: 'nav:admin', type: 'nav', value: 'admin', label: '管理后台', hint: '用户 · 审计' }]
    })
  }

  return groups
})

const groups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return allGroups.value
  return allGroups.value
    .map((g) => ({
      ...g,
      items: g.items.filter(
        (it) => it.label.toLowerCase().includes(kw) || String(it.hint).toLowerCase().includes(kw)
      )
    }))
    .filter((g) => g.items.length)
})

const flatItems = computed(() => groups.value.flatMap((g) => g.items))

function close() {
  emit('update:modelValue', false)
}

function pick(item) {
  if (!item) return
  emit('select', { type: item.type, value: item.value })
  close()
}

function onKeydown(e) {
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    return
  }

  if (e.key === 'Enter') {
    e.preventDefault()
    pick(flatItems.value.find((it) => it.id === activeId.value) || flatItems.value[0])
    return
  }

  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    const list = flatItems.value
    if (!list.length) return
    const idx = list.findIndex((it) => it.id === activeId.value)
    const next =
      e.key === 'ArrowDown'
        ? list[(idx + 1 + list.length) % list.length]
        : list[(idx - 1 + list.length) % list.length]
    activeId.value = next.id
  }
}

// 打开时聚焦输入框并重置状态
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    keyword.value = ''
    activeId.value = flatItems.value[0]?.id ?? ''
    await nextTick()
    inputRef.value?.focus()
  }
)

// 过滤后如果选中项被过滤掉，回落到第一条
watch(flatItems, (list) => {
  if (!list.some((it) => it.id === activeId.value)) {
    activeId.value = list[0]?.id ?? ''
  }
})
</script>

<style scoped>
.cmdk-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 14vh;
  background: rgba(3, 5, 12, 0.55);
  backdrop-filter: blur(6px);
}

.cmdk-panel {
  width: 560px;
  max-width: calc(100vw - 32px);
  border-radius: 16px;
  border: 1px solid var(--dcdr-border);
  background: var(--dcdr-surface);
  box-shadow: var(--dcdr-shadow-pop);
  overflow: hidden;
}

.cmdk-input-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  height: 52px;
  border-bottom: 1px solid var(--dcdr-border);
  color: var(--dcdr-text-3);
}

.cmdk-icon {
  display: flex;
  width: 16px;
  height: 16px;
  flex: none;
}

.cmdk-input {
  flex: 1;
  height: 100%;
  font-size: 13.5px;
  color: var(--dcdr-text-1);
}

.cmdk-input::placeholder {
  color: var(--dcdr-text-3);
}

.cmdk-kbd,
.cmdk-foot kbd {
  font-family: var(--dcdr-font-mono);
  font-size: 9.5px;
  line-height: 1;
  padding: 4px 6px;
  border-radius: 5px;
  border: 1px solid var(--dcdr-border);
  background: var(--dcdr-hover);
  color: var(--dcdr-text-3);
}

.cmdk-list {
  max-height: 46vh;
  overflow-y: auto;
  padding: 6px;
}

.cmdk-group {
  padding: 8px 10px 4px;
  font-family: var(--dcdr-font-mono);
  font-size: 9.5px;
  letter-spacing: 1px;
  color: var(--dcdr-text-3);
}

.cmdk-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 9px 10px;
  border-radius: 8px;
  text-align: left;
  color: var(--dcdr-text-1);
}

.cmdk-item.is-active {
  background: var(--dcdr-accent-wash);
  box-shadow: inset 0 0 0 1px rgba(46, 167, 255, 0.32);
}

.cmdk-item-label {
  font-size: 13px;
}

.cmdk-item-hint {
  font-family: var(--dcdr-font-mono);
  font-size: 9.5px;
  color: var(--dcdr-text-3);
}

.cmdk-empty {
  padding: 26px 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--dcdr-text-3);
}

.cmdk-foot {
  display: flex;
  gap: 16px;
  padding: 8px 14px;
  border-top: 1px solid var(--dcdr-border);
  font-size: 10.5px;
  color: var(--dcdr-text-3);
}

.cmdk-foot span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.cmdk-enter-active,
.cmdk-leave-active {
  transition: opacity 140ms var(--dcdr-ease);
}

.cmdk-enter-active .cmdk-panel,
.cmdk-leave-active .cmdk-panel {
  transition: transform 160ms var(--dcdr-ease);
}

.cmdk-enter-from,
.cmdk-leave-to {
  opacity: 0;
}

.cmdk-enter-from .cmdk-panel,
.cmdk-leave-to .cmdk-panel {
  transform: translateY(-8px) scale(0.99);
}
</style>
