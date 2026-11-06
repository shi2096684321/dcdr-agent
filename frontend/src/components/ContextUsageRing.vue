<template>
  <!-- 上下文占用指示器(V3.4) —— 形态对齐 WorkBuddy 的输入框角标:
       平时只有一枚 32×32 容器里的 16px 细圆环(无数字, 靠 hover 提示读数值),
       点击才展开 280px 明细弹层。为什么不做"环内写百分比": 16px 的环内塞数字只能到 8px 字,
       在浅色底上几乎糊成一团; 数值交给提示与弹层, 圆环只负责"有没有在涨"这一眼判断。

       弹层自己做定位而不用 el-popover/el-tooltip 的原因: 这两者都要占用同一个锚点元素,
       套在一起时(弹层包提示)点击事件会被内层吞掉 —— 实测点击圆环没反应。手写一层
       absolute 定位 + 外点关闭, 行为可预期, 也不受 teleport 层级影响。
       弹层结构照抄 WorkBuddy 的顺序: 顶部总进度 → 分段条 → 五类图例(只显示百分比, token 数在 hover 里)。
       两处刻意不同: ① 无数据时仍显示空环并保持可点(否则没有入口去改总上下文);
       ② 弹层底部多了「总上下文」设置(WorkBuddy 把它放在模型配置里, 这里的用户更需要就地改)。 -->
  <div ref="rootEl" class="ctx-usage">
    <button type="button" class="usage-ring" :aria-label="tipText"
            :aria-expanded="open" @click.stop="toggle">
      <svg class="ring" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="8" cy="8" r="7" fill="none" stroke-width="2" class="ring-track" />
        <circle cx="8" cy="8" r="7" fill="none" stroke-width="2" stroke-linecap="round"
                transform="rotate(-90 8 8)" class="ring-progress"
                :stroke-dasharray="CIRCUMFERENCE" :stroke-dashoffset="dashOffset" />
      </svg>
    </button>

    <!-- hover 提示: 与圆环同一行的轻量气泡, 打开弹层后自动让位 -->
    <span v-if="!open" class="ctx-tip">{{ tipText }}</span>

    <div v-if="open" class="cu" role="dialog" aria-label="上下文用量" @click.stop>
      <div class="cu-head">
        <span class="cu-title">上下文用量</span>
        <button type="button" class="cu-close" aria-label="关闭" @click="open = false">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor"
               stroke-width="1.5" stroke-linecap="round">
            <line x1="4" y1="4" x2="12" y2="12" />
            <line x1="12" y1="4" x2="4" y2="12" />
          </svg>
        </button>
      </div>

      <template v-if="hasData">
        <div class="cu-stats">
          <span class="cu-percent">{{ percentText }}%</span>
          <span class="cu-used-info">
            <span class="cu-used-label">已使用</span>
            <span class="cu-used-value">{{ usedK }} / {{ totalK }}</span>
          </span>
        </div>

        <!-- 进度条与图例是同一笔账的两种读法, 必须同源:
             外层 = 已用占窗口的比例, 内层五段 = 已用内部各分类的占比(段间 1px 缝隙用来分隔色块) -->
        <div class="cu-bar">
          <div class="cu-bar-fill" :style="{ width: barWidth + '%' }">
            <span v-for="row in rows" :key="row.key" class="cu-seg"
                  :style="{ width: row.barWidth + '%', background: row.color }"></span>
          </div>
        </div>

        <div class="cu-legend">
          <div v-for="row in rows" :key="row.key" class="cu-row">
            <span class="cu-swatch" :style="{ background: row.color }"></span>
            <span class="cu-row-name">{{ row.label }}</span>
            <!-- 行内只显示百分比: 五行 token 数并排会互相淹没, 具体数值放 hover -->
            <span class="cu-row-value" :title="`${row.label} ${fmt(row.tokens)} tokens`">{{ row.percentText }}</span>
          </div>
        </div>

        <!-- 本轮消耗与上面的"占用"是两笔账: 上面是这一轮结束后窗口里躺着多少(下一轮要重发),
             这里是刚才那一轮真正烧了多少(每轮独立)。混成一个数就分不清"是历史太长还是这轮太长"。
             收尾时才有值 —— 调用模型前答案还不存在。 -->
        <div v-if="round" class="cu-round">
          <div class="round-row">
            <span class="round-k">本轮消耗</span>
            <span class="round-total">{{ fmt(round.total) }}</span>
          </div>
          <div class="round-sub">
            输入 {{ fmt(round.prompt) }} · 输出 {{ fmt(round.completion) }} ·
            {{ round.measured ? '模型实测' : '估算（模型未返回用量）' }}
          </div>
        </div>
      </template>

      <div v-else class="cu-empty">
        这个会话还没有发起过对话。发一条消息后，这里会按系统提示词、工具、对话消息等分别显示占用。
      </div>

      <!-- 分母设置: WorkBuddy 把它放在模型配置里, 这里放进弹层底部 ——
           用户看到"怎么才 3%"的下一步动作就是改分母, 不必再跳出去找设置页。
           形态也照它的口径: 只给档位, 不给自由输入(填错分母不会报错, 只会让整个面板一直显示错的比例) -->
      <div class="cu-foot">
        <div class="foot-row">
          <span class="foot-k">上下文长度</span>
          <el-select :model-value="selectedValue" class="foot-select" size="small"
                     :disabled="!editable || saving" @change="onSelect">
            <el-option v-for="o in options" :key="String(o.value ?? '')"
                       :label="o.label" :value="o.value ?? ''" />
          </el-select>
        </div>
        <div class="foot-hint">
          <template v-if="!editable">当前部署未启用登录，无法保存个人设置。</template>
          <template v-else>切换后立即生效：分母只影响占用比例与「接近上限」提示，不会截断对话。</template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({
  /** ContextUsage: available/totalTokens/limitTokens/percent/segments */
  usage: { type: Object, default: null },
  /** ContextLimitView: limit/userValue/defaultValue/min/max/editable */
  limitView: { type: Object, default: null },
  saving: { type: Boolean, default: false }
})
const emit = defineEmits(['update-limit'])

const CIRCUMFERENCE = 2 * Math.PI * 7

/**
 * 五类配色 —— 直接取 WorkBuddy 的分类色板(#00c29a / #e7b86d / #835cff / #2fcfe6 / #5184ff)。
 * 不套本项目的 --dcdr-* 强调色: 这五个色是数据可视化语义(要互相可区分),
 * 而主题强调色只有一个, 用它画五段等于没有区分度。
 */
const CATEGORY = [
  { key: 'system', label: '系统提示词', color: '#00c29a' },
  { key: 'tools', label: '工具及子智能体', color: '#e7b86d' },
  { key: 'messages', label: '对话消息', color: '#835cff' },
  { key: 'connectors', label: '连接器与MCP', color: '#2fcfe6' },
  { key: 'skills', label: '技能', color: '#5184ff' }
]

const rootEl = ref(null)
const open = ref(false)

function toggle() {
  open.value = !open.value
}

/**
 * 外点关闭。用 mousedown 而不是 click: 点住面板外部再拖回面板内时, click 的 target
 * 会落在共同祖先上, 面板会莫名其妙地消失; mousedown 只认"按下那一刻在哪"。
 */
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

const hasData = computed(() => props.usage?.available === true)
const tokensByKey = computed(() => {
  const map = {}
  for (const seg of props.usage?.segments || []) map[seg.key] = seg.tokens || 0
  return map
})
/**
 * 各段占「已用总量」的百分比, 直接取后端下发的值。
 * <p>为什么不再自己除一遍: 这两笔账(分段占比、分段 token)后端已经算好并下发, 前端重算等于
 * 维护两套公式 —— 后端改了分类口径, 前端不会跟着变, 而且两边会各错各的。
 */
const percentByKey = computed(() => {
  const map = {}
  for (const seg of props.usage?.segments || []) map[seg.key] = seg.percent || 0
  return map
})

const percentText = computed(() => {
  const v = Number(props.usage?.percent)
  return Number.isFinite(v) ? String(v) : '0.0'
})
const limit = computed(() => props.usage?.limitTokens || props.limitView?.limit || 0)
const used = computed(() => props.usage?.totalTokens || 0)

/** 实际画出来的占比: 给一个 2% 的下限, 否则 0.5% 的弧长还不到 1px, 看着像"没数据" */
const arcPercent = computed(() => {
  if (!hasData.value) return 0
  const p = Math.min(100, Math.max(0, Number(props.usage?.percent) || 0))
  return Math.max(p, 2)
})

/** 环的填充: 与提示上的百分比同源, 超过 100% 时封顶画满 */
const dashOffset = computed(() => CIRCUMFERENCE - (arcPercent.value / 100) * CIRCUMFERENCE)

/** 外层进度条宽度 = 已用占窗口(封顶 100), 与圆环一致 */
const barWidth = computed(() => Math.round(arcPercent.value * 10) / 10)

/**
 * token 数格式化: 与 WorkBuddy 一致(≥1000 保留一位小数的 K)。
 * 不做 1.2M / 中文万缩写 —— 同一屏内单位不统一比数字长更难受。
 */
const fmt = (n) => {
  const v = Number(n) || 0
  return v >= 1000 ? `${(v / 1000).toFixed(1)}K` : String(v)
}
const usedK = computed(() => fmt(used.value))
const totalK = computed(() => fmt(limit.value))

/** 本轮模型消耗(收尾时下发; 调用前为空) */
const round = computed(() => props.usage?.lastRound || null)

/**
 * 把各段 token 换算成"占窗口的百分比", 并保证五行加起来正好等于顶部显示的总百分比。
 * <p>
 * 直接各自四舍五入会出现 12.4 + 12.4 + 12.4 = 37% 而顶部写 37.1% 的情况 —— 用户一加就发现对不上。
 * 这里用最大余数法(先按 0.1% 取整, 余下的份额按小数部分从大到小补齐), 与 WorkBuddy 同口径。
 */
function distributePercents(tokens, totalPercent) {
  const sum = tokens.reduce((a, b) => a + b, 0)
  const tenths = Math.round((Number(totalPercent) || 0) * 10)
  if (sum <= 0 || tenths <= 0) return tokens.map(() => 0)
  const raw = tokens.map((t) => (t / sum) * tenths)
  const floors = raw.map((v) => Math.floor(v))
  let left = tenths - floors.reduce((a, b) => a + b, 0)
  const order = raw
    .map((v, i) => ({ i, frac: v - floors[i] }))
    .sort((a, b) => b.frac - a.frac)
  for (let k = 0; k < order.length && left > 0; k++, left--) floors[order[k].i] += 1
  return floors
}

const rows = computed(() => {
  const tokens = CATEGORY.map((c) => tokensByKey.value[c.key] || 0)
  const tenths = distributePercents(tokens, props.usage?.percent)
  return CATEGORY.map((c, i) => ({
    ...c,
    tokens: tokens[i],
    // 内层分段条按"已用量"为分母(后端算好的), 外层宽度已经代表窗口占比
    barWidth: percentByKey.value[c.key] || 0,
    // 文字用最大余数法分配, 保证五行相加正好等于顶部那个总百分比
    percentText: `${(tenths[i] / 10).toFixed(1)}%`
  }))
})

/** hover 提示: 百分比 · 已用 / 上限 上下文已使用(与 WorkBuddy 的措辞一致) */
const tipText = computed(() => {
  if (!hasData.value) return '上下文用量：暂无数据，点击查看'
  return `${percentText.value}% · ${usedK.value} / ${totalK.value} 上下文已使用`
})

const editable = computed(() => props.limitView?.editable !== false)
/** 档位清单由后端下发(含首项「跟随默认」, value=null); 前端不再自己拼标签 */
const options = computed(() => props.limitView?.options || [])
/**
 * 当前选中项。未设置个人值时选「跟随默认」那一项 ——
 * el-select 不接受 null 作为选项值(会和"清空"混淆), 所以用空串做默认档的哨兵值。
 */
const selectedValue = computed(() => props.limitView?.userValue ?? '')

/** 选档即生效: 档位是有限集合, 没有"填到一半"的中间态, 不需要保存按钮 */
function onSelect(value) {
  emit('update-limit', value === '' || value == null ? null : Number(value))
}
</script>

<style scoped>
/* 外层只占 32px: 弹层与提示都相对它绝对定位, 不参与输入行的 flex 布局 */
.ctx-usage { position: relative; flex: none; display: inline-flex; }

.usage-ring {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; padding: 0;
  border: none; border-radius: 16px; background: transparent; cursor: pointer;
  transition: background-color 0.2s ease;
}
.usage-ring:hover { background: var(--dcdr-hover); }
.ring { display: block; }
.ring-track { stroke: var(--dcdr-border-strong); }
.ring-progress {
  /* 与 WorkBuddy 一致: 中性灰而不是强调色 —— 这枚环是"状态指示", 不该在满屏蓝里抢注意力 */
  stroke: var(--dcdr-text-3);
  transition: stroke-dashoffset 0.3s ease;
}

/* hover 提示: 纯 CSS 显示, 避免再引入一层 teleport 弹层 */
.ctx-tip {
  position: absolute; bottom: calc(100% + 6px); right: 0; z-index: 40;
  padding: 5px 8px; border-radius: 8px; white-space: nowrap;
  font-size: 11.5px; line-height: 1.4; color: var(--dcdr-text-1);
  background: var(--dcdr-surface-2); border: 1px solid var(--dcdr-border-strong);
  box-shadow: var(--dcdr-shadow-card);
  opacity: 0; pointer-events: none; transform: translateY(2px);
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.ctx-usage:hover .ctx-tip { opacity: 1; transform: translateY(0); transition-delay: 0.15s; }

/* 明细弹层: 280px / 圆角 16 / 内边距 16, 向上展开(right 对齐, 向左生长) */
.cu {
  position: absolute; bottom: calc(100% + 8px); right: 0; z-index: 60;
  width: 280px; padding: 16px; border-radius: 16px;
  background: var(--dcdr-surface); border: 1px solid var(--dcdr-border-strong);
  box-shadow: var(--dcdr-shadow-pop);
  color: var(--dcdr-text-1); font-family: var(--dcdr-font-sans);
  font-size: 12px; line-height: 1.5; text-align: left;
}
.cu-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.cu-title { flex: 1; font-size: 12px; line-height: 20px; color: var(--dcdr-text-1); }
.cu-close {
  flex: none; display: inline-flex; align-items: center; justify-content: center;
  width: 16px; height: 16px; padding: 0; border: none; border-radius: 4px;
  background: transparent; color: var(--dcdr-text-3); cursor: pointer; transition: color 0.15s ease;
}
.cu-close:hover { color: var(--dcdr-text-1); }

.cu-stats { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.cu-percent { font-size: 20px; font-weight: 700; line-height: 28px; color: var(--dcdr-text-1); }
.cu-used-info { display: flex; align-items: center; gap: 4px; }
.cu-used-label, .cu-used-value { font-size: 12px; line-height: 20px; color: var(--dcdr-text-3); }

.cu-bar {
  display: flex; height: 8px; margin-bottom: 16px;
  border-radius: 3px; overflow: hidden; background: var(--dcdr-hover);
}
.cu-bar-fill { display: flex; gap: 1px; height: 100%; transition: width 0.3s ease; }
.cu-seg { height: 100%; transition: width 0.25s ease; }

.cu-legend { display: flex; flex-direction: column; gap: 4px; }
.cu-row { display: flex; align-items: center; gap: 8px; height: 22px; }
.cu-swatch { width: 8px; height: 8px; flex: 0 0 8px; border-radius: 50%; }
.cu-row-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--dcdr-text-1); }
.cu-row-value { flex: none; font-size: 12px; color: var(--dcdr-text-3); font-variant-numeric: tabular-nums; }

/* 本轮消耗: 与图例用一条虚线隔开 —— 上面是"窗口占用", 这里是"这一轮的账单" */
.cu-round {
  margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--dcdr-border);
}
.round-row { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.round-k { font-size: 12px; color: var(--dcdr-text-2); }
.round-total { font-size: 13px; font-weight: 600; color: var(--dcdr-text-1); font-variant-numeric: tabular-nums; }
.round-sub { margin-top: 2px; font-size: 10.5px; line-height: 1.5; color: var(--dcdr-text-3); }

.cu-empty {
  padding: 10px 12px; margin-bottom: 4px; border-radius: 10px;
  font-size: 11.5px; line-height: 1.6; color: var(--dcdr-text-3);
  background: var(--dcdr-surface-faint); border: 1px dashed var(--dcdr-border);
}

.cu-foot { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--dcdr-border); }
.foot-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.foot-k { flex: none; font-size: 12px; color: var(--dcdr-text-2); }
.foot-select { flex: 1; min-width: 0; }
/* el-select 默认 32px 高、内边距偏大, 在 280px 的面板里会显得笨重; 收到 28px 与 12px 字号 */
.foot-select :deep(.el-select__wrapper) {
  min-height: 28px; padding: 0 8px; font-size: 12px;
  border-radius: 8px; background: var(--dcdr-surface-2);
  box-shadow: 0 0 0 1px var(--dcdr-border-strong) inset;
}
.foot-select :deep(.el-select__placeholder) { font-size: 12px; }
.foot-hint { margin-top: 8px; font-size: 10.5px; line-height: 1.5; color: var(--dcdr-text-3); }
</style>
