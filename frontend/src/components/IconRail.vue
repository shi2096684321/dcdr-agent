<template>
  <nav class="rail" aria-label="主导航">
    <button
      v-for="item in items"
      :key="item.value"
      type="button"
      class="rail-item"
      :class="{ 'is-active': modelValue === item.value }"
      :title="item.hint"
      :aria-label="item.label"
      :aria-current="modelValue === item.value ? 'page' : undefined"
      @click="emit('update:modelValue', item.value)"
    >
      <span class="rail-icon" v-html="item.icon" />
    </button>
  </nav>
</template>

<script setup>
defineProps({
  /** 当前一级入口（与 App.vue 的 mode 同值） */
  modelValue: { type: String, default: 'chat' },
  /** [{ value, label, hint, icon }]，默认取 nav.js 里的全套 */
  items: { type: Array, required: true }
})

const emit = defineEmits(['update:modelValue'])
</script>

<style scoped>
.rail {
  flex: none;
  width: var(--dcdr-rail-w);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding-top: 18px;
  background: var(--dcdr-rail-bg);
  border-right: 1px solid var(--dcdr-border);
  z-index: 3;
}

.rail-item {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  border: 1px solid transparent;
  color: var(--dcdr-text-2);
  transition:
    background var(--dcdr-dur) var(--dcdr-ease),
    color var(--dcdr-dur) var(--dcdr-ease),
    border-color var(--dcdr-dur) var(--dcdr-ease);
}

.rail-item:hover {
  background: var(--dcdr-hover);
  color: var(--dcdr-text-1);
}

.rail-item.is-active {
  background: var(--dcdr-accent-wash);
  border-color: rgba(46, 167, 255, 0.45);
  color: var(--dcdr-accent-soft);
  box-shadow: var(--dcdr-glow-accent);
}

.rail-icon {
  display: flex;
  width: 19px;
  height: 19px;
}
</style>
