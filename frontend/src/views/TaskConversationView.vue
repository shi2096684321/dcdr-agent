<template>
  <div class="tc-mode">
    <!-- 左侧: 自动执行产生的会话 -->
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="sidebar-header">
        <button class="refresh-btn" :disabled="loading" @click="load">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
        <button class="collapse-btn" @click="sidebarCollapsed = true" title="收起">«</button>
      </div>

      <!-- 来源筛选: 定时任务和工作流混在一列时, 找"昨天晚上那个任务"全靠眼睛扫 -->
      <div class="filter-row">
        <button
          v-for="f in FILTERS"
          :key="f.key"
          class="filter-chip"
          :class="{ on: filter === f.key }"
          @click="filter = f.key"
        >{{ f.label }}<span class="cnt">{{ countOf(f.key) }}</span></button>
      </div>

      <div class="tc-list">
        <div
          v-for="s in visibleSessions"
          :key="s.id"
          class="tc-item"
          :class="{ active: s.id === currentId }"
          @click="select(s.id)"
        >
          <div class="tc-icon" :class="s.source">{{ s.source === 'schedule' ? '⏰' : '⚙' }}</div>
          <div class="tc-info">
            <div class="tc-title" :title="s.title">{{ displayTitle(s) }}</div>
            <div class="tc-meta">
              <span class="src-tag" :class="s.source">{{ sourceLabel(s.source) }}</span>
              <span class="time">{{ formatDate(s.createdAt) }}</span>
            </div>
          </div>
        </div>

        <div v-if="!loading && !visibleSessions.length" class="empty-tip">
          <template v-if="legacyBackend">
            后端还是旧版本, 没有会话来源字段<br />
            本次升级需要重启后端服务后才能区分「任务对话」和普通对话
          </template>
          <template v-else-if="sessions.length">当前筛选下没有记录</template>
          <template v-else>
            暂无自动执行对话<br />
            定时任务或工作流跑一次之后, 这里会出现对应的对话
          </template>
        </div>
      </div>
    </aside>
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false">»</button>

    <!-- 右侧: 对话正文 -->
    <div class="tc-main">
      <template v-if="current">
        <div class="tc-toolbar">
          <div class="tc-toolbar-left">
            <h2 :title="current.title">{{ displayTitle(current) }}</h2>
            <el-tag size="small" :type="current.source === 'schedule' ? 'warning' : 'info'">
              {{ sourceLabel(current.source) }}
            </el-tag>
            <!-- 最后一条是 user 消息 = Agent 还没回, 就是"执行中"最直接的证据 -->
            <el-tag v-if="executing" size="small" type="warning" effect="plain">执行中</el-tag>
            <span v-if="ownerLabel(current)" class="owner">{{ ownerLabel(current) }}</span>
            <span class="created">{{ formatDate(current.createdAt) }}</span>
          </div>
          <div class="tc-toolbar-right">
            <el-button size="small" :loading="msgLoading" @click="loadMessages(current.id)">重新加载</el-button>
            <el-button size="small" type="danger" @click="remove(current)">删除</el-button>
          </div>
        </div>

        <div v-loading="msgLoading" class="tc-messages">
          <MessageBubble
            v-for="m in messages"
            :key="m.id"
            :msg="m"
            readonly
            reasoning-open
            @preview-image="openImage"
            @preview-code="openCode"
          />
          <!--
            执行中的占位: 与首页"等待首个内容块"时的思考动画一致。
            定时任务一次跑几分钟, 在此之前这里只有一条 user 消息, 看起来像"没反应";
            后端把 reasoning 与正文一起在结束时才落库(整轮累积, 中途写入会写成半截),
            所以过程中能给的只有这个状态, 真正的内容要等消息回来 —— 5 秒轮询会自动补上。
          -->
          <div v-if="executing" class="tc-thinking">
            <div class="tc-think-avatar"><img :src="logoUrl" alt="DCDR" /></div>
            <div class="tc-think-body">
              <div class="tc-think-role">DCDR</div>
              <div class="msg-thinking">
                <span class="think-dots"><i></i><i></i><i></i></span>
                <span class="think-label">执行中，正在思考…</span>
              </div>
            </div>
          </div>
          <div v-if="!msgLoading && !messages.length && !executing" class="msg-empty">
            这次执行没有留下消息内容（可能还在执行开头，或执行前就失败了）<br />
            若任务正在执行，页面每 5 秒自动刷新一次
          </div>
        </div>
      </template>

      <div v-else class="tc-blank">
        <div class="tc-blank-title">任务对话</div>
        <div class="tc-blank-desc">
          这里只放系统自动执行产生的对话 —— 定时任务到点触发、工作流每跑一步都会留一条。<br />
          你自己聊的对话在左侧「对话」里, 两边不再混在一起。
        </div>
      </div>
    </div>

    <!-- 复用对话页的两个预览弹窗: 自动执行也可能产出图片/代码 -->
    <ImagePreviewModal v-model:url="previewImageUrl" />
    <CodePreviewModal
      v-model:visible="codePreview.visible"
      :code="codePreview.code"
      :lang="codePreview.lang"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import logoUrl from '@/assets/dcdr-logo.png'
import MessageBubble from '@/components/MessageBubble.vue'
import ImagePreviewModal from '@/components/ImagePreviewModal.vue'
import CodePreviewModal from '@/components/CodePreviewModal.vue'
import { getSessions, getMessages, deleteSession } from '@/api/chat'
import { getScheduledTasks } from '@/api/scheduledTasks'
import { getWorkflows } from '@/api/workflows'
import { formatDate } from '@/utils/format'

/**
 * 「自动化 → 任务对话」。
 * <p>
 * 定时任务和工作流每次执行都会建一个会话跑 Agent。这些会话以前混在对话侧边栏里,
 * 用户分不清哪些是自己聊的; 现在后端给会话落了 source 字段, 这里用 source=automation
 * 单独拉取, 对话页默认只查 user —— 分类发生在服务端, 不靠前端过滤。
 * <p>
 * 页面刻意做成只读: 这些对话是系统跑出来的,"重新生成/撤回"没有语义,
 * 所以 MessageBubble 传 readonly, 只留回看和清理。
 */

const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'schedule', label: '定时任务' },
  { key: 'workflow', label: '工作流' }
]

/**
 * 从定时任务页「执行中」那条记录跳过来时要选中的会话。
 * <p>定时任务执行中正文还没回写, 执行记录页只能显示一个状态 —— 所以那个页面的"看进展"
 * 必须落到这里。传的是会话 id 而不是任务 id: 一个任务有多条会话, 只有 id 能唯一定位。
 */
const props = defineProps({
  initialSessionId: { type: String, default: '' }
})

const sessions = ref([])
const loading = ref(false)
const filter = ref('all')
const sidebarCollapsed = ref(false)
/**
 * 后端是否尚未升级(source 字段不存在)。
 * <p>
 * 旧后端不认 source 参数, 会把**全部**会话原样返回 —— 若直接当成"任务对话"渲染,
 * 页面会安静地把用户自己的对话也列进来, 且没有任何报错。宁可空着并说明原因,
 * 也不要给出一个看起来正常、实际错误的结果。
 */
const legacyBackend = ref(false)

const currentId = ref('')
const messages = ref([])
const msgLoading = ref(false)
/** 执行中自动刷新的定时器句柄(5 秒一次; 没有进行中的会话时它只是空转一次判断) */
let waitTimer = null

const previewImageUrl = ref(null)
const codePreview = ref({ visible: false, code: '', lang: '' })

/** sourceRef(任务 ID / 工作流 ID) → 名称; 任务或工作流被删掉时留空, 由页面退化成只显示来源 */
const taskNames = ref({})
const workflowNames = ref({})

const current = computed(() => sessions.value.find((s) => s.id === currentId.value) || null)

/**
 * 会话是否仍在执行中。
 * <p>后端把 user 消息在调模型之前就落库、assistant 消息要等 Agent 跑完才落库,
 * 所以"最后一条是 user"就是"这一轮还没有回复"—— 不需要额外的状态字段。
 */
const executing = computed(() => {
  const last = messages.value[messages.value.length - 1]
  return !!last && last.role === 'user'
})

const visibleSessions = computed(() =>
  filter.value === 'all' ? sessions.value : sessions.value.filter((s) => s.source === filter.value)
)

function countOf(key) {
  if (key === 'all') return sessions.value.length
  return sessions.value.filter((s) => s.source === key).length
}

function sourceLabel(source) {
  return source === 'schedule' ? '定时任务' : source === 'workflow' ? '工作流' : '对话'
}

/**
 * 列表标题。
 * <p>
 * 历史数据(落 source 字段之前)的标题带 "schedule:" / "workflow:" 前缀, 这里剥掉;
 * 新数据标题已是"定时任务 · 任务名", 且首个问题到达后会被自动改写成问题内容 —— 那反而更好读,
 * 所以不做任何截断, 交给 CSS 省略号。
 */
function displayTitle(s) {
  return (s.title || '(无标题)').replace(/^(schedule|workflow):\s*/, '')
}

function ownerLabel(s) {
  if (!s.sourceRef) return ''
  const name = s.source === 'schedule' ? taskNames.value[s.sourceRef] : workflowNames.value[s.sourceRef]
  if (!name) return ''
  return s.source === 'schedule' ? `来自任务「${name}」` : `来自工作流「${name}」`
}

async function load() {
  loading.value = true
  try {
    const raw = (await getSessions('automation')) || []
    // 一条都没有时无法判断后端版本, 按新版处理(空列表本来就是合法状态)
    legacyBackend.value = raw.length > 0 && raw.every((s) => !s.source)
    sessions.value = legacyBackend.value
      ? []
      : raw.filter((s) => s.source === 'schedule' || s.source === 'workflow')
    if (currentId.value && !sessions.value.some((s) => s.id === currentId.value)) {
      currentId.value = ''
      messages.value = []
    }
    // 优先选中外部指定的会话(从"执行中"的记录跳过来); 它还没出现在列表里时退回默认那条
    const preferred = props.initialSessionId
      && sessions.value.some((s) => s.id === props.initialSessionId)
      ? props.initialSessionId : ''
    if (preferred) {
      if (preferred !== currentId.value) await select(preferred)
    } else if (!currentId.value && sessions.value.length) {
      await select(sessions.value[0].id)
    }
  } catch (e) {
    ElMessage.error('加载任务对话失败：' + (e?.message || '网络异常'))
    sessions.value = []
  } finally {
    loading.value = false
  }
}

/** 名称映射只为把 "来自任务 xxx" 显示成人话, 拉不到不影响主流程 */
async function loadNames() {
  const [tasks, workflows] = await Promise.allSettled([getScheduledTasks(), getWorkflows()])
  if (tasks.status === 'fulfilled' && Array.isArray(tasks.value)) {
    taskNames.value = Object.fromEntries(tasks.value.map((t) => [t.id, t.name]))
  }
  if (workflows.status === 'fulfilled' && Array.isArray(workflows.value)) {
    workflowNames.value = Object.fromEntries(workflows.value.map((w) => [w.id, w.name]))
  }
}

async function select(id) {
  currentId.value = id
  await loadMessages(id)
}

/**
 * @param {boolean} silent 执行中的自动轮询: 不显示 loading, 否则每 5 秒整块消息区闪一下
 */
async function loadMessages(id, silent = false) {
  if (!id) return
  if (!silent) msgLoading.value = true
  try {
    messages.value = (await getMessages(id)) || []
  } catch (e) {
    // 轮询失败不弹错也不清空: 网络抖一下就清屏, 用户会以为对话丢了
    if (!silent) {
      ElMessage.error('加载对话内容失败：' + (e?.message || '网络异常'))
      messages.value = []
    }
  } finally {
    if (!silent) msgLoading.value = false
  }
}

async function remove(s) {
  try {
    await ElMessageBox.confirm(
      `删除这条${sourceLabel(s.source)}对话记录？内容将无法恢复。`,
      '删除任务对话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteSession(s.id)
    sessions.value = sessions.value.filter((x) => x.id !== s.id)
    if (currentId.value === s.id) {
      currentId.value = ''
      messages.value = []
      if (visibleSessions.value.length) select(visibleSessions.value[0].id)
    }
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.message || '网络异常'))
  }
}

function openImage(src) {
  previewImageUrl.value = src
}

/** 与 ChatView 的 code 预览同构: 打开弹窗, 内容由 payload 决定 */
function openCode(payload) {
  codePreview.value = { visible: true, code: payload?.code || '', lang: payload?.lang || '' }
}

onMounted(async () => {
  loadNames()
  await load()
  // 执行中的会话只有 user 消息(assistant 要等 Agent 跑完才落库): 在这种状态下每 5 秒静默拉一次,
  // 让用户停在会话里就能看到回复自己出现, 不用反复点「重新加载」。
  waitTimer = setInterval(() => {
    if (!currentId.value || msgLoading.value) return
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'user') loadMessages(currentId.value, true)
  }, 5000)
})

/** 外部指定了要看的会话(从执行记录跳过来)时立刻切过去 */
watch(() => props.initialSessionId, async (sid) => {
  if (!sid) return
  if (!sessions.value.some((s) => s.id === sid)) {
    // 会话刚建、列表还是旧的: 先刷新列表, 再选中
    await load()
    return
  }
  if (sid !== currentId.value) await select(sid)
})

onUnmounted(() => {
  if (waitTimer) clearInterval(waitTimer)
  waitTimer = null
})
</script>

<style scoped>
.tc-mode { flex: 1; display: flex; overflow: hidden; position: relative; }

.sidebar {
  width: 290px; flex-shrink: 0;
  background: var(--dcdr-bg-subtle);
  border-right: 1px solid var(--dcdr-border);
  display: flex; flex-direction: column;
  transition: width 0.2s;
}
.sidebar.collapsed { width: 0; overflow: hidden; border-right: none; }
.sidebar-expand {
  position: absolute; left: 0; top: 56px; width: 20px; height: 40px;
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-left: none;
  border-radius: 0 6px 6px 0; cursor: pointer; z-index: 10;
  color: var(--dcdr-text-3);
}
.sidebar-header { padding: 12px; display: flex; gap: 8px; align-items: center; }
.refresh-btn {
  flex: 1; padding: 8px 12px; font-size: 13px; cursor: pointer;
  color: var(--dcdr-text-1); background: var(--dcdr-surface);
  border: 1px solid var(--dcdr-border); border-radius: 8px;
  transition: background 0.2s, border-color 0.2s;
}
.refresh-btn:hover:not(:disabled) { background: var(--dcdr-hover); border-color: var(--dcdr-border-strong, var(--dcdr-border)); }
.refresh-btn:disabled { color: var(--dcdr-text-3); cursor: default; }
.collapse-btn {
  width: 28px; height: 32px; border: 1px solid var(--dcdr-border);
  background: var(--dcdr-surface); border-radius: 6px; cursor: pointer; color: var(--dcdr-text-3);
}

/* 来源筛选条 */
.filter-row { display: flex; gap: 6px; padding: 0 12px 10px; flex-wrap: wrap; }
.filter-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 10px; font-size: 12px; cursor: pointer;
  color: var(--dcdr-text-3); background: transparent;
  border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-pill, 999px);
  transition: background 0.2s, color 0.2s, border-color 0.2s;
}
.filter-chip:hover { background: var(--dcdr-hover); color: var(--dcdr-text-1); }
.filter-chip.on {
  color: var(--dcdr-accent-soft, var(--dcdr-text-1));
  background: var(--dcdr-accent-wash);
  border-color: rgba(46, 167, 255, 0.45);
}
.filter-chip .cnt { font-size: 11px; opacity: 0.75; }

.tc-list { flex: 1; overflow-y: auto; padding: 0 8px 12px; }
.tc-item {
  display: flex; gap: 10px; padding: 10px; border-radius: 8px;
  cursor: pointer; margin-bottom: 4px;
}
.tc-item:hover { background: var(--dcdr-bg); }
.tc-item.active { background: var(--dcdr-accent-wash); }
.tc-icon {
  width: 32px; height: 32px; border-radius: 8px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center; font-size: 15px;
  background: var(--dcdr-bg);
}
.tc-icon.workflow { background: var(--dcdr-bg); }
.tc-info { flex: 1; min-width: 0; }
.tc-title {
  font-size: 13px; font-weight: 500; color: var(--dcdr-text-1);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.tc-meta { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--dcdr-text-3); margin-top: 4px; }
.src-tag { padding: 0 5px; border-radius: 4px; font-size: 10.5px; line-height: 16px; }
.src-tag.schedule { color: #b7791f; background: rgba(214, 158, 46, 0.14); }
.src-tag.workflow { color: var(--dcdr-text-3); background: var(--dcdr-hover); }
.empty-tip { text-align: center; color: var(--dcdr-text-3); font-size: 13px; padding: 40px 14px; line-height: 1.9; }

.tc-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: var(--dcdr-surface); min-width: 0; }
.tc-toolbar {
  flex: none; display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 12px 20px; border-bottom: 1px solid var(--dcdr-border);
}
.tc-toolbar-left { display: flex; align-items: center; gap: 8px; min-width: 0; }
.tc-toolbar-left h2 {
  font-size: 15px; font-weight: 600; color: var(--dcdr-text-1); margin: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 460px;
}
.owner, .created { font-size: 12px; color: var(--dcdr-text-3); white-space: nowrap; }
.tc-toolbar-right { display: flex; gap: 8px; flex-shrink: 0; }

.tc-messages { flex: 1; overflow-y: auto; padding: 16px 20px 40px; min-height: 0; }
.msg-empty { text-align: center; color: var(--dcdr-text-3); font-size: 13px; padding: 40px 0; }

/* 执行中占位: 尺寸与 MessageBubble 的气泡布局对齐(40px 头像 / 14px 间距 / 900px 居中),
   这样它和上面已落库的消息在同一条视觉轴上, 不会读成"另一个组件" */
.tc-thinking { display: flex; gap: 14px; margin-bottom: 28px; max-width: 900px; margin-left: auto; margin-right: auto; width: 100%; }
.tc-think-avatar { width: 40px; height: 40px; border-radius: 12px; flex-shrink: 0; overflow: hidden; box-shadow: 0 3px 10px rgba(0, 0, 0, 0.1); }
.tc-think-avatar img { width: 100%; height: 100%; object-fit: cover; }
.tc-think-body { flex: 1; min-width: 0; }
.tc-think-role { font-size: 13px; color: var(--dcdr-text-3); margin-bottom: 6px; font-weight: 500; }
/* 三个圆点依次弹跳 + 文字渐变扫光: 与首页等待首个内容块时的动画完全一致 */
.msg-thinking { display: inline-flex; align-items: center; gap: 10px; padding: 10px 16px; border-radius: 12px; background: var(--dcdr-accent-wash); border: 1px solid var(--dcdr-accent-wash); }
.think-dots { display: inline-flex; gap: 4px; }
.think-dots i { width: 7px; height: 7px; border-radius: 50%; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); animation: thinkBounce 1.2s ease-in-out infinite; }
.think-dots i:nth-child(2) { animation-delay: 0.15s; }
.think-dots i:nth-child(3) { animation-delay: 0.3s; }
@keyframes thinkBounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.35; }
  30% { transform: translateY(-5px); opacity: 1; }
}
.think-label { font-size: 13px; background: linear-gradient(90deg, #9aa7c2 0%, var(--dcdr-accent) 50%, #9aa7c2 100%); background-size: 200% 100%; -webkit-background-clip: text; background-clip: text; color: transparent; animation: thinkShimmer 2s linear infinite; }
@keyframes thinkShimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.tc-blank { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 40px; }
.tc-blank-title { font-size: 16px; font-weight: 600; color: var(--dcdr-text-1); }
.tc-blank-desc { font-size: 13px; line-height: 1.9; color: var(--dcdr-text-3); text-align: center; max-width: 520px; }
</style>
