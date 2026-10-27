<template>
  <div v-if="sessionId" class="wc-bar">
    <button
      class="wc-btn"
      :class="{ active: open, idle: !activeCount }"
      :title="activeCount ? `本会话有 ${activeCount} 个文件待撤销` : '本会话还没有文件改动'"
      @click="toggle"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M4.5 5.5h15M4.5 12h9.5M4.5 18.5h6.5" />
        <path d="M15.5 14.5l2.5 2.5 4-4.5" />
      </svg>
      <span>改动</span>
      <span v-if="activeCount" class="wc-count">{{ activeCount }}</span>
    </button>

    <div v-if="open" class="wc-mask" @click.self="close">
      <aside class="wc-panel">
        <header class="wc-head">
          <div class="wc-head-text">
            <h3>本会话的文件改动</h3>
            <p class="wc-sub">
              <template v-if="loading">读取中…</template>
              <template v-else-if="!files.length">还没有任何文件被改动</template>
              <template v-else-if="!activeCount">全部 {{ files.length }} 处改动都已撤销</template>
              <template v-else>{{ activeCount }} 个文件待撤销 · 共 {{ files.length }} 个文件被改过</template>
            </p>
          </div>
          <button class="wc-close" title="关闭" @click="close">×</button>
        </header>

        <div v-if="files.length" class="wc-actions">
          <label class="wc-toggle">
            <input v-model="showReverted" type="checkbox" /> 显示已撤销
          </label>
          <div class="wc-seg">
            <button :class="{ on: viewMode === 'inline' }" title="单列：改动上下相邻" @click="viewMode = 'inline'">内联</button>
            <button :class="{ on: viewMode === 'side' }" title="左右对照：左边改前，右边改后" @click="viewMode = 'side'">并排</button>
          </div>
          <span class="wc-spacer"></span>
          <button class="wc-ghost" :disabled="loading || busy" @click="load">刷新</button>
          <button class="wc-danger" :disabled="!activeCount || busy" @click="clearAll">清空本会话改动</button>
        </div>

        <div class="wc-list">
          <div v-for="f in visibleFiles" :key="f.path" class="wc-file" :class="{ done: !f.active }">
            <div class="wc-file-head" @click="toggleExpand(f.path)">
              <span class="wc-chev">{{ expanded.has(f.path) ? '▾' : '▸' }}</span>
              <span class="wc-type" :class="'t-' + f.changeType">{{ typeLabel(f.changeType) }}</span>
              <span class="wc-path mono" :title="f.path">{{ f.path }}</span>
              <span class="wc-stat">
                <span v-if="f.added" class="plus">+{{ f.added }}</span>
                <span v-if="f.removed" class="minus">−{{ f.removed }}</span>
              </span>
              <button v-if="f.active" class="wc-undo" :disabled="busy" title="把这个文件退回本会话开始前的状态" @click.stop="undoOne(f)">撤销</button>
              <span v-else class="wc-badge">已撤销</span>
            </div>

            <template v-if="expanded.has(f.path)">
              <p v-if="f.note" class="wc-note">{{ f.note }}</p>

              <div v-if="f.diff.length" class="wc-diff-wrap">
                <!-- 内联：单列，改动与上下文上下相邻 -->
                <table v-if="viewMode === 'inline'" class="wc-tbl">
                  <tbody>
                    <tr v-for="(l, i) in f.diff" :key="i" :class="'r-' + l.type">
                      <td v-if="l.type === 'skip'" colspan="3" class="skip-cell" @click="expandAll(f)">
                        ⋯ 第 {{ l.oldNo }}–{{ l.oldNo + l.skipped - 1 }} 行未修改（{{ l.skipped }} 行）· 点此展开
                      </td>
                      <template v-else>
                        <td class="ln">{{ l.type === 'add' ? '' : l.oldNo }}</td>
                        <td class="ln">{{ l.type === 'del' ? '' : l.newNo }}</td>
                        <td class="code"><span class="sign">{{ signOf(l) }}</span>{{ l.text }}</td>
                      </template>
                    </tr>
                  </tbody>
                </table>

                <!-- 并排：左改前 / 右改后，删增按行配对 -->
                <table v-else class="wc-tbl side">
                  <tbody>
                    <tr v-for="(r, i) in sideRows[f.path] || []" :key="i">
                      <td v-if="r.skip" colspan="4" class="skip-cell" @click="expandAll(f)">
                        ⋯ 第 {{ r.skip.oldNo }}–{{ r.skip.oldNo + r.skip.skipped - 1 }} 行未修改（{{ r.skip.skipped }} 行）· 点此展开
                      </td>
                      <template v-else>
                        <td class="ln">{{ r.left ? r.left.no : '' }}</td>
                        <td class="code" :class="{ 'c-del': r.left && r.left.type === 'del' }">
                          <span v-if="r.left" class="sign">{{ r.left.type === 'del' ? '-' : ' ' }}</span>{{ r.left ? r.left.text : '' }}
                        </td>
                        <td class="ln ln-r">{{ r.right ? r.right.no : '' }}</td>
                        <td class="code" :class="{ 'c-add': r.right && r.right.type === 'add' }">
                          <span v-if="r.right" class="sign">{{ r.right.type === 'add' ? '+' : ' ' }}</span>{{ r.right ? r.right.text : '' }}
                        </td>
                      </template>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div class="wc-diff-foot">
                <span v-if="f.diffTruncated" class="wc-warn">
                  只显示了前 {{ expandedFull.has(f.path) ? 2000 : 200 }} 行
                </span>
                <span class="wc-spacer"></span>
                <button class="wc-link" :disabled="expanding.has(f.path)" @click="toggleFull(f)">
                  {{ expanding.has(f.path) ? '读取中…'
                    : expandedFull.has(f.path) ? '收起（只看改动附近）'
                    : '展开全文' }}
                </button>
              </div>
            </template>

            <p v-else-if="expanded.has(f.path) && !f.diff.length && !f.note" class="wc-note">内容没有差异</p>
          </div>

          <div v-if="!visibleFiles.length" class="wc-empty">
            {{ files.length ? '没有待撤销的改动，勾选「显示已撤销」可以看历史' : '让模型改几个文件，这里会显示逐行差异' }}
          </div>
        </div>

        <footer class="wc-foot">
          左边是改前（回收区快照），右边是磁盘当前内容，你在编辑器里又改过的地方也能看出来。
          撤销只回滚文件、不动对话；被外部改过的文件会跳过并单独提示，不会覆盖你的改动。
        </footer>
      </aside>
    </div>
  </div>
</template>

<script setup>
/**
 * 会话文件变更面板(V3.3 F-49)。
 *
 * 为什么要有它：能改本机文件的 agent 若不能让人一眼看清"改了什么"，用户只能靠猜。
 *
 * diff 的呈现照着主流产品的做法来（GitHub / VS Code 那一套）：
 * - 两侧行号 + `+`/`-` 前缀，改动行的颜色与底色双重区分（只靠颜色对色弱不友好）；
 * - 块之间的未改动区域显式标成"第 X–Y 行未修改"，可点击展开 —— 不做这一步，
 *   界面上两个改动块直接挨着，看起来像文件只有这么长；
 * - 内联 / 并排可切换：并排是最直观的"改前 vs 改后"；
 * - 默认只给改动前后 3 行，要看全貌点「展开全文」按需拉取，避免一次塞几千行进响应。
 *
 * 数据全部来自后端聚合（journal → 文件 + 结构化 diff 行）。前端拿不到磁盘，
 * 也没法读回收区快照，所以这里只负责渲染，不做任何对比计算。
 */
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSessionChanges, undoSessionPath, rewindAll } from '@/api'

const props = defineProps({
  sessionId: { type: String, default: '' }
})

/** 变更被撤销后通知父组件（用于刷新消息上的撤销按钮状态） */
const emit = defineEmits(['changed'])

/** 默认只给 3 行上下文，与 git / GitHub 一致 */
const DEFAULT_CONTEXT = 3

const open = ref(false)
const loading = ref(false)
const busy = ref(false)
const files = ref([])
const activeCount = ref(0)
const showReverted = ref(false)
const expanded = ref(new Set())
/** 已展开全文的文件路径（之后收起时重新按默认上下文请求） */
const expandedFull = ref(new Set())
const expanding = ref(new Set())
const viewMode = ref('inline')

const visibleFiles = computed(() =>
  showReverted.value ? files.value : files.value.filter((f) => f.active)
)

/** 并排视图的行数据：删增按行配对，右侧不足时留空 */
const sideRows = computed(() => {
  const map = {}
  for (const f of files.value) map[f.path] = buildSideRows(f.diff)
  return map
})

const TYPE_LABEL = { added: 'A', modified: 'M', deleted: 'D', unchanged: '=' }
const typeLabel = (t) => TYPE_LABEL[t] || '?'
const signOf = (l) => (l.type === 'del' ? '-' : l.type === 'add' ? '+' : ' ')

function buildSideRows(lines) {
  const rows = []
  let i = 0
  while (i < lines.length) {
    const l = lines[i]
    if (l.type === 'skip') {
      rows.push({ skip: l })
      i++
      continue
    }
    if (l.type === 'equal') {
      rows.push({
        left: { no: l.oldNo, text: l.text, type: 'equal' },
        right: { no: l.newNo, text: l.text, type: 'equal' }
      })
      i++
      continue
    }
    // 连续的删/增作为一块逐行左右配对：左边第 1 个删除对右边第 1 个新增
    const dels = []
    const adds = []
    while (i < lines.length && (lines[i].type === 'del' || lines[i].type === 'add')) {
      if (lines[i].type === 'del') dels.push(lines[i])
      else adds.push(lines[i])
      i++
    }
    const n = Math.max(dels.length, adds.length)
    for (let k = 0; k < n; k++) {
      rows.push({
        left: dels[k] ? { no: dels[k].oldNo, text: dels[k].text, type: 'del' } : null,
        right: adds[k] ? { no: adds[k].newNo, text: adds[k].text, type: 'add' } : null
      })
    }
  }
  return rows
}

function toggleExpand(path) {
  // Set 是引用类型，必须换一个新实例才会触发响应式更新
  const next = new Set(expanded.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  expanded.value = next
}

async function load() {
  if (!props.sessionId) {
    files.value = []
    activeCount.value = 0
    return
  }
  loading.value = true
  try {
    const res = await getSessionChanges(props.sessionId)
    files.value = res?.files || []
    activeCount.value = res?.activeCount || 0
    // 列表刷新后内容已回退成默认上下文，"已展开全文"的标记要一起清掉，
    // 否则按钮文案（收起）和实际内容（已收起）会对不上
    expandedFull.value = new Set()
  } catch (e) {
    // 打开时给提示（用户正等着看），收起状态下静默 —— 辅助信息不该打断对话
    if (open.value) ElMessage.error(e?.message || '读取变更失败')
    files.value = []
    activeCount.value = 0
  } finally {
    loading.value = false
  }
}

/** 展开 / 收起全文：单文件按需拉一次，不把整个会话的完整内容都塞给前端 */
async function toggleFull(f) {
  const wantFull = !expandedFull.value.has(f.path)
  const nextExpanding = new Set(expanding.value)
  nextExpanding.add(f.path)
  expanding.value = nextExpanding
  try {
    const res = await getSessionChanges(props.sessionId, {
      path: f.path,
      context: wantFull ? 'full' : String(DEFAULT_CONTEXT)
    })
    const one = res?.files?.[0]
    if (one) {
      const idx = files.value.findIndex((x) => x.path === f.path)
      if (idx >= 0) files.value[idx] = one
    }
    const nextFull = new Set(expandedFull.value)
    if (wantFull) nextFull.add(f.path)
    else nextFull.delete(f.path)
    expandedFull.value = nextFull
  } catch (e) {
    ElMessage.error(e?.message || '读取完整内容失败')
  } finally {
    const done = new Set(expanding.value)
    done.delete(f.path)
    expanding.value = done
  }
}

/** 点折叠条 = 展开全文（比"只多显示 10 行"更符合意图） */
function expandAll(f) {
  if (!expandedFull.value.has(f.path)) toggleFull(f)
}

function toggle() {
  open.value = !open.value
  if (open.value) load()
}

function close() {
  open.value = false
}

function reportResult(rep, okVerb = '恢复') {
  const ok = rep?.reverted?.length || 0
  const skipped = rep?.skipped || []
  if (skipped.length) {
    // 跳过必须说出来：静默跳过等于让用户以为"撤干净了"
    ElMessage.warning(
      `${ok} 个文件已${okVerb}，${skipped.length} 个被跳过：${skipped[0].path}（${skipped[0].reason}）`
    )
  } else if (ok) {
    ElMessage.success(`已${okVerb} ${ok} 个文件`)
  } else {
    ElMessage.info('没有需要处理的内容')
  }
}

async function undoOne(f) {
  try {
    await ElMessageBox.confirm(
      `把「${f.path}」退回本会话开始前的状态？对话不会被删除。`,
      '撤销这个文件的改动',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  busy.value = true
  try {
    reportResult(await undoSessionPath(props.sessionId, f.path))
    emit('changed')
  } catch (e) {
    ElMessage.error(e?.message || '撤销失败')
  } finally {
    busy.value = false
    await load()
  }
}

async function clearAll() {
  try {
    await ElMessageBox.confirm(
      `把本会话改过的 ${activeCount.value} 个文件全部退回会话开始前的状态？\n\n对话记录会保留，只回滚文件；删除掉的文件会从回收区恢复。`,
      '清空本会话改动',
      { type: 'warning', confirmButtonText: '全部撤销', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  busy.value = true
  try {
    reportResult(await rewindAll(props.sessionId, 'FILES'))
    emit('changed')
  } catch (e) {
    ElMessage.error(e?.message || '清空失败')
  } finally {
    busy.value = false
    await load()
  }
}

onMounted(load)

watch(
  () => props.sessionId,
  () => {
    // 切会话等于换了一整套改动记录，旧列表必须清掉，面板也要收起来
    open.value = false
    expanded.value = new Set()
    expandedFull.value = new Set()
    load()
  }
)

// 供父组件在流式结束后刷新（模型刚改完文件时，计数要跟着变）
defineExpose({ refresh: load })
</script>

<style scoped>
/* 配色一律走 theme.css 的设计令牌，不写 hex 也不用 fallback ——
   变量名写错会立刻暴露成明显错误的观感，而不是悄悄退化成看不清的深灰。 */

.wc-bar { display: inline-flex; align-items: center; }
.wc-btn {
  display: inline-flex; align-items: center; gap: 6px; height: 28px; padding: 0 10px;
  border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-pill);
  background: var(--dcdr-surface-faint);
  color: var(--dcdr-text-2); font-size: 12px; font-weight: 500; cursor: pointer;
  transition: color var(--dcdr-dur) var(--dcdr-ease), border-color var(--dcdr-dur) var(--dcdr-ease);
}
.wc-btn:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); background: var(--dcdr-hover); }
.wc-btn.active { color: var(--dcdr-text-1); border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); }
.wc-btn.idle { color: var(--dcdr-text-3); }
.wc-btn svg { width: 14px; height: 14px; }
.wc-count {
  min-width: 16px; padding: 0 5px; border-radius: var(--dcdr-radius-pill);
  background: var(--dcdr-accent); color: var(--dcdr-text-inverse);
  font-size: 11px; font-weight: 600; text-align: center; line-height: 16px;
}

/* 遮罩 + 右侧抽屉：diff 是宽内容，横着比竖着更难受，所以面板给到 760 */
.wc-mask { position: fixed; inset: 0; background: rgba(3, 5, 12, .5); z-index: 3000; }
.wc-panel {
  position: absolute; top: 0; right: 0; bottom: 0; width: 760px; max-width: 94vw;
  display: flex; flex-direction: column;
  background: var(--dcdr-surface);
  border-left: 1px solid var(--dcdr-border);
  box-shadow: var(--dcdr-shadow-pop);
}

.wc-head {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 16px 18px 12px; border-bottom: 1px solid var(--dcdr-border);
}
.wc-head-text { flex: 1; min-width: 0; }
.wc-head h3 { margin: 0; font-size: 15px; font-weight: 600; color: var(--dcdr-text-1); }
.wc-sub { margin: 4px 0 0; font-size: 12px; color: var(--dcdr-text-2); }
.wc-close {
  border: none; background: transparent; color: var(--dcdr-text-3);
  font-size: 20px; line-height: 1; cursor: pointer; padding: 0 2px;
}
.wc-close:hover { color: var(--dcdr-text-1); }

.wc-actions {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 18px; border-bottom: 1px solid var(--dcdr-border);
}
.wc-spacer { flex: 1; }
.wc-toggle { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--dcdr-text-2); cursor: pointer; }
.wc-toggle input { accent-color: var(--dcdr-accent); }

/* 内联 / 并排 分段开关 */
.wc-seg { display: inline-flex; border: 1px solid var(--dcdr-border); border-radius: 6px; overflow: hidden; }
.wc-seg button {
  height: 24px; padding: 0 10px; border: none; cursor: pointer; font-size: 12px;
  background: transparent; color: var(--dcdr-text-2);
}
.wc-seg button + button { border-left: 1px solid var(--dcdr-border); }
.wc-seg button.on { background: var(--dcdr-accent-wash); color: var(--dcdr-accent); font-weight: 600; }
.wc-seg button:hover:not(.on) { background: var(--dcdr-hover); color: var(--dcdr-text-1); }

.wc-ghost, .wc-danger {
  height: 26px; padding: 0 10px; border-radius: 6px; font-size: 12px; cursor: pointer;
  background: transparent; border: 1px solid var(--dcdr-border); color: var(--dcdr-text-2);
}
.wc-ghost:hover:not(:disabled) { color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); background: var(--dcdr-hover); }
.wc-danger { color: var(--dcdr-danger); border-color: var(--dcdr-border-strong); }
.wc-danger:hover:not(:disabled) { background: var(--dcdr-hover); }
.wc-ghost:disabled, .wc-danger:disabled { opacity: .45; cursor: default; }

.wc-list { flex: 1; overflow: auto; padding: 6px 0; }
.wc-file { border-bottom: 1px solid var(--dcdr-border); }
.wc-file.done { opacity: .55; }
.wc-file-head {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 18px; cursor: pointer;
}
.wc-file-head:hover { background: var(--dcdr-hover); }
.wc-chev { width: 10px; color: var(--dcdr-text-3); font-size: 10px; flex: none; }
.wc-type {
  width: 16px; flex: none; text-align: center;
  font-family: var(--dcdr-font-mono); font-size: 11px; font-weight: 700;
  color: var(--dcdr-text-2);
}
.wc-type.t-added { color: var(--dcdr-success); }
.wc-type.t-modified { color: var(--dcdr-accent); }
.wc-type.t-deleted { color: var(--dcdr-danger); }
.wc-path {
  flex: 1; min-width: 0; font-size: 12.5px; color: var(--dcdr-text-1);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.wc-stat { display: inline-flex; gap: 6px; font-family: var(--dcdr-font-mono); font-size: 11px; flex: none; }
.wc-stat .plus { color: var(--dcdr-success); }
.wc-stat .minus { color: var(--dcdr-danger); }
.wc-undo {
  flex: none; height: 22px; padding: 0 8px; border-radius: 5px; cursor: pointer;
  font-size: 11.5px; background: transparent;
  border: 1px solid var(--dcdr-border-strong); color: var(--dcdr-text-2);
}
.wc-undo:hover:not(:disabled) { color: var(--dcdr-accent); border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); }
.wc-undo:disabled { opacity: .45; cursor: default; }
.wc-badge { flex: none; font-size: 11px; color: var(--dcdr-text-3); }

.wc-note { margin: 0; padding: 0 18px 10px 44px; font-size: 11.5px; color: var(--dcdr-text-3); }
.wc-empty { padding: 24px 18px; font-size: 12px; color: var(--dcdr-text-3); text-align: center; }

/* ===== diff 表 ===== */
.wc-diff-wrap {
  max-height: 440px; overflow: auto;
  background: var(--dcdr-code-bg);
  border-top: 1px solid var(--dcdr-code-border);
}
.wc-tbl {
  border-collapse: collapse; min-width: 100%;
  font-family: var(--dcdr-font-mono); font-size: 11.5px; line-height: 1.6;
  color: var(--dcdr-code-fg);
}
.wc-tbl td { padding: 0 8px; white-space: pre; vertical-align: top; }
.wc-tbl .ln {
  width: 1%; color: var(--dcdr-code-dim); text-align: right;
  user-select: none; white-space: nowrap;
  border-right: 1px solid var(--dcdr-code-border);
  padding: 0 6px;
}
/* 并排视图右半区的行号再补一条左边框，两侧分界更清楚 */
.wc-tbl .ln-r { border-left: 1px solid var(--dcdr-code-border); }
.wc-tbl .sign { display: inline-block; width: 1.1em; color: var(--dcdr-code-dim); }
.wc-tbl .code { padding-left: 2px; }

/* 正负行：颜色 + 底色双重区分，不只靠颜色 */
.wc-tbl .r-add .code,
.wc-tbl .code.c-add {
  color: var(--dcdr-success);
  background: color-mix(in srgb, var(--dcdr-success) 12%, transparent);
}
.wc-tbl .r-del .code,
.wc-tbl .code.c-del {
  color: var(--dcdr-danger);
  background: color-mix(in srgb, var(--dcdr-danger) 12%, transparent);
}
.wc-tbl .r-add .sign, .wc-tbl .code.c-add .sign { color: var(--dcdr-success); }
.wc-tbl .r-del .sign, .wc-tbl .code.c-del .sign { color: var(--dcdr-danger); }

/* 折叠条：一眼看出"这里还有内容没显示"，且可点开 */
.skip-cell {
  background: var(--dcdr-surface-2);
  color: var(--dcdr-text-3);
  font-size: 11px; text-align: center; cursor: pointer;
  padding: 3px 8px !important;
  border-top: 1px solid var(--dcdr-code-border);
  border-bottom: 1px solid var(--dcdr-code-border);
}
.skip-cell:hover { color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }

.wc-diff-foot {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 14px; border-top: 1px solid var(--dcdr-code-border);
  background: var(--dcdr-surface-2);
}
.wc-warn { font-size: 11px; color: var(--dcdr-warning); }
.wc-link {
  border: none; background: transparent; cursor: pointer;
  font-size: 11.5px; color: var(--dcdr-accent); padding: 0;
}
.wc-link:hover:not(:disabled) { text-decoration: underline; }
.wc-link:disabled { color: var(--dcdr-text-3); cursor: default; }

.wc-foot {
  padding: 10px 18px; border-top: 1px solid var(--dcdr-border);
  font-size: 11px; line-height: 1.6; color: var(--dcdr-text-3);
}

.mono { font-family: var(--dcdr-font-mono); }
</style>
