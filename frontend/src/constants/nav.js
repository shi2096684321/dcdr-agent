/**
 * 左侧图标轨道的导航项。
 *
 * 为什么不是顶栏 8 个文字 tab：现在有 7 个一级入口，加上模型下拉、联网开关、
 * 主题切换和头像，1440 宽下顶栏会挤成一行麻花。图标轨道占 64px 竖向空间，
 * 顶栏只留"全局命令"，两者各司其职。
 *
 * 图标统一用 currentColor 描边，颜色由 CSS 控制（未选中 = 次级文字，选中 = 强调色），
 * 这样一套图标吃两个主题，不用为选中态复制一份亮色图形。
 */
const ICONS = {
  chat: `<svg viewBox="0 0 24 24" width="19" height="19" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M4 6.6C4 5.2 5.2 4 6.6 4H17.4C18.8 4 20 5.2 20 6.6V14.4C20 15.8 18.8 17 17.4 17H10.4L6.2 20.4V17H6.6C5.2 17 4 15.8 4 14.4V6.6Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/></svg>`,

  creative: `<svg viewBox="0 0 24 24" width="19" height="19" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 3.2L13.9 9.4L20 11.2L13.9 13L12 19.2L10.1 13L4 11.2L10.1 9.4L12 3.2Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><path d="M18.6 16.4L19.4 18.6L21.5 19.4L19.4 20.2L18.6 22.4L17.8 20.2L15.7 19.4L17.8 18.6L18.6 16.4Z" fill="currentColor"/></svg>`,

  knowledge: `<svg viewBox="0 0 24 24" width="19" height="19" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M5 5.2C5 4 6 3.2 7.2 3.2H18.6V17.4H7.2C6 17.4 5 18.2 5 19.4V5.2Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><path d="M18.6 17.4V21H7.2" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/><path d="M9 7.6H14.6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>`,

  expert: `<svg viewBox="0 0 24 24" width="19" height="19" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 3L19 5.9V11.4C19 15.9 15.9 19.4 12 21C8.1 19.4 5 15.9 5 11.4V5.9L12 3Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><circle cx="12" cy="10.4" r="2.2" stroke="currentColor" stroke-width="1.7"/><path d="M8.4 16.4C9 14.7 10.4 13.8 12 13.8C13.6 13.8 15 14.7 15.6 16.4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>`,

  memory: `<svg viewBox="0 0 24 24" width="19" height="19" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 3.6L20 8L12 12.4L4 8L12 3.6Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><path d="M4 12L12 16.4L20 12" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M4 16L12 20.4L20 16" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>`,

  auto: `<svg viewBox="0 0 24 24" width="19" height="19" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="3.4" y="3.4" width="6.2" height="6.2" rx="1.8" stroke="currentColor" stroke-width="1.7"/><rect x="14.4" y="3.4" width="6.2" height="6.2" rx="1.8" stroke="currentColor" stroke-width="1.7"/><rect x="8.9" y="14.4" width="6.2" height="6.2" rx="1.8" stroke="currentColor" stroke-width="1.7"/><path d="M9.6 6.5H14.4M6.5 9.6V12.2C6.5 13.2 7.3 14 8.3 14H9M17.5 9.6V12.2C17.5 13.2 16.7 14 15.7 14H15" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>`,

  ops: `<svg viewBox="0 0 24 24" width="19" height="19" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M2.6 12H6.6L9.4 5.4L13.4 18.6L16 12H21.4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>`
}

export const NAV_ITEMS = [
  { value: 'chat', label: '对话', hint: '对话工作台（⌘1）', icon: ICONS.chat },
  { value: 'creative', label: '创作', hint: '图片与视频生成（⌘2）', icon: ICONS.creative },
  { value: 'knowledge', label: '知识库', hint: '文档管理与检索测试（⌘3）', icon: ICONS.knowledge },
  { value: 'expert', label: '专家', hint: '专家 / 技能 / 市场（⌘4）', icon: ICONS.expert },
  { value: 'memory', label: '记忆', hint: '长期记忆（⌘5）', icon: ICONS.memory },
  { value: 'auto', label: '自动化', hint: '工作流 / DAG / 定时任务（⌘6）', icon: ICONS.auto },
  { value: 'ops', label: '运维', hint: '监控与成本 / 调用链 / 路由（⌘7）', icon: ICONS.ops }
]

/** 快捷键 ⌘1..⌘7 到 mode 的映射，App 里注册 */
export const NAV_SHORTCUTS = NAV_ITEMS.reduce((acc, item, i) => {
  acc[String(i + 1)] = item.value
  return acc
}, {})
