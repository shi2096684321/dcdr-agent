/**
 * 对话正文内代码块的「复制 / 运行预览」支持。
 *
 * 背景: 正文是 markdown-it 渲染后经 v-html 注入的静态 HTML, 无法在其上直接挂 Vue 事件,
 * 因此采用「渲染时注入按钮 + 容器事件委托」的方案:
 *   - useMarkdown 的 fence 规则把每个代码块包成 .code-block(带 .code-copy / .code-preview 按钮)
 *   - 组件容器上挂一次 @click="handleCodeClick", 由 closest 定位按钮, 从 DOM 读取代码原文
 * 代码原文只在点击时从 <code> 读取, 不写入 data 属性, 免去转义与体积问题。
 */

/** 浏览器里可直接运行预览的语言 */
const PREVIEWABLE = new Set(['html', 'htm', 'svg', 'css', 'js', 'javascript', 'mjs'])
/** 本身就是完整文档, 直接作为 srcdoc */
const HTML_LANG = new Set(['html', 'htm'])
const SVG_LANG = new Set(['svg'])
const CSS_LANG = new Set(['css'])
const JS_LANG = new Set(['js', 'javascript', 'mjs'])

/** 语言标识归一化: 取 info 串首个词, 仅保留可安全放进 class/data 属性的字符 */
export function normalizeLang(raw) {
  const lang = String(raw == null ? '' : raw).trim().split(/\s+/)[0].toLowerCase()
  return lang.replace(/[^\w+#-]/g, '')
}

/** 内容嗅探: 以常见标签开头时按 HTML 片段处理(模型常不写 ```html 语言标识) */
export function looksLikeMarkup(code) {
  return /^\s*<(?:!doctype|html|head|body|div|p|span|section|article|header|footer|h[1-6]|table|ul|ol|li|form|input|button|a|img|svg|canvas|style|script)\b/i
    .test(String(code == null ? '' : code))
}

/** 该代码块是否提供「运行预览」按钮: 已知可预览语言, 或语言未知但内容像标记语言 */
export function canPreviewCode(lang, code) {
  const l = normalizeLang(lang)
  if (PREVIEWABLE.has(l)) return true
  if (!l || l === 'text') return looksLikeMarkup(code)
  return false
}

/** 语言展示名(取不到时显示 text) */
export function langLabel(lang) {
  return normalizeLang(lang) || 'text'
}

/** 内联脚本/样式中若含结束标签会提前截断文档, 需转义(只转义当次内联的内容) */
function safeInline(code, tag) {
  return String(code == null ? '' : code).replace(new RegExp('</' + tag, 'gi'), '<\\/' + tag)
}

const PAGE_STYLE = `
  html,body{margin:0;padding:0}
  body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
       color:#1f2937;background:#fff;padding:16px;min-height:100vh;box-sizing:border-box}
  .__console{margin:16px 0 0;padding:12px;background:#0f172a;color:#e2e8f0;border-radius:10px;
             font-family:Consolas,Monaco,"Courier New",monospace;font-size:12.5px;line-height:1.6;white-space:pre-wrap;word-break:break-word}
  .__console:empty{display:none}
  .__console .__err{color:#fca5a5}
  .__console .__warn{color:#fcd34d}
`
/** CSS 预览的示例 DOM: 让纯样式代码有东西可作用 */
const CSS_SAMPLE = `
  <h1>标题 H1</h1>
  <p>正文段落示例，用于预览字号、行高与颜色。</p>
  <button>按钮</button>
  <ul><li>列表项 1</li><li>列表项 2</li></ul>
  <div class="box">.box 示例容器</div>
  <a href="javascript:void 0">链接示例</a>
`

/** 组装预览页面 */
function page({ body = '', css = '', js = '', captureConsole = false }) {
  const capture = captureConsole
    ? `<script>
(function(){
  var out = document.getElementById('__console');
  function fmt(v){
    if (typeof v === 'string') return v;
    try { return JSON.stringify(v, null, 2); } catch (e) { return String(v); }
  }
  function write(cls, args){
    var line = document.createElement('div');
    if (cls) line.className = cls;
    line.textContent = Array.prototype.map.call(args, fmt).join(' ');
    out.appendChild(line);
  }
  ['log','info','debug'].forEach(function(k){ var o = console[k].bind(console); console[k] = function(){ write('', arguments); o.apply(null, arguments); }; });
  console.warn = (function(o){ return function(){ write('__warn', arguments); o.apply(null, arguments); }; })(console.warn.bind(console));
  console.error = (function(o){ return function(){ write('__err', arguments); o.apply(null, arguments); }; })(console.error.bind(console));
  window.addEventListener('error', function(e){ write('__err', [String(e.message)]); });
  window.addEventListener('unhandledrejection', function(e){ write('__err', ['Unhandled rejection: ' + fmt(e.reason)]); });
})();
<\/script>`
    : ''

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>${PAGE_STYLE}${css ? safeInline(css, 'style') : ''}</style>
</head>
<body>
${body}
<div id="__console" class="__console"></div>
${capture}
${js ? '<script>' + safeInline(js, 'script') + '<\/script>' : ''}
</body>
</html>`
}

/**
 * 由代码块生成可直接用于 iframe srcdoc 的完整 HTML 文档。
 * - html: 原样作为页面(模型常给出完整页面, 也可能只是片段)
 * - svg : 居中包裹
 * - css : 注入示例 DOM 之上, 便于看效果
 * - js  : 空页面执行, 并回显 console / 运行时错误
 */
export function buildPreviewDoc(code, lang) {
  const body = code == null ? '' : String(code)
  let l = normalizeLang(lang)
  if (!l || l === 'text') l = looksLikeMarkup(body) ? 'html' : 'text'

  if (HTML_LANG.has(l)) return body
  if (SVG_LANG.has(l)) {
    return page({
      body: `<div style="display:flex;align-items:center;justify-content:center;min-height:calc(100vh - 32px)">${body}</div>`
    })
  }
  if (CSS_LANG.has(l)) return page({ body: CSS_SAMPLE, css: body })
  if (JS_LANG.has(l)) return page({ js: body, captureConsole: true })
  return page({
    body: '<p style="color:#6b7280">该语言暂不支持在线运行预览，可先复制代码到本地运行。</p>'
  })
}

/** 复制到剪贴板: 优先 Clipboard API, 非安全上下文/被拒绝时降级 execCommand */
export async function copyText(text) {
  const s = text == null ? '' : String(text)
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(s)
      return true
    }
  } catch (e) { /* 降级 */ }
  try {
    const ta = document.createElement('textarea')
    ta.value = s
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.top = '-1000px'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch (e) {
    return false
  }
}

/** 按钮文案临时反馈(按钮由 v-html 注入, 直接改 DOM 即可) */
function flashLabel(btn, text) {
  if (!btn.dataset.originLabel) btn.dataset.originLabel = btn.textContent
  btn.textContent = text
  btn.classList.add('is-done')
  clearTimeout(btn.__flashTimer)
  btn.__flashTimer = setTimeout(() => {
    btn.textContent = btn.dataset.originLabel
    btn.classList.remove('is-done')
  }, 1500)
}

/**
 * 代码块按钮的事件委托处理器, 挂在渲染正文的容器上。
 * @param {(payload: {code: string, lang: string}) => void} onPreview 点击「运行预览」时回调
 */
export function useCodeBlock(onPreview) {
  async function handleCodeClick(e) {
    const target = e.target instanceof Element ? e.target : null
    if (!target) return
    const btn = target.closest('.code-copy, .code-preview')
    if (!btn) return
    e.stopPropagation()
    const block = btn.closest('.code-block')
    const codeEl = block && block.querySelector('pre code')
    if (!codeEl) return
    const code = codeEl.textContent || ''
    const lang = (block.dataset && block.dataset.lang) || ''
    if (btn.classList.contains('code-copy')) {
      const ok = await copyText(code)
      flashLabel(btn, ok ? '✓ 已复制' : '复制失败')
    } else if (typeof onPreview === 'function') {
      onPreview({ code, lang })
    }
  }
  return { handleCodeClick }
}
