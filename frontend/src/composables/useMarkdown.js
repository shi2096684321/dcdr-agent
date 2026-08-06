import MarkdownIt from 'markdown-it'

// 全局共享一个 markdown-it 实例, 避免每个组件重复构造
const md = new MarkdownIt()

/**
 * 渲染 Markdown 正文, 并把模型返回的引用标记 [[n]] / 行内 [n]
 * 转换为圆形数字角标(点击跳转到 sources[idx])。
 * 流式输出与最终落库共用此函数, 保证"生成中看到的内容"与"最终内容"完全一致。
 *
 * @param text    原始 Markdown 文本
 * @param sources 引用来源列表(可为空, 为空时不渲染角标跳转)
 * @return 可直接 v-html 的 HTML 字符串
 */
function renderBubbleHtml(text, sources) {
  if (!text) return ''
  const src = sources || []
  let html = md.render(text)
  // 先保护 markdown 链接 [text](url) 与图片 ![alt](url), 避免被角标正则误伤
  const stash = []
  const mdLink = /!?\[[^\]]*\]\([^)]*\)/g
  html = html.replace(mdLink, (m) => {
    stash.push(m)
    return ' ZCITE' + (stash.length - 1) + 'Z '
  })
  // 构建角标 HTML: 圆形数字, data-cite 带序号, data-url 带对应来源链接(供点击跳转)
  const buildCite = (n) => {
    const idx = Number(n) - 1
    const url = (src[idx] && src[idx].url) ? src[idx].url : ''
    return `<a class="cite" data-cite="${n}" href="${url}" target="_blank" rel="noopener noreferrer"><span class="cite-num">${n}</span></a>`
  }
  const doubleBracket = /\[\[(\d+)\]\]/g
  html = html.replace(doubleBracket, (_, n) => buildCite(n))
  const singleBracket = /([\s\S]?)\[(\d+)\](?!\()/g
  html = html.replace(singleBracket, (_, pre, n) => (pre || '') + buildCite(n))
  const restore = / ZCITE(\d+)Z /g
  html = html.replace(restore, (_, i) => stash[Number(i)])
  return html
}

export function useMarkdown() {
  const renderMarkdown = (text) => md.render(text || '')
  return { md, renderMarkdown, renderBubbleHtml }
}
