<template>
  <div
    v-if="items.length > 1"
    class="qnav-dock"
    @mouseenter="enter"
    @mouseleave="leave"
  >
    <transition name="qnav">
      <nav
        v-if="shown"
        class="qnav"
        aria-label="本会话问题导航"
        @mouseenter="enter"
        @mouseleave="leave"
      >
        <button
          v-for="(item, i) in items"
          :key="item.key"
          type="button"
          class="qnav-item"
          :class="{ active: i === active }"
          :title="item.text"
          @click="$emit('jump', item)"
        >{{ item.text }}</button>
      </nav>
    </transition>

    <button
      type="button"
      class="qnav-toggle"
      :class="{ on: pinned }"
      :title="pinned ? '收起问题导航' : '展开问题导航（鼠标悬浮也可展开）'"
      :aria-expanded="shown"
      @click="$emit('toggle')"
    >
      <svg v-if="pinned" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg>
      <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M4.5 7h15M4.5 12h15M4.5 17h9"/></svg>
    </button>
  </div>
</template>

<script setup>
/**
 * 会话内问题导航（右侧大纲），<b>悬浮展开</b>。
 *
 * <p><b>为什么悬浮而不是占位</b>：占位方案（展开时给正文让出 200px）会让正文列在开关之间
 * 左右挪动，而且收起时那条预留带就一直空着。悬浮方案不改变任何布局：
 * 面板是一层浮在正文之上的浮层，看完即走。
 *
 * <p><b>两种打开方式</b>：鼠标悬浮图标临时展开（移开就收）；点图标则"钉住"，方便连续跳几次。
 *
 * <p><b>只列用户的问题</b>：助手回答可能很长，列进来就不再是"目录"而是"第二份正文"；
 * 提问卡片里用户点选的回答也不算问题（它已被折进卡片），由父组件过滤。
 */
import { computed, onUnmounted, ref } from 'vue'

const props = defineProps({
  /** [{key, text}] —— key 用于父组件定位消息元素 */
  items: { type: Array, default: () => [] },
  /** 当前所处的问题下标（由父组件按滚动位置算） */
  active: { type: Number, default: 0 },
  /** 是否被钉住（用户点过图标）；悬浮是临时态，不改变这个值 */
  pinned: { type: Boolean, default: false }
})
defineEmits(['jump', 'toggle'])

const hover = ref(false)
/** 图标与面板之间有 8px 空隙, 移过去会先触发一次 leave —— 用延时收起把这一小段路铺平 */
let closeTimer = null

function enter() {
  if (closeTimer) {
    clearTimeout(closeTimer)
    closeTimer = null
  }
  hover.value = true
}

function leave() {
  if (closeTimer) clearTimeout(closeTimer)
  closeTimer = setTimeout(() => {
    hover.value = false
    closeTimer = null
  }, 160)
}

const shown = computed(() => props.pinned || hover.value)

onUnmounted(() => { if (closeTimer) clearTimeout(closeTimer) })
</script>

<style scoped>
/* 停靠在消息区右侧的竖中线上: 按钮在此, 面板以它为锚向左浮出, 不参与布局 */
.qnav-dock {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 6;
}

.qnav-toggle {
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--dcdr-border);
  border-radius: 8px;
  background: var(--dcdr-surface);
  color: var(--dcdr-text-3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  opacity: 0.7;
  transition: opacity 0.15s ease, color 0.15s ease, border-color 0.15s ease, background 0.15s ease;
}
.qnav-toggle:hover { opacity: 1; color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); }
.qnav-toggle.on { opacity: 1; color: var(--dcdr-accent); border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); }
.qnav-toggle svg { width: 14px; height: 14px; }

/* 浮层: 玻璃底 + 描边, 盖在正文上也要读得清 */
.qnav {
  position: absolute;
  right: 34px;
  top: 50%;
  transform: translateY(-50%);
  width: 200px;
  max-height: 60vh;
  overflow-y: auto;
  padding: 10px 10px 10px 8px;
  background: var(--dcdr-surface-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--dcdr-border);
  border-radius: 12px;
  box-shadow: var(--dcdr-shadow-card);
}
.qnav::-webkit-scrollbar { width: 3px; }
.qnav::-webkit-scrollbar-thumb { background: var(--dcdr-border); border-radius: 3px; }

/* 每一项自带左边界: 叠起来就是那条竖线, 当前项把这一段染成强调色 */
.qnav-item {
  display: block;
  width: 100%;
  text-align: right;
  padding: 4px 0 4px 10px;
  border: none;
  border-left: 1px solid var(--dcdr-border);
  background: transparent;
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  color: var(--dcdr-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease;
}
.qnav-item:hover { color: var(--dcdr-text-1); border-left-color: var(--dcdr-border-strong); }
.qnav-item.active { color: var(--dcdr-text-1); font-weight: 500; border-left-color: var(--dcdr-accent); }

.qnav-enter-active, .qnav-leave-active { transition: opacity 0.15s ease, transform 0.15s ease; }
.qnav-enter-from, .qnav-leave-to { opacity: 0; transform: translateY(-50%) translateX(8px); }
</style>
