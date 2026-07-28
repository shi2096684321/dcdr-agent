import MarkdownIt from 'markdown-it'

// 全局共享一个 markdown-it 实例, 避免每个组件重复构造
const md = new MarkdownIt()

export function useMarkdown() {
  const renderMarkdown = (text) => md.render(text || '')
  return { md, renderMarkdown }
}
