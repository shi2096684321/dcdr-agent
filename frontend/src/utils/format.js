// 通用格式化工具
export function formatDuration(ms) {
  if (!ms && ms !== 0) return '-'
  const s = Math.round(ms / 1000)
  if (s < 60) return s + 's'
  return Math.floor(s / 60) + 'm' + (s % 60) + 's'
}

export function formatDate(dt) {
  if (!dt) return '-'
  const d = new Date(dt)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 风格值 -> 中文标签
const STYLE_MAP = { vivid: '生动', natural: '自然', anime: '动漫', oil: '油画' }
export function styleLabel(v) {
  return STYLE_MAP[v] || v || '-'
}
