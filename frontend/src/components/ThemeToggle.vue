<template>
  <el-dropdown trigger="click" placement="bottom-end" popper-class="dcdr-theme-popper" @command="theme.setMode">
    <button type="button" class="theme-btn" :title="`主题：${theme.label}`" :aria-label="`主题：${theme.label}`">
      <span class="theme-btn-icon" v-html="currentIcon" />
    </button>

    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="m in THEME_MODES" :key="m.value" :command="m.value">
          <span class="theme-opt">
            <span class="theme-opt-main">
              <span class="theme-opt-label">{{ m.label }}</span>
              <span class="theme-opt-hint">{{ m.hint }}</span>
            </span>
            <svg
              v-if="theme.mode === m.value"
              class="theme-opt-check"
              viewBox="0 0 24 24"
              width="14"
              height="14"
              fill="none"
              xmlns="http://www.w3.org/2000/svg"
            >
              <path d="M5 12.5L9.6 17L19 7" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { computed } from 'vue'
import { useThemeStore, THEME_MODES } from '@/stores/theme'

const theme = useThemeStore()

const ICON_SUN = `<svg viewBox="0 0 24 24" width="15" height="15" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="12" cy="12" r="4.2" stroke="currentColor" stroke-width="1.8"/><path d="M12 3V5.4M12 18.6V21M3 12H5.4M18.6 12H21M5.6 5.6L7.3 7.3M16.7 16.7L18.4 18.4M18.4 5.6L16.7 7.3M7.3 16.7L5.6 18.4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>`

const ICON_MOON = `<svg viewBox="0 0 24 24" width="15" height="15" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M20 14.4C18.7 15.1 17.2 15.5 15.6 15.5C11.1 15.5 7.5 11.9 7.5 7.4C7.5 5.8 7.9 4.3 8.6 3C5.4 4.1 3 7.1 3 10.7C3 15.2 6.6 18.8 11.1 18.8C14.7 18.8 17.7 16.4 20 14.4Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/></svg>`

const ICON_AUTO = `<svg viewBox="0 0 24 24" width="15" height="15" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="1.8"/><path d="M12 4C15.9 6.6 15.9 17.4 12 20V4Z" fill="currentColor"/></svg>`

const currentIcon = computed(() => {
  if (theme.mode === 'light') return ICON_SUN
  if (theme.mode === 'dark') return ICON_MOON
  return ICON_AUTO
})
</script>

<style scoped>
.theme-btn {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--dcdr-radius-pill);
  border: 1px solid var(--dcdr-border);
  background: var(--dcdr-hover);
  color: var(--dcdr-text-2);
  transition:
    color var(--dcdr-dur) var(--dcdr-ease),
    border-color var(--dcdr-dur) var(--dcdr-ease),
    background var(--dcdr-dur) var(--dcdr-ease);
}

.theme-btn:hover {
  color: var(--dcdr-accent-soft);
  border-color: rgba(46, 167, 255, 0.45);
}

.theme-btn-icon {
  display: flex;
  width: 15px;
  height: 15px;
}
</style>

<!-- 下拉是 teleport 到 body 的，scoped 够不到，只能用一个专属 class 做全局样式 -->
<style>
.dcdr-theme-popper .theme-opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  min-width: 168px;
}

.dcdr-theme-popper .theme-opt-main {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.dcdr-theme-popper .theme-opt-label {
  font-size: 13px;
  color: var(--dcdr-text-1);
}

.dcdr-theme-popper .theme-opt-hint {
  font-size: 11px;
  color: var(--dcdr-text-3);
}

.dcdr-theme-popper .theme-opt-check {
  color: var(--dcdr-accent);
  flex: none;
}

.dcdr-theme-popper.el-popper {
  border-radius: var(--dcdr-radius-inner);
  border: 1px solid var(--dcdr-border);
  background: var(--dcdr-surface-2);
  box-shadow: var(--dcdr-shadow-pop);
}

.dcdr-theme-popper .el-dropdown-menu {
  background: transparent;
  padding: 4px;
}

.dcdr-theme-popper .el-dropdown-menu__item {
  border-radius: 8px;
  padding: 8px 10px;
  color: var(--dcdr-text-1);
}

.dcdr-theme-popper .el-dropdown-menu__item:not(.is-disabled):focus,
.dcdr-theme-popper .el-dropdown-menu__item:not(.is-disabled):hover {
  background: var(--dcdr-hover);
  color: var(--dcdr-text-1);
}
</style>
