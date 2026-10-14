<template>
  <div class="hub">
    <!-- 二级导航: 把同类页面收在同一个顶栏入口下。
         顶栏只留"用户此刻想干什么"(对话/创作/专家/自动化…), 具体到哪一页交给这里 -->
    <nav class="hub-nav">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="hub-tab"
        :class="{ active: tab === t.key }"
        :title="t.hint"
        @click="emit('update:tab', t.key)"
      >{{ t.label }}</button>
    </nav>

    <div class="hub-body">
      <!-- 直接挂载原有视图, 不为容器改它们的布局:
           它们的根元素都是 flex:1(本就适配 app-main 这个 flex 行容器), 换个父级依然成立;
           一旦在这里另加一层尺寸约束, 各页面自己的滚动行为就会分别出问题 -->
      <slot />
    </div>
  </div>
</template>

<script setup>
/**
 * 二级页容器的外壳 —— 只负责"一排页签 + 一个填满剩余高度的内容区"。
 * <p>
 * 抽出来是因为它已经有三个使用者(专家 / 自动化 / 运维)。三份拷贝必然漂移:
 * 早晚会有一个容器忘了 min-height:0, 表现为"某一页滚动失效"这种只有肉眼能发现的问题。
 */
defineProps({
  /** [{ key, label, hint }]; hint 作为 title 提示, 说明这一页是干什么的 */
  tabs: { type: Array, required: true },
  /** 当前二级页 key */
  tab: { type: String, required: true }
})
const emit = defineEmits(['update:tab'])
</script>

<style scoped>
.hub { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-width: 0; }

.hub-nav {
  flex: none;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 7px 24px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
}
.hub-tab {
  padding: 6px 14px;
  font-size: 13px;
  color: #606266;
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  user-select: none;
  transition: background 0.2s, color 0.2s;
}
.hub-tab:hover { background: #f2f3f5; color: #667eea; }
.hub-tab.active { background: #eef2ff; color: #667eea; font-weight: 500; }

/* 子页要在这里"填满并自己滚": overflow:hidden + min-height:0 缺一个, 长列表就会把外层撑开 */
.hub-body { flex: 1; display: flex; overflow: hidden; min-height: 0; min-width: 0; }
</style>
