<template>
  <!-- 统一「+」入口(V3.4) —— 对齐 WorkBuddy 的输入框加号: 一个按钮管三类上下文
       (专家 / 知识库 / 技能), 点开在一个面板里勾选, 而不是并排三个下拉框。
       为什么合并: 三个下拉各占 100~140px, 窄屏时输入行的动作按钮会被挤成两行;
       而它们本来就是同一件事 —— "这轮对话要带上什么"。
       选中结果不再留在下拉里, 而是以 chips 显示在输入框上方(见 ChatView 的 picked-chips),
       好处是"这轮到底带了什么"一眼可见, 不必逐个下拉点开确认。 -->
  <div ref="rootEl" class="picker">
    <button type="button" class="picker-btn" :class="{ active: count > 0 }"
            :disabled="disabled" :aria-expanded="open" aria-label="添加上下文"
            :title="count > 0 ? `已选 ${count} 项上下文` : '添加上下文：专家 / 知识库 / 技能'"
            @click.stop="toggle">
      <svg class="plus" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           stroke-width="1.9" stroke-linecap="round">
        <path d="M12 5.5v13M5.5 12h13" />
      </svg>
      <span v-if="count > 0" class="badge">{{ count }}</span>
    </button>

    <div v-if="open" class="pop" role="dialog" aria-label="添加上下文" @click.stop>
      <div class="pop-head">
        <span class="pop-title">添加上下文</span>
        <button type="button" class="pop-close" aria-label="关闭" @click="open = false">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor"
               stroke-width="1.5" stroke-linecap="round">
            <line x1="4" y1="4" x2="12" y2="12" />
            <line x1="12" y1="4" x2="4" y2="12" />
          </svg>
        </button>
      </div>

      <template v-if="!loggedIn">
        <div class="pop-empty">登录后可以使用专家、知识库与技能。</div>
      </template>
      <template v-else>
        <!-- 专家是单选(一轮只能用一个角色), 因此第一项是"不选" -->
        <section class="pop-sec">
          <div class="sec-head">
            <span class="sec-title">专家</span>
            <span class="sec-hint">单选</span>
          </div>
          <div class="sec-list">
            <button type="button" class="opt" :class="{ on: !agentId }" @click="pickAgent('')">
              <span class="opt-name">默认（不使用专家）</span>
              <span v-if="!agentId" class="opt-check">✓</span>
            </button>
            <button v-for="a in agents" :key="a.id" type="button" class="opt"
                    :class="{ on: agentId === a.id }" @click="pickAgent(a.id)">
              <span class="opt-name">{{ a.name }}</span>
              <span v-if="agentId === a.id" class="opt-check">✓</span>
            </button>
            <div v-if="!agents.length" class="sec-empty">{{ loading ? '加载中…' : '还没有创建专家' }}</div>
          </div>
        </section>

        <section class="pop-sec">
          <div class="sec-head">
            <span class="sec-title">知识库</span>
            <span class="sec-hint">多选 · {{ kbIds.length }} 个</span>
          </div>
          <div class="sec-list">
            <button v-for="kb in knowledgeBases" :key="kb.id" type="button" class="opt"
                    :class="{ on: kbIds.includes(kb.id) }" @click="toggleKb(kb.id)">
              <span class="opt-name">{{ kb.name }}</span>
              <span v-if="kbIds.includes(kb.id)" class="opt-check">✓</span>
            </button>
            <div v-if="!knowledgeBases.length" class="sec-empty">
              {{ loading ? '加载中…' : '没有可选的知识库' }}
            </div>
          </div>
        </section>

        <section class="pop-sec">
          <div class="sec-head">
            <span class="sec-title">技能</span>
            <span class="sec-hint">多选 · {{ skillNames.length }} 个</span>
          </div>
          <div class="sec-list">
            <button v-for="s in skills" :key="s.name" type="button" class="opt"
                    :class="{ on: skillNames.includes(s.name) }" @click="toggleSkill(s.name)">
              <span class="opt-name">{{ s.displayName || s.name }}</span>
              <span v-if="skillNames.includes(s.name)" class="opt-check">✓</span>
            </button>
            <div v-if="!skills.length" class="sec-empty">{{ loading ? '加载中…' : '没有已启用的技能' }}</div>
          </div>
        </section>

        <div class="pop-foot">
          <span class="pop-tip">知识库每轮自动检索；指定技能会直接把正文加入本轮上下文。</span>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps({
  agentId: { type: String, default: '' },
  kbIds: { type: Array, default: () => [] },
  skillNames: { type: Array, default: () => [] },
  agents: { type: Array, default: () => [] },
  knowledgeBases: { type: Array, default: () => [] },
  skills: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  loggedIn: { type: Boolean, default: true }
})
const emit = defineEmits(['update:agentId', 'update:kbIds', 'update:skillNames', 'open'])

const rootEl = ref(null)
const open = ref(false)

const count = computed(() =>
  (props.agentId ? 1 : 0) + props.kbIds.length + props.skillNames.length)

function toggle() {
  if (props.disabled) return
  open.value = !open.value
}

/** 首次展开才去拉三份列表: 大多数人一整天都不点它, 不必每次进对话都查三张表 */
watch(open, (v) => { if (v) emit('open') })

/** 外点关闭: 用 mousedown 判定"按下那一刻在哪", 避免按住面板内拖到外面松手时被误关 */
function onDocMouseDown(e) {
  if (!open.value) return
  const el = rootEl.value
  if (el && e.target instanceof Node && el.contains(e.target)) return
  open.value = false
}
function onKeyDown(e) {
  if (e.key === 'Escape' && open.value) open.value = false
}
onMounted(() => {
  document.addEventListener('mousedown', onDocMouseDown)
  document.addEventListener('keydown', onKeyDown)
})
onUnmounted(() => {
  document.removeEventListener('mousedown', onDocMouseDown)
  document.removeEventListener('keydown', onKeyDown)
})

function pickAgent(id) {
  emit('update:agentId', id)
}
/** 多选项用"整份新数组"回传, 而不是原地 push/splice —— 父组件是 ref, 换引用才会触发依赖更新 */
function toggleKb(id) {
  const next = props.kbIds.includes(id) ? props.kbIds.filter((x) => x !== id) : [...props.kbIds, id]
  emit('update:kbIds', next)
}
function toggleSkill(name) {
  const next = props.skillNames.includes(name)
    ? props.skillNames.filter((x) => x !== name)
    : [...props.skillNames, name]
  emit('update:skillNames', next)
}
</script>

<style scoped>
.picker { position: relative; flex: none; display: inline-flex; }

.picker-btn {
  position: relative; display: inline-flex; align-items: center; justify-content: center;
  width: 36px; height: 36px; padding: 0; border: none; border-radius: 10px; cursor: pointer;
  color: var(--dcdr-text-2); background: var(--dcdr-bg-subtle); transition: all 0.2s ease;
}
.picker-btn:hover:not(:disabled) { color: var(--dcdr-text-1); background: var(--dcdr-hover); }
.picker-btn.active { color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }
.picker-btn:disabled { opacity: 0.45; cursor: not-allowed; }
.plus { width: 18px; height: 18px; }
.badge {
  position: absolute; top: -2px; right: -2px; min-width: 15px; height: 15px; padding: 0 4px;
  border-radius: 999px; background: var(--dcdr-accent); color: #fff;
  font-size: 9.5px; line-height: 15px; text-align: center; font-family: var(--dcdr-font-mono);
}

/* 面板: 向上展开、向左生长(加号在输入行最左, 往右展开会顶到发送键) */
.pop {
  position: absolute; bottom: calc(100% + 8px); left: 0; z-index: 60;
  width: 300px; padding: 14px; border-radius: 16px;
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border-strong);
  box-shadow: var(--dcdr-shadow-pop);
  color: var(--dcdr-text-1); font-family: var(--dcdr-font-sans);
  font-size: 12px; line-height: 1.5; text-align: left;
}
.pop-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.pop-title { flex: 1; font-size: 12px; line-height: 20px; color: var(--dcdr-text-1); }
.pop-close {
  flex: none; display: inline-flex; align-items: center; justify-content: center;
  width: 16px; height: 16px; padding: 0; border: none; border-radius: 4px;
  background: transparent; color: var(--dcdr-text-3); cursor: pointer; transition: color 0.15s ease;
}
.pop-close:hover { color: var(--dcdr-text-1); }

.pop-sec { margin-bottom: 10px; }
.sec-head { display: flex; align-items: baseline; gap: 6px; margin-bottom: 4px; }
.sec-title { font-size: 11px; letter-spacing: 0.3px; color: var(--dcdr-text-3); }
.sec-hint { font-size: 10px; color: var(--dcdr-text-3); opacity: 0.75; }
/* 三个分组各有上限: 技能动辄十几个, 不限高会把面板顶出屏幕 */
.sec-list { display: flex; flex-direction: column; gap: 1px; max-height: 148px; overflow-y: auto; }
.sec-empty { padding: 6px 8px; font-size: 11px; color: var(--dcdr-text-3); }

.opt {
  display: flex; align-items: center; gap: 8px; width: 100%;
  padding: 5px 8px; border: none; border-radius: 7px; cursor: pointer;
  background: transparent; color: var(--dcdr-text-1); font-size: 12px;
  font-family: inherit; text-align: left; transition: background 0.15s ease;
}
.opt:hover { background: var(--dcdr-hover); }
.opt.on { background: var(--dcdr-accent-wash); color: var(--dcdr-accent-soft); }
.opt-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.opt-check { flex: none; font-size: 11px; color: var(--dcdr-accent); }

.pop-empty {
  padding: 10px 12px; border-radius: 10px; font-size: 11.5px; line-height: 1.6;
  color: var(--dcdr-text-3); background: var(--dcdr-surface-faint); border: 1px dashed var(--dcdr-border);
}
.pop-foot { padding-top: 8px; border-top: 1px solid var(--dcdr-border); }
.pop-tip { font-size: 10.5px; line-height: 1.5; color: var(--dcdr-text-3); }
</style>
