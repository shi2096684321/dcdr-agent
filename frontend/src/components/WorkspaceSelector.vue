<template>
  <div class="ws-bar">
    <button class="ws-btn" :class="{ active: current }" @click="open" :title="current ? current.rootPath : '未选择工作区'">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3.5 7.2c0-.9.7-1.6 1.6-1.6h4l2 2.2h7.8c.9 0 1.6.7 1.6 1.6v9c0 .9-.7 1.6-1.6 1.6H5.1c-.9 0-1.6-.7-1.6-1.6V7.2z"/>
      </svg>
      <span class="ws-name">{{ current ? current.name : '未选择工作区' }}</span>
      <span v-if="current" class="ws-level" :class="'lv-' + current.accessLevel">{{ levelLabel(current.accessLevel) }}</span>
    </button>
    <button v-if="current" class="ws-unbind" @click="unbind" title="解除本会话与工作区的关联（不删除任何文件）">×</button>

    <!-- 选择弹窗 -->
    <div v-if="dialog" class="ws-mask" @click.self="dialog = false">
      <div class="ws-dialog">
        <div class="ws-dialog-head">
          <h3>选择工作区</h3>
          <button class="ws-close" @click="dialog = false">×</button>
        </div>
        <p class="ws-tip">
          选中的目录会授权给本会话的模型读写：<b>只读</b>档仅能查看；<b>可写</b>档能改文件；<b>可写可删</b>档还能删除（删除会先进回收区，可撤销）。
        </p>

        <div class="ws-list">
          <div v-for="w in workspaces" :key="w.id" class="ws-item" :class="{ selected: current && current.id === w.id }">
            <div class="ws-item-main" @click="bind(w)">
              <div class="ws-item-name">{{ w.name }}</div>
              <div class="ws-item-path mono">{{ w.rootPath }}</div>
            </div>
            <select :value="w.accessLevel" class="ws-item-level" @change="changeLevel(w, $event.target.value)">
              <option value="READ">只读</option>
              <option value="WRITE">可写</option>
              <option value="FULL">可写可删</option>
            </select>
            <button class="ws-item-del" title="仅解除关联，不删除磁盘文件" @click="remove(w)">删除</button>
          </div>
          <div v-if="!workspaces.length" class="ws-empty">还没有工作区，先在下面添加一个目录</div>
        </div>

        <div class="ws-form">
          <input v-model="form.rootPath" class="ws-input mono" placeholder="/Users/me/work/my-project 或 D:\\Projects\\my-project" />
          <!-- 打开磁盘逐级选择: 手打绝对路径是最容易出错的一步, 一个字符错了就白填 -->
          <button class="ws-browse" @click="openPicker" title="打开磁盘目录选择">浏览…</button>
          <input v-model="form.name" class="ws-input ws-input-sm" placeholder="名称（可留空）" />
          <select v-model="form.accessLevel" class="ws-item-level">
            <option value="READ">只读</option>
            <option value="WRITE">可写</option>
            <option value="FULL">可写可删</option>
          </select>
          <button class="ws-primary" :disabled="saving" @click="create">{{ saving ? '添加中…' : '添加目录' }}</button>
        </div>
        <p class="ws-hint">
          路径是运行本服务的这台机器上的真实目录。删除工作区只解除关联，<b>不会删除磁盘上的任何文件</b>。
        </p>
        <p v-if="!sessionId" class="ws-hint ws-hint-warn">
          当前是新会话（尚未保存到会话列表）。选择或添加目录时会自动创建一个会话并绑定，之后也能在这里随时查看。
        </p>
      </div>
    </div>

    <!-- 目录浏览面板: 盘符 → 逐级下钻 → 选中回填 -->
    <div v-if="picker.open" class="ws-mask ws-mask-top" @click.self="picker.open = false">
      <div class="ws-dialog ws-picker">
        <div class="ws-dialog-head">
          <h3>选择目录</h3>
          <button class="ws-close" @click="picker.open = false">×</button>
        </div>

        <div class="ws-roots">
          <button
            v-for="r in picker.roots"
            :key="r.path"
            class="ws-root"
            :class="{ active: picker.path === r.path }"
            @click="go(r.path)"
          >{{ r.name }}</button>
        </div>

        <div class="ws-crumbs">
          <button class="ws-crumb-up" :disabled="!picker.parent" @click="go(picker.parent)" title="上一级">↑</button>
          <template v-for="(c, i) in crumbs" :key="i">
            <span v-if="i" class="ws-crumb-sep">›</span>
            <button class="ws-crumb" @click="go(c.path)">{{ c.label }}</button>
          </template>
        </div>

        <div class="ws-picker-list">
          <button v-for="d in picker.children" :key="d.path" class="ws-dir" @click="go(d.path)">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3.5 7.2c0-.9.7-1.6 1.6-1.6h4l2 2.2h7.8c.9 0 1.6.7 1.6 1.6v9c0 .9-.7 1.6-1.6 1.6H5.1c-.9 0-1.6-.7-1.6-1.6V7.2z"/>
            </svg>
            <span class="ws-dir-name">{{ d.name }}</span>
          </button>
          <div v-if="!picker.children.length" class="ws-empty">该目录下没有子目录</div>
        </div>

        <div class="ws-picker-foot">
          <span class="ws-picker-path mono" :title="picker.path">{{ picker.path }}</span>
          <button class="ws-ghost" @click="picker.open = false">取消</button>
          <button class="ws-primary" @click="choosePath">选择此目录</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 工作区选择器(V3.3 F-49)。
 *
 * 设计要点：
 * - 徽标常驻显示「名称 + 档位」，hover 显示绝对路径 —— 用户必须随时能确认模型在读哪个目录；
 * - 档位默认 READ：给读权限不等于给删权限，升档由用户显式操作；
 * - 解除绑定/删除工作区都不触碰磁盘，文案里写死这一点。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listWorkspaces, createWorkspace, updateWorkspace, deleteWorkspace,
  bindWorkspace, unbindWorkspace, getSession, browseDirs
} from '@/api'

const props = defineProps({
  sessionId: { type: String, default: '' }
})

// 绑定强依赖会话 ID, 而首页的新会话是懒创建的(首次发消息才落库) ——
// 没有会话时向父组件要一个, 拿到 ID 后由 watch 续上这次绑定
const emit = defineEmits(['need-session'])

const workspaces = ref([])
const boundId = ref('')
const dialog = ref(false)
const saving = ref(false)
const form = ref({ rootPath: '', name: '', accessLevel: 'READ' })

/** 等待会话就绪后再绑定的工作区 */
const pendingWorkspace = ref(null)

const current = computed(() => workspaces.value.find((w) => w.id === boundId.value) || null)

const levelLabel = (lv) => ({ READ: '只读', WRITE: '可写', FULL: '可写可删' }[lv] || lv)

/**
 * 目录浏览面板状态。
 *
 * 为什么必须有这个面板: 浏览器出于安全限制拿不到本机的绝对路径 ——
 * `<input type="file" webkitdirectory>` 只给相对路径, `showDirectoryPicker()` 也只给 handle。
 * 所以"打开磁盘选目录"只能由运行在本机的后端来枚举(F-49 的 /browse 接口), 前端负责逐级下钻。
 */
const picker = ref({ open: false, path: '', parent: null, roots: [], children: [] })

/** 把绝对路径切成可点击的面包屑; Windows 盘符(D:\)单独处理, 其余按分隔符推 */
const crumbs = computed(() => {
  const p = picker.value.path
  if (!p) return []
  const win = p.includes('\\')
  const sep = win ? '\\' : '/'
  const segs = p.split(sep).filter(Boolean)
  const out = []
  // POSIX 以 / 开头时, 首个片段要拼在根后而不是当成相对名的开头
  let acc = win ? '' : (p.startsWith('/') ? '/' : '')
  segs.forEach((seg) => {
    if (win && seg.endsWith(':')) {
      acc = seg + sep
    } else if (!acc || acc.endsWith(sep)) {
      acc = acc + seg
    } else {
      acc = acc + sep + seg
    }
    out.push({ label: seg, path: acc })
  })
  return out
})

function applyDir(r) {
  picker.value = {
    open: true,
    path: r?.path || '',
    parent: r?.parent || null,
    roots: r?.roots || [],
    children: r?.children || []
  }
}

/** 打开面板: 优先从已填的路径开始, 让用户接着上次的位置往下走 */
async function openPicker() {
  try {
    applyDir(await browseDirs(form.value.rootPath.trim() || ''))
  } catch (e) {
    // 首次打不开就直接说原因(非管理员 / 开关关闭), 不要留一个空面板让用户猜
    ElMessage.error(e?.message || '无法打开目录选择')
  }
}

async function go(path) {
  if (!path) return
  try {
    applyDir(await browseDirs(path))
  } catch (e) {
    // 进某个子目录失败(无权限)不该把整个面板关掉, 保留当前位置让用户换一条路
    ElMessage.error(e?.message || '无法读取该目录')
  }
}

function choosePath() {
  const p = picker.value.path
  if (!p) return
  form.value.rootPath = p
  if (!form.value.name.trim()) {
    // 名称留空时用末级目录名, 否则列表里全是"未命名"没法区分
    form.value.name = p.split(/[\\/]/).filter(Boolean).pop() || ''
  }
  picker.value.open = false
}

async function load() {
  try {
    workspaces.value = await listWorkspaces()
  } catch (e) {
    workspaces.value = []
  }
}

async function loadBinding() {
  if (!props.sessionId) { boundId.value = ''; return }
  try {
    // 会话详情里带 workspaceId；拿不到就当未绑定
    const s = await getSession(props.sessionId)
    boundId.value = s?.workspaceId || ''
  } catch (e) {
    boundId.value = ''
  }
}

function open() {
  dialog.value = true
}

async function create() {
  if (!form.value.rootPath.trim()) {
    ElMessage.warning('请填写目录路径')
    return
  }
  saving.value = true
  try {
    const w = await createWorkspace({
      name: form.value.name || undefined,
      rootPath: form.value.rootPath.trim(),
      accessLevel: form.value.accessLevel
    })
    form.value = { rootPath: '', name: '', accessLevel: 'READ' }
    await load()
    // 无论当前有没有会话都走 bind(): 没有会话时它会去要一个, 而不是静默跳过
    await bind(w)
    ElMessage.success('工作区已添加')
  } catch (e) {
    ElMessage.error(e?.message || '添加失败')
  } finally {
    saving.value = false
  }
}

async function bind(w) {
  if (!props.sessionId) {
    // 关键: 这里以前是 `if (props.sessionId)` 直接跳过 —— 工作区建好了、绑定却没发出去,
    // 界面上仍显示"未选择工作区"且没有任何提示, 看起来像功能坏了。
    // 现在记下待绑定目标, 请父组件建一个会话, 会话 ID 回来后自动补上。
    pendingWorkspace.value = w
    emit('need-session')
    return
  }
  try {
    await bindWorkspace(props.sessionId, w.id)
    boundId.value = w.id
    dialog.value = false
    ElMessage.success(`已绑定到「${w.name}」`)
  } catch (e) {
    ElMessage.error(e?.message || '绑定失败')
  }
}

async function unbind() {
  if (!props.sessionId || !current.value) return
  try {
    await unbindWorkspace(props.sessionId)
    boundId.value = ''
    ElMessage.success('已解除关联（磁盘文件未受影响）')
  } catch (e) {
    ElMessage.error(e?.message || '解绑失败')
  }
}

async function changeLevel(w, level) {
  if (level === w.accessLevel) return
  if (level === 'FULL') {
    try {
      await ElMessageBox.confirm(
        '该会话的模型将可以删除此目录下的文件（删除会先移入回收区，可撤销）。确定升到此档位吗？',
        '授予删除权限', { type: 'warning' }
      )
    } catch (e) {
      return
    }
  }
  try {
    const updated = await updateWorkspace(w.id, { accessLevel: level })
    Object.assign(w, updated)
    ElMessage.success('档位已更新，下一轮对话生效')
  } catch (e) {
    ElMessage.error(e?.message || '更新失败')
  }
}

async function remove(w) {
  try {
    await ElMessageBox.confirm(
      `删除工作区「${w.name}」只会解除关联，不会删除磁盘上的任何文件。继续吗？`,
      '删除工作区', { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await deleteWorkspace(w.id)
    if (boundId.value === w.id) boundId.value = ''
    await load()
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

onMounted(async () => {
  await Promise.all([load(), loadBinding()])
})

watch(() => props.sessionId, async (id) => {
  await loadBinding()
  // 会话刚被创建出来: 把之前选好但没处可绑的工作区补上
  if (id && pendingWorkspace.value) {
    const w = pendingWorkspace.value
    pendingWorkspace.value = null
    await bind(w)
  }
})
</script>

<style scoped>
/* 配色一律走 theme.css 的设计令牌，不写 hex、不写 fallback。
   不写 fallback 是刻意的：之前这里用了 --dcdr-text / --dcdr-text-secondary 这两个
   并不存在的变量名，fallback #222/#666 在深色底(#06070f)上分别只有 1.2:1 和 3.7:1，
   于是"浅色还好、暗色看不清"。去掉 fallback 后，变量名写错会立刻暴露成明显错误的观感。 */

.ws-bar { display: flex; align-items: center; gap: 4px; padding: 0 28px 8px; }
.ws-btn {
  display: inline-flex; align-items: center; gap: 6px; height: 28px; padding: 0 10px;
  border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-pill);
  background: var(--dcdr-surface-faint);
  color: var(--dcdr-text-2); font-size: 12px; font-weight: 500; cursor: pointer;
  transition: color var(--dcdr-dur) var(--dcdr-ease), border-color var(--dcdr-dur) var(--dcdr-ease);
}
.ws-btn:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); background: var(--dcdr-hover); }
.ws-btn.active { color: var(--dcdr-text-1); border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); }
.ws-btn svg { width: 14px; height: 14px; }
.ws-name { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 档位徽标：三档用「同底色 + 不同字色」区分，深色下不会因底色透明而糊成一片 */
.ws-level {
  padding: 1px 7px; border-radius: var(--dcdr-radius-pill); font-size: 11px;
  background: var(--dcdr-hover); color: var(--dcdr-text-2);
}
.ws-level.lv-WRITE { color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }
.ws-level.lv-FULL { color: var(--dcdr-warning); background: var(--dcdr-hover); }
.ws-unbind {
  border: none; background: transparent; cursor: pointer;
  color: var(--dcdr-text-3); font-size: 15px; line-height: 1;
}
.ws-unbind:hover { color: var(--dcdr-danger); }

/* 遮罩：两套主题共用一个足够沉的底，省去主题分支；
   深色下它还要负责把面板和同样很暗的页面底色分开 */
.ws-mask {
  position: fixed; inset: 0; background: rgba(3, 5, 12, .6);
  display: flex; align-items: center; justify-content: center; z-index: 3000;
}
.ws-dialog {
  width: 620px; max-height: 80vh; overflow: auto;
  background: var(--dcdr-surface);
  border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-card);
  box-shadow: var(--dcdr-shadow-pop);
  padding: 20px;
}
.ws-dialog-head { display: flex; align-items: center; justify-content: space-between; }
.ws-dialog-head h3 { margin: 0; font-size: 15px; color: var(--dcdr-text-1); }
.ws-close {
  border: none; background: transparent; font-size: 18px; cursor: pointer;
  color: var(--dcdr-text-3); line-height: 1;
}
.ws-close:hover { color: var(--dcdr-text-1); }
.ws-tip { margin: 8px 0 14px; font-size: 12px; color: var(--dcdr-text-2); line-height: 1.6; }
.ws-tip b { color: var(--dcdr-text-1); }

.ws-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px; }
.ws-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner);
  background: var(--dcdr-surface-2);
}
.ws-item.selected { border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); }
.ws-item-main { flex: 1; min-width: 0; cursor: pointer; }
.ws-item-name { font-size: 13px; color: var(--dcdr-text-1); }
.ws-item-path { font-size: 11px; color: var(--dcdr-text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-item-level, .ws-input {
  border: 1px solid var(--dcdr-border); border-radius: 6px; padding: 4px 6px; font-size: 12px;
  background: var(--dcdr-surface-2); color: var(--dcdr-text-1);
}
.ws-item-del { border: none; background: transparent; color: var(--dcdr-danger); font-size: 12px; cursor: pointer; }
.ws-empty { font-size: 12px; color: var(--dcdr-text-3); padding: 6px 2px; }

.ws-form { display: flex; gap: 8px; }
.ws-input { flex: 1; height: 30px; }
.ws-input-sm { max-width: 120px; }
.ws-input::placeholder { color: var(--dcdr-text-3); }
.ws-input:focus, .ws-item-level:focus { border-color: var(--dcdr-accent-line); outline: none; }
/* 主按钮沿用全站的强调色渐变，与 el-button--primary 一致；
   字重提到 600 是因为深色下亮蓝底上的白字笔画偏细，比浅色主题更需要重量 */
.ws-primary {
  height: 30px; padding: 0 14px; border-radius: 6px; border: none;
  background-image: var(--dcdr-accent-grad); color: var(--dcdr-text-inverse);
  font-size: 12px; font-weight: 600; cursor: pointer;
}
.ws-primary:disabled { opacity: .6; cursor: default; }
.ws-hint { margin: 10px 0 0; font-size: 11px; color: var(--dcdr-text-3); line-height: 1.6; }
.ws-hint b { color: var(--dcdr-text-2); }
.ws-hint-warn { color: var(--dcdr-warning); }

/* ===== 目录浏览面板 ===== */
.ws-browse {
  height: 30px; padding: 0 10px; border-radius: 6px; font-size: 12px; cursor: pointer;
  border: 1px solid var(--dcdr-border); background: var(--dcdr-surface-2);
  color: var(--dcdr-text-2); white-space: nowrap;
}
.ws-browse:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); }
.ws-mask-top { z-index: 3100; }
.ws-picker { width: 640px; display: flex; flex-direction: column; gap: 10px; }

.ws-roots { display: flex; flex-wrap: wrap; gap: 6px; }
.ws-root {
  padding: 3px 10px; border-radius: var(--dcdr-radius-pill); font-size: 12px; cursor: pointer;
  border: 1px solid var(--dcdr-border); background: var(--dcdr-surface-2); color: var(--dcdr-text-2);
}
.ws-root:hover { border-color: var(--dcdr-border-strong); color: var(--dcdr-text-1); }
.ws-root.active {
  border-color: var(--dcdr-accent-line); color: var(--dcdr-accent); background: var(--dcdr-accent-wash);
}

.ws-crumbs {
  display: flex; align-items: center; gap: 2px; flex-wrap: wrap;
  padding: 6px 8px; border-radius: 8px;
  background: var(--dcdr-surface-2); border: 1px solid var(--dcdr-border);
  font-size: 12px; max-height: 66px; overflow: auto;
}
.ws-crumb-up {
  border: none; background: transparent; cursor: pointer;
  color: var(--dcdr-text-2); font-size: 13px; padding: 0 4px; line-height: 1;
}
.ws-crumb-up:hover:not(:disabled) { color: var(--dcdr-text-1); }
.ws-crumb-up:disabled { opacity: .3; cursor: default; }
.ws-crumb {
  border: none; background: transparent; cursor: pointer; font-size: 12px;
  color: var(--dcdr-accent); padding: 0 2px;
}
.ws-crumb:hover { text-decoration: underline; }
.ws-crumb-sep { color: var(--dcdr-text-3); font-size: 11px; }

.ws-picker-list {
  min-height: 200px; max-height: 320px; overflow: auto;
  border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-inner);
  background: var(--dcdr-surface-2); padding: 4px;
  display: flex; flex-direction: column;
}
.ws-dir {
  display: flex; align-items: center; gap: 8px; width: 100%; padding: 6px 8px;
  border: none; border-radius: 6px; background: transparent; cursor: pointer;
  font-size: 13px; color: var(--dcdr-text-1); text-align: left;
}
.ws-dir:hover { background: var(--dcdr-hover); }
/* 文件夹图标用语义色而不是写死橙色：深色下 --dcdr-warning 是 #fbbf24（亮），浅色是 #8f5f06（深），两边都够 */
.ws-dir svg { width: 14px; height: 14px; flex: none; color: var(--dcdr-warning); }
.ws-dir-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.ws-picker-foot { display: flex; align-items: center; gap: 8px; }
.ws-picker-path {
  flex: 1; min-width: 0; font-size: 11px; color: var(--dcdr-text-3);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ws-ghost {
  height: 30px; padding: 0 12px; border-radius: 6px; font-size: 12px; cursor: pointer;
  border: 1px solid var(--dcdr-border); background: transparent; color: var(--dcdr-text-2);
}
.ws-ghost:hover { color: var(--dcdr-text-1); border-color: var(--dcdr-border-strong); background: var(--dcdr-hover); }

.mono { font-family: var(--dcdr-font-mono); }
</style>
