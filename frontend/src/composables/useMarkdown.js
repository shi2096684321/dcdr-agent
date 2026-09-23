import MarkdownIt from 'markdown-it'
import { canPreviewCode, langLabel } from './useCodeBlock'

// 全局共享一个 markdown-it 实例, 避免每个组件重复构造
const md = new MarkdownIt()

// 开启「复制 / 运行预览」按钮的渲染环境标记。
// 只对对话正文、推理过程开启: 工作流预览等场景直接输出 HTML, 不需要(也不该有)按钮。
const CODE_ACTIONS_ENV = { codeActions: true }

/**
 * 把 markdown-it 生成的 <pre><code> 包一层, 加上语言标签与「复制 / 运行预览」按钮。
 * 按钮不带内联事件(正文由 v-html 注入), 由容器上的事件委托统一处理 —— 见 useCodeBlock。
 */
function wrapCodeBlock(preHtml, lang, code) {
  const label = langLabel(lang)
  const preview = canPreviewCode(lang, code)
  return '<div class="code-block" data-lang="' + label + '">'
    + '<div class="code-block-bar">'
    + '<span class="code-lang">' + label + '</span>'
    + '<span class="code-actions">'
    + '<button type="button" class="code-action code-copy">复制</button>'
    + (preview ? '<button type="button" class="code-action code-preview">运行预览</button>' : '')
    + '</span>'
    + '</div>'
    + preHtml
    + '</div>'
}

// 覆写 fence 规则: 未开启标记时保持原有输出, 保证其它调用方行为不变
const defaultFence = md.renderer.rules.fence
md.renderer.rules.fence = function (tokens, idx, options, env, self) {
  const html = defaultFence(tokens, idx, options, env, self)
  if (!env || !env.codeActions) return html
  const token = tokens[idx]
  return wrapCodeBlock(html, token.info, token.content)
}

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
  let html = md.render(text, CODE_ACTIONS_ENV)
  // stash: 先摘出不应被角标正则改写的片段, 处理完再原样放回
  const stash = []
  const hold = (m) => {
    stash.push(m)
    return ' ZCITE' + (stash.length - 1) + 'Z '
  }
  // 1. 保护代码块: 代码里的 [1] / list[0] / arr[2] 不能被当成引用角标改写,
  //    否则界面上显示错乱, 复制/预览拿到的代码也是错的
  html = html.replace(/<div class="code-block"[\s\S]*?<\/pre>\s*<\/div>/g, hold)
  // 2. 保护行内代码(代码块已摘走, 此处剩下的都是行内 <code>)
  html = html.replace(/<code>[\s\S]*?<\/code>/g, hold)
  // 3. 保护 markdown 链接 [text](url) 与图片 ![alt](url)
  const mdLink = /!?\[[^\]]*\]\([^)]*\)/g
  html = html.replace(mdLink, hold)
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
  /**
   * @param text 原始 Markdown 文本
   * @param opts { codeActions: true } 时, 代码块会带上「复制 / 运行预览」按钮
   */
  const renderMarkdown = (text, opts) => md.render(text || '', opts && opts.codeActions ? CODE_ACTIONS_ENV : {})
  return { md, renderMarkdown, renderBubbleHtml }
}
