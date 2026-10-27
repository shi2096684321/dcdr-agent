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

const workspaces = ref([])
const boundId = ref('')
const dialog = ref(false)
const saving = ref(false)
const form = ref({ rootPath: '', name: '', accessLevel: 'READ' })

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
    if (props.sessionId) await bind(w)
    ElMessage.success('工作区已添加')
  } catch (e) {
    ElMessage.error(e?.message || '添加失败')
  } finally {
    saving.value = false
  }
}

async function bind(w) {
  if (!props.sessionId) {
    ElMessage.warning('请先创建或选择一个会话')
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

watch(() => props.sessionId, async () => {
  await loadBinding()
})
</script>

<style scoped>
.ws-bar { display: flex; align-items: center; gap: 4px; padding: 0 28px 8px; }
.ws-btn {
  display: inline-flex; align-items: center; gap: 6px; height: 28px; padding: 0 10px;
  border: 1px solid var(--dcdr-border, #e5e5e5); border-radius: 14px; background: transparent;
  color: var(--dcdr-text-secondary, #666); font-size: 12px; cursor: pointer;
}
.ws-btn:hover, .ws-btn.active { color: var(--dcdr-text, #222); border-color: var(--dcdr-border-strong, #ccc); }
.ws-btn svg { width: 14px; height: 14px; }
.ws-name { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-level { padding: 1px 6px; border-radius: 8px; font-size: 11px; background: rgba(120,120,120,.12); }
.ws-level.lv-WRITE { background: rgba(50,120,255,.14); color: #2b6cf6; }
.ws-level.lv-FULL { background: rgba(255,140,0,.16); color: #c96a00; }
.ws-unbind { border: none; background: transparent; cursor: pointer; color: #999; font-size: 15px; line-height: 1; }

.ws-mask { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: center; justify-content: center; z-index: 3000; }
.ws-dialog { width: 620px; max-height: 80vh; overflow: auto; background: var(--dcdr-bg, #fff); border-radius: 12px; padding: 20px; }
.ws-dialog-head { display: flex; align-items: center; justify-content: space-between; }
.ws-dialog-head h3 { margin: 0; font-size: 15px; }
.ws-close { border: none; background: transparent; font-size: 18px; cursor: pointer; color: #999; }
.ws-tip { margin: 8px 0 14px; font-size: 12px; color: var(--dcdr-text-secondary, #777); line-height: 1.6; }
.ws-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px; }
.ws-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border: 1px solid var(--dcdr-border, #eee); border-radius: 8px; }
.ws-item.selected { border-color: #2b6cf6; }
.ws-item-main { flex: 1; min-width: 0; cursor: pointer; }
.ws-item-name { font-size: 13px; }
.ws-item-path { font-size: 11px; color: #999; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-item-level, .ws-input { border: 1px solid var(--dcdr-border, #ddd); border-radius: 6px; padding: 4px 6px; font-size: 12px; background: transparent; color: inherit; }
.ws-item-del { border: none; background: transparent; color: #b25; font-size: 12px; cursor: pointer; }
.ws-empty { font-size: 12px; color: #999; padding: 6px 2px; }
.ws-form { display: flex; gap: 8px; }
.ws-input { flex: 1; height: 30px; }
.ws-input-sm { max-width: 120px; }
.ws-primary { height: 30px; padding: 0 14px; border-radius: 6px; border: none; background: #2b6cf6; color: #fff; font-size: 12px; cursor: pointer; }
.ws-primary:disabled { opacity: .6; cursor: default; }
.ws-hint { margin: 10px 0 0; font-size: 11px; color: #999; line-height: 1.6; }

/* 目录浏览面板 */
.ws-browse {
  height: 30px; padding: 0 10px; border-radius: 6px; font-size: 12px; cursor: pointer;
  border: 1px solid var(--dcdr-border, #ddd); background: transparent; color: var(--dcdr-text-secondary, #666);
  white-space: nowrap;
}
.ws-browse:hover { color: var(--dcdr-text, #222); border-color: var(--dcdr-border-strong, #bbb); }
.ws-mask-top { z-index: 3100; }
.ws-picker { width: 640px; display: flex; flex-direction: column; gap: 10px; }
.ws-roots { display: flex; flex-wrap: wrap; gap: 6px; }
.ws-root {
  padding: 3px 10px; border-radius: 12px; font-size: 12px; cursor: pointer;
  border: 1px solid var(--dcdr-border, #e5e5e5); background: transparent; color: var(--dcdr-text-secondary, #666);
}
.ws-root:hover { border-color: var(--dcdr-border-strong, #ccc); color: var(--dcdr-text, #222); }
.ws-root.active { border-color: #2b6cf6; color: #2b6cf6; background: rgba(43,108,246,.08); }
.ws-crumbs {
  display: flex; align-items: center; gap: 2px; flex-wrap: wrap;
  padding: 6px 8px; border-radius: 8px; background: var(--dcdr-bg-subtle, #f7f7f8);
  font-size: 12px; max-height: 66px; overflow: auto;
}
.ws-crumb-up {
  border: none; background: transparent; cursor: pointer; color: var(--dcdr-text-secondary, #666);
  font-size: 13px; padding: 0 4px; line-height: 1;
}
.ws-crumb-up:disabled { opacity: .35; cursor: default; }
.ws-crumb { border: none; background: transparent; cursor: pointer; font-size: 12px; color: #2b6cf6; padding: 0 2px; }
.ws-crumb:hover { text-decoration: underline; }
.ws-crumb-sep { color: #bbb; font-size: 11px; }
.ws-picker-list {
  min-height: 200px; max-height: 320px; overflow: auto;
  border: 1px solid var(--dcdr-border, #eee); border-radius: 8px; padding: 4px;
  display: flex; flex-direction: column;
}
.ws-dir {
  display: flex; align-items: center; gap: 8px; width: 100%; padding: 6px 8px;
  border: none; border-radius: 6px; background: transparent; cursor: pointer;
  font-size: 13px; color: var(--dcdr-text, #222); text-align: left;
}
.ws-dir:hover { background: var(--dcdr-bg-subtle, #f2f3f5); }
.ws-dir svg { width: 14px; height: 14px; flex: none; color: #f0a92a; }
.ws-dir-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-picker-foot { display: flex; align-items: center; gap: 8px; }
.ws-picker-path {
  flex: 1; min-width: 0; font-size: 11px; color: var(--dcdr-text-secondary, #777);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ws-ghost {
  height: 30px; padding: 0 12px; border-radius: 6px; font-size: 12px; cursor: pointer;
  border: 1px solid var(--dcdr-border, #ddd); background: transparent; color: var(--dcdr-text-secondary, #666);
}
.mono { font-family: var(--dcdr-mono, ui-monospace, SFMono-Regular, Menlo, monospace); }
</style>
