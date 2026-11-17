/**
 * 思考流里「工具步骤」的展示元数据。
 *
 * <p>为什么需要这层：后端只下发工具名（如 {@code workspace_read}）与一段 JSON 参数，
 * 直接把这两样摊在界面上，用户看到的是「已调用 3 个工具」加三个英文名 ——
 * 知道有工具跑过，但不知道它读了哪个文件、搜了什么词。这里把工具名翻成动作短语，
 * 再从参数里挑出最能说明「这一步在干什么」的字段，作为行内摘要。
 *
 * <p>未收录的工具不隐藏、不报错：退回原始工具名 + 默认图标。新增工具时界面照常可用，
 * 只是少了一句中文，不需要同步改前端。
 */

/** 工具名 → {label, 目标字段候选, 图标分类}；与后端 tool/* 的 NAME 常量一一对应 */
const TOOL_META = {
  // 工作区（本地文件）
  workspace_read: { label: '读取文件', target: ['path', 'file_path'], icon: 'file' },
  workspace_write: { label: '写入文件', target: ['path', 'file_path'], icon: 'file' },
  workspace_edit: { label: '修改文件', target: ['path', 'file_path'], icon: 'file' },
  workspace_delete: { label: '删除文件', target: ['path'], icon: 'file' },
  workspace_list: { label: '浏览目录', target: ['path', 'dir'], icon: 'file' },
  workspace_search: { label: '搜索工作区', target: ['query', 'pattern'], icon: 'search' },
  // 联网 / 知识库
  web_search: { label: '联网搜索', target: ['query'], icon: 'globe' },
  web_extract: { label: '抓取网页', target: ['url'], icon: 'globe' },
  knowledge_search: { label: '检索知识库', target: ['query'], icon: 'search' },
  knowledge_base: { label: '检索知识库', target: ['query'], icon: 'search' },
  memory_search: { label: '检索长期记忆', target: ['query'], icon: 'search' },
  // 内置小工具
  calculator: { label: '计算', target: ['expression'], icon: 'tool' },
  time: { label: '查询时间', target: ['timezone'], icon: 'tool' },
  weather: { label: '查询天气', target: ['city'], icon: 'tool' },
  image_generation: { label: '生成图片', target: ['prompt'], icon: 'image' },
  skill_load: { label: '加载技能', target: ['name'], icon: 'tool' },
  ask_user: { label: '向你确认', target: [], icon: 'tool' },
  tool_result_read: { label: '查看工具结果', target: ['id'], icon: 'tool' }
}

/** 图标用内联 path（不引图标库：一个组件只用到 5 个图标，不值当多一份依赖） */
const ICON_PATHS = {
  file: [
    'M13.5 3.8H7.2c-.9 0-1.6.7-1.6 1.6v13.2c0 .9.7 1.6 1.6 1.6h9.6c.9 0 1.6-.7 1.6-1.6V8.6l-4.9-4.8z',
    'M13.4 3.9v4.7h5'
  ],
  search: [
    'M11 4.6a6.4 6.4 0 1 1 0 12.8 6.4 6.4 0 0 1 0-12.8z',
    'M20 20l-4.2-4.2'
  ],
  globe: [
    'M12 3.6a8.4 8.4 0 1 1 0 16.8 8.4 8.4 0 0 1 0-16.8z',
    'M3.6 12h16.8',
    'M12 3.6c2.3 2.4 3.5 5.3 3.5 8.4s-1.2 6-3.5 8.4c-2.3-2.4-3.5-5.3-3.5-8.4s1.2-6 3.5-8.4z'
  ],
  image: [
    'M4.6 4.8h14.8v14.4H4.6z',
    'M8.6 10.6a1.7 1.7 0 1 0 0-3.4 1.7 1.7 0 0 0 0 3.4z',
    'M19.4 16.4l-4.8-4.6-8.2 7.2'
  ],
  tool: [
    'M14.5 6.2a4 4 0 0 1 5.3 5.1l-8.6 8.6a2.6 2.6 0 0 1-3.7-3.7l8.6-8.6',
    'M4.4 4.4l3.2 3.2'
  ]
}

/** 参数摘要保留的最大字符数：够看清"哪个文件/搜什么词"，再长也没意义 */
const TARGET_MAX = 60
/** 参数/结果面板单次渲染上限：工具结果动辄上万字符，全量塞进 DOM 会把滚动压垮 */
const DETAIL_MAX = 1600

/** 安全解析工具参数：模型偶尔给非 JSON（如直接给一段文本），解析失败就原样使用 */
function parseArgs(raw) {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  const text = String(raw).trim()
  if (!text || (text[0] !== '{' && text[0] !== '[')) return null
  try {
    return JSON.parse(text)
  } catch (e) {
    return null
  }
}

/** 超长文本截断，保留尾部提示，避免用户以为内容就这么点 */
export function clipText(text, max = DETAIL_MAX) {
  const s = text == null ? '' : String(text)
  if (s.length <= max) return s
  return s.slice(0, max) + '\n…(已截断，原始 ' + s.length + ' 字符)'
}

/** 参数格式化成可读 JSON（解析不出结构就原样返回） */
export function prettyArgs(raw) {
  const parsed = parseArgs(raw)
  if (parsed == null) return clipText(raw)
  // 单行紧凑值（如 path 只有一个）不必展开成多行 JSON，省得一步工具占三行
  try {
    return clipText(JSON.stringify(parsed, null, 2))
  } catch (e) {
    return clipText(raw)
  }
}

/**
 * 把工具名与参数翻译成一行展示信息。
 *
 * @param {string} name    后端工具名
 * @param {string} argsRaw 工具参数（JSON 字符串或原始文本）
 * @return {{label: string, target: string, icon: string[]}} label=动作短语，target=行内摘要，icon=svg path 数组
 */
export function describeTool(name, argsRaw) {
  const raw = name || 'tool'
  const meta = TOOL_META[raw]
  const parsed = parseArgs(argsRaw)
  let target = ''
  if (meta && parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    for (const key of meta.target) {
      const v = parsed[key]
      if (v != null && String(v).trim()) {
        target = String(v).replace(/\s+/g, ' ').trim()
        break
      }
    }
  }
  if (target.length > TARGET_MAX) target = target.slice(0, TARGET_MAX) + '…'
  // MCP 工具名形如 mcp__server__tool：原名太长且带分隔符，只留最后一段更像个动作
  const label = meta
    ? meta.label
    : (raw.startsWith('mcp__') ? '调用 ' + raw.split('__').pop() : raw)
  const iconKey = meta ? meta.icon : 'tool'
  return { label, target, icon: ICON_PATHS[iconKey] || ICON_PATHS.tool }
}

/** 毫秒 → 人读的耗时（<1s 用 ms，其余保留一位小数秒） */
export function formatCost(ms) {
  if (ms == null || !Number.isFinite(Number(ms))) return ''
  const n = Number(ms)
  if (n < 1000) return n + 'ms'
  return (n / 1000).toFixed(1) + 's'
}

/** 工具调用项 → 时间线里的工具段落（历史消息只有"已完成"这一种状态） */
function toToolSegment(t) {
  return {
    kind: 'tool',
    name: t?.name,
    id: t?.id,
    args: t?.arguments,
    status: 'done',
    opId: t?.opId || ''
  }
}

/**
 * 历史消息 → 思考流时间线。
 *
 * <p>优先用后端落库的 {@code trace}：它是「这段思考有多少字符」+「接着调了哪个工具」的有序列表，
 * 配合 {@code reasoningContent} 的累加偏移，就能把一大段拼接起来的思考切回
 * "想了 → 做了 → 又想了"的真实顺序（落库原因见 Message.trace）。
 *
 * <p>老消息没有 {@code trace}（升级前保存的）：退回"整段思考 + 全部工具"——
 * 那份顺序信息本来就没存，编不出来，只能这样。
 *
 * @param {object} msg 后端返回的消息 DTO
 * @return {Array} 可直接喂给 ThinkingTrace 的 segments
 */
export function buildTraceSegments(msg) {
  if (!msg) return []
  const invocations = Array.isArray(msg.toolInvocations) ? msg.toolInvocations : []
  const reasoning = msg.reasoningContent || ''
  const steps = Array.isArray(msg.trace) ? msg.trace : []

  if (!steps.length) {
    const segs = reasoning ? [{ kind: 'reason', text: reasoning }] : []
    invocations.forEach(t => segs.push(toToolSegment(t)))
    return segs
  }

  const segs = []
  const used = new Set()
  let offset = 0
  for (const step of steps) {
    if (!step) continue
    if (step.t === 'reason') {
      const n = Number(step.n) || 0
      const text = reasoning.slice(offset, offset + n)
      offset += n
      if (text) segs.push({ kind: 'reason', text })
    } else if (step.t === 'tool') {
      // 按 id 找对应的调用；找不到就跳过（宁可少一行，也不张冠李戴到别的工具上）
      const idx = invocations.findIndex((t, i) => !used.has(i) && t?.id && t.id === step.id)
      if (idx < 0) continue
      used.add(idx)
      segs.push(toToolSegment(invocations[idx]))
    }
  }
  // 兜底 1: 时间线没引用到的调用（id 缺失或配对失败）补在末尾，至少不丢信息
  invocations.forEach((t, i) => { if (!used.has(i)) segs.push(toToolSegment(t)) })
  // 兜底 2: 切片没覆盖完的思考尾段（截断/改写过）补一段，避免文字凭空消失
  if (offset < reasoning.length) segs.push({ kind: 'reason', text: reasoning.slice(offset) })
  return segs
}
