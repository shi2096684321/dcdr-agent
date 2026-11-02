package com.dcdr.agent.tool.websearch;

import com.chimbori.crux.articles.Article;
import com.chimbori.crux.articles.ArticleExtractor;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * HTML → 正文 Markdown 的抽取器(纯静态, 不涉及任何网络)。
 *
 * <p><b>为什么从 {@code WebExtractTool} 搬出来</b>: 引入 DeepSeek Fetch 后,
 * 抓取变成"一个可替换的策略", 而这条 crux 链路是<b>本地策略的实现细节</b>,
 * 不是工具的职责。留在 Tool 里会导致: 抓取源一换, 这段 400 行的核心逻辑
 * 就被晾在一个不再被调用的类里; 或者相反 —— 新抓取源为了复用去反向依赖 Tool 门面。
 *
 * <p><b>本次只做搬迁, 不改行为</b>: 方法体、注释、阈值全部原样保留,
 * 唯一的改动是类名与 {@link FetchedPage} 作为返回值(replaces 原来的嵌套 record)。
 * {@code WebExtractFixtureTest} 仍通过 {@code WebExtractTool.extractFromHtml} 委托进来,
 * 所以那批 fixture 用例继续有效 —— 它们是这条链路唯一的回归防线。
 *
 * <p>链路: {@code jsoup 解析(自动识别 charset, 解决中文站 GBK 乱码)
 * → crux ArticleExtractor 提取正文(去导航/广告/页脚) → flexmark 转 Markdown}。
 * 其中还有一步"结构化重建", 原因见 {@link #rebuildFromBodyContainer} 的注释。
 */
@Slf4j
public final class HtmlArticleExtractor {

    /**
     * HTML → Markdown 转换器。
     * <p>{@code FlexmarkHtmlConverter} 内部无会话状态, 每次 {@code convert} 自建上下文,
     * 故静态持有是安全的。
     */
    private static final FlexmarkHtmlConverter MD_CONVERTER = FlexmarkHtmlConverter.builder().build();

    /**
     * 网页正文里的「列表符号」—— 它们来自 {@code <ul><li>} 被提取后的纯文本残留。
     * <p>用 Unicode 码点转义写法而非直接输入字符, 避免源码编码差异导致漏匹配。
     * <p>刻意<b>不含</b> U+00B7(中文间隔号, 如「迈克尔·乔丹」), 避免误删人名。
     * <p>注意: 注释中不能出现反斜杠加 u 的转义写法 —— Java 的词法预解析对注释同样生效,
     * 非法转义会直接编译失败(本项目已踩过)。
     */
    private static final String BULLET_CHARS =
            "\u2022\u2023\u2043\u2219\u25aa\u25ab\u25cf\u25e6\u30fb\uff65";

    private HtmlArticleExtractor() {
    }

    /**
     * 从<b>已拿到的 HTML 字节</b>中抽取正文。
     *
     * <p><b>为什么入参是字节而不是字符串</b>: 交给 jsoup 依据 {@code <meta charset>}/BOM
     * 自动判定编码, 避免中文站点(GBK/GB2312) 被按 UTF-8 解码成乱码。
     *
     * @param raw      原始响应字节
     * @param url      页面地址(仅用于解析相对链接与兜底标题)
     * @param maxChars 正文最大长度
     * @return 抽取结果; 正文为空(纯 JS 渲染页 / 空壳页)时返回 {@code null}
     */
    public static FetchedPage extract(byte[] raw, String url, int maxChars) {
        try {
            Document doc = Jsoup.parse(new ByteArrayInputStream(raw), null, url);
            // crux 会**原地修改**传入的文档, 故先留一份原件 —— 结构化重建要拿它做基准
            Document original = doc.clone();
            HttpUrl httpUrl = HttpUrl.get(url);
            Article article = new ArticleExtractor(httpUrl, doc)
                    .extractMetadata()
                    .extractContent()
                    .estimateReadingTime()
                    .getArticle();
            String rawTitle = article.getTitle() != null && !article.getTitle().isBlank()
                    ? article.getTitle() : hostOf(url);
            // 复用搜索侧的标题清洗(去话题标签/尾部省略号/站点后缀, 如 "_光明网")
            String title = SearchResultCleaner.cleanTitle(rawTitle);
            String markdown = toMarkdown(article);
            if (markdown.isBlank()) {
                return null;
            }
            String structural = rebuildFromBodyContainer(original, article.getDocument());
            if (structural != null && structural.length() > markdown.length()) {
                // 只在**严格更长**时采用: 结构化版本保留了标题与代码块, 但它多走了
                // "容器定位"这一步 —— 容器若框小了(只圈住开头一两段), 结果会比 crux 的
                // 段落级提取还少。用"必须更长"作为硬门槛, 保证这条路只会变好不会变差。
                markdown = structural;
            }
            return new FetchedPage(title, clip(markdown, maxChars));
        } catch (Exception e) {
            log.warn("正文抽取失败: url={}, cause={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 用 crux 的段落做锚点, 在<b>原始文档</b>里定位正文容器, 重建保留结构的 Markdown。
     *
     * <p><b>为什么必须补这一步</b>: 实测 crux 4.0 的 {@code extractContent()}
     * <b>会丢弃所有标题(h1~h6)与代码块(pre)</b> —— 一个含 3 个 h2、2 个 pre 的技术博客,
     * 抽完只剩段落文本。对"这个错误怎么解决""怎么配置 XX"这类查询, 模型拿到的就是
     * <b>没有代码的答案</b>, 而外部表现完全正常(有正文、长度也不短), 无从察觉。
     * 该行为关不掉: {@code ArticleExtractor} 只有 extractMetadata/extractContent/estimateReadingTime
     * 三个无参步骤, 没有保留标签的选项。
     *
     * <p><b>为什么用 crux 的结果当锚点, 而不是自己重跑一遍 readability</b>:
     * crux 已经准确判断出"哪些段落属于正文", 这是最难的部分。我们只需要它回答
     * "正文长什么样", 再用它反查"这些段落在原始文档中位于哪个容器", 就能拿到
     * 带完整结构的原始 DOM, 不必自己重新实现一遍噪声剔除。
     *
     * @param original 原始文档(未经 crux 修改)
     * @param cruxDoc  crux 提取后的文档(只含段落, 用作锚点)
     * @return 重建的 Markdown; 无法可靠定位容器时返回 {@code null}, 由调用方退回 crux 结果
     */
    private static String rebuildFromBodyContainer(Document original, Document cruxDoc) {
        if (original == null || cruxDoc == null) {
            return null;
        }
        // 锚点: crux 判定为正文的段落。太短的不要(标题、图注容易被误当锚点)
        List<String> anchors = cruxDoc.select("p").stream()
                .map(e -> e.text().trim())
                .filter(t -> t.length() >= 20)
                .limit(30)
                .toList();
        if (anchors.size() < 2) {
            return null;
        }
        Element first = findAnchorElement(original, anchors.get(0));
        Element last = findAnchorElement(original, anchors.get(anchors.size() - 1));
        if (first == null || last == null) {
            return null;
        }
        // 向上冒泡, 直到该祖先同时包含首尾两个锚点 —— 那就是覆盖整篇正文的最小容器
        String lastText = last.text().trim();
        Element container = first;
        while (container != null && !container.text().contains(lastText)) {
            container = container.parent();
        }
        // 退到 body/html 说明正文分散在多个互不相邻的容器里, 此时容器 = 整页,
        // 硬用会把导航与页脚一起搬进来 —— 交给 crux 的段落级结果更安全
        if (container == null
                || "body".equals(container.tagName()) || "html".equals(container.tagName())) {
            return null;
        }
        Document shell = Jsoup.parse("<html><body></body></html>");
        shell.body().appendChild(container.clone());
        // 容器内仍可能挂着评论区 / 推荐位 / 广告位: crux 的噪声判断是逐块的,
        // 而这里我们整块搬走, 必须自己再清一遍
        shell.select("nav, aside, footer, header, .comment, .comments, .related, .share, "
                + ".ad, .ads, .advert, .recommend, .sidebar").remove();
        // 去掉 id/class: flexmark 会把它们转成 `{#content}` / `{.article}` 这类属性语法,
        // 对模型是纯噪声(且会粘在标题行后面, 看起来像正文的一部分)。
        // 例外是代码语言标注(language-xxx): flexmark 靠它生成 ```java 围栏, 丢了代码就没有语言提示了
        shell.select("[id]").forEach(e -> e.removeAttr("id"));
        shell.select("[class]").forEach(e -> {
            List<String> kept = new ArrayList<>();
            for (String c : e.className().split("\\s+")) {
                if (c.startsWith("language-") || c.startsWith("lang-")) {
                    kept.add(c);
                }
            }
            if (kept.isEmpty()) {
                e.removeAttr("class");
            } else {
                e.attr("class", String.join(" ", kept));
            }
        });
        normalizeDom(shell);
        String html = shell.body() == null ? "" : shell.body().html();
        return normalizeMarkdown(MD_CONVERTER.convert(html));
    }

    /** 在原始文档里找到"包含该锚点文本"的最内层段落级元素 */
    private static Element findAnchorElement(Document original, String anchor) {
        String key = anchor.substring(0, Math.min(20, anchor.length()));
        for (Element el : original.select("p, li, td, blockquote")) {
            if (el.text().trim().contains(key)) {
                return el;
            }
        }
        return null;
    }

    /**
     * 把 crux 提取出的正文 DOM 转成 Markdown。
     * <p>
     * <b>⚠️ 关键坑</b>: crux 4.0 的 {@code Article.document} 是把提取出的正文元素
     * <b>直接挂在 Document 根下</b>(与自动创建的空 {@code <body>} 平级), 因此
     * {@code doc.body().html()} 恒为空 —— 直接取 body 会得到空正文。
     * 必须先重新解析一次(jsoup 会把游离的元素归入 body), 再取其 body 内容。
     * 实测: 不重新解析 → Markdown 长度 0; 重新解析后 → 正常提取。
     */
    private static String toMarkdown(Article article) {
        try {
            Document clean = article.getDocument();
            if (clean == null) {
                return "";
            }
            // 1) 重新解析: crux 把正文元素挂在根下(见上方说明), 借 jsoup 归位到 body
            Document normalized = Jsoup.parse(clean.html());
            // 2) DOM 规范化: 去无用标签、展开嵌套 <p>、删空元素
            normalizeDom(normalized);
            String html = normalized.body() != null ? normalized.body().html() : clean.html();
            // 3) 转 Markdown 后再做文本级清洗
            return normalizeMarkdown(MD_CONVERTER.convert(html));
        } catch (Exception e) {
            log.warn("正文转 Markdown 失败: {}", e.getMessage());
            return "";
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * DOM 规范化: crux 的输出常见 {@code <p>} 嵌套与空元素, 会让 Markdown 转换产生大量碎片
     * (实测出现 {@code <p><p>public</p><p>class</p> HelloWorld</p>} 这类把代码块拆散的结构)。
     * <p>处理: 去掉无内容价值的标签 → 展开外层嵌套 {@code <p>} → 删除空元素。
     */
    private static void normalizeDom(Document doc) {
        Element body = doc.body();
        if (body == null) {
            return;
        }
        body.select("script, style, noscript, iframe, svg, form, button, input").remove();
        // <p><p>x</p></p> → <p>x</p>: 展开外层 p(移除其标签但保留子节点)
        body.select("p:has(> p)").forEach(Element::unwrap);
        // 删除纯空白且不含图片的空元素(先收集再删, 避免遍历时修改 DOM)
        body.select("p, div, span, li, td").stream()
                .filter(e -> e.text().isBlank() && e.select("img").isEmpty())
                .toList()
                .forEach(Element::remove);
    }

    /**
     * Markdown 文本清洗:
     * <ol>
     *   <li>去 NBSP</li>
     *   <li>去列表符号残留({@code •} 等非 Markdown 标准符号)</li>
     *   <li>去 Markdown 转义符({@code \[}、{@code \>} 等) —— 对 LLM 而言转义符是纯噪声</li>
     *   <li>去行尾空白、压缩连续空行</li>
     * </ol>
     */
    private static String normalizeMarkdown(String md) {
        if (md == null) {
            return "";
        }
        return stripBullets(md.replace('\u00a0', ' '))
                .replaceAll("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>~|])", "$1")
                .replaceAll("[ \\t]+(\\R)", "$1")
                .replaceAll("\\R{3,}", "\n\n")
                .trim();
    }

    /**
     * 逐个字符删除列表符号及其后随的一个空格。
     * <p>相较「逐行判断行首是否以列表符号开头」的写法, 这里不依赖行首定位、也不依赖
     * 空白判定, 实现更直白可靠(前者曾在实测中静默失效)。
     */
    private static String stripBullets(String md) {
        StringBuilder sb = new StringBuilder(md.length());
        for (int i = 0; i < md.length(); i++) {
            char ch = md.charAt(i);
            if (BULLET_CHARS.indexOf(ch) < 0) {
                sb.append(ch);
                continue;
            }
            // 命中列表符号: 跳过符号本身; 若其后紧跟一个半角空格也一并跳过
            if (i + 1 < md.length() && md.charAt(i + 1) == ' ') {
                i++;
            }
        }
        return sb.toString();
    }

    /** 超长截断; 全空返回空串而非 null, 免得调用方到处判空 */
    public static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
