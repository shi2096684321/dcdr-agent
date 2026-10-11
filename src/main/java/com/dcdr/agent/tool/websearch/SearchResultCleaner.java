package com.dcdr.agent.tool.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SearXNG 搜索结果清洗器(V3.2)。
 * <p>
 * <b>为什么需要它</b>: SearXNG 返回的是<b>搜索引擎原始结果</b>(不像 Tavily 已为 LLM 做过清洗),
 * 实测一个中文查询的 20 条结果里近一半是「聚合页 / 列表页 / 标签页 / 视频页」——
 * 它们的摘要只是多条标题的堆砌、且无法提取正文, 既污染模型上下文又白占结果位。
 * <p>
 * 因此这里做一条清洗管线: <b>过滤非文章页 → 清洗 URL / 标题 / 摘要 → 去重</b>。
 * 纯静态工具类, 无状态, 便于单测。
 */
@Slf4j
public final class SearchResultCleaner {

    private SearchResultCleaner() {
    }

    /** 单条摘要最大长度 */
    private static final int SNIPPET_MAX = 200;

    // ===================== 过滤规则 =====================

    /**
     * 非文章页的路径特征: 标签 / 栏目 / 话题 / 列表 / 频道 / 视频 / 搜索页等聚合形态。
     * <p>要求特征词后紧跟分隔符或结尾, 以免误伤 {@code /newsroom}、{@code /newsDetail_xxx}
     * 这类真实文章路径(实测 {@code m.thepaper.cn/newsDetail_forward_xxx} 不会被误判)。
     */
    private static final Pattern NON_ARTICLE_PATH = Pattern.compile(
            "/(?:tag|tags|topic|topics|category|categories|channel|channels|list|lists"
                    + "|news|video|videos|search|special|zhuanti|column|columns)(?:[/.\\-]|$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 单层短路径(如 {@code /ai}、{@code /tech}): 无扩展名、无数字, 通常是栏目/频道首页。
     * <p>具体文章的 URL 一般含数字 ID、日期或更深层级(实测
     * {@code /newsDetail_forward_34218660}、{@code /rain/a/20260915A07TH600} 均不会被误判),
     * 故该规则误伤面很小。
     */
    private static final Pattern SHALLOW_SECTION_PATH = Pattern.compile("^/[^/.\\d]{1,20}$");

    /** 标题中的聚合页特征词(与路径特征互补 —— 有些聚合页 URL 很干净, 只能靠标题识别) */
    private static final Pattern AGGREGATE_TITLE = Pattern.compile(
            "最新资讯|资讯列表|实时滚动|滚动快讯|快讯聚合|信息聚合|话题聚合|栏目|专题页|排行榜|列表页");

    /** 短视频/社交聚合域名: 页面无法提取正文, 摘要多为话题标签堆砌 */
    private static final Set<String> BLOCKED_HOSTS = Set.of("page.sm.cn");

    // ===================== 清洗规则 =====================

    /** 需剥离的追踪参数(对模型零价值却极占 token —— 实测有 700+ 字符的 URL) */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "uc_param_str", "wm_cid", "wm_id", "referfrom", "spm", "bucket",
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "share_token", "from_source", "saref", "s_trans", "s_channel", "from");

    /** 标题尾部的站点后缀: " | 雷锋网" / "_光明网" / " - 百家号" / "--科技改变未来" */
    private static final Pattern TITLE_SUFFIX = Pattern.compile(
            "\\s*(?:\\||_|-{1,2}|–|—)\\s*[^\\s|_\\-–—][^|_\\-–—]{0,14}$");

    /** 话题标签(#xxx) 与 @账号(短视频标题里常见) */
    private static final Pattern HASH_TAG = Pattern.compile("#[^\\s#]{1,20}");
    private static final Pattern AT_MENTION = Pattern.compile("@[^\\s@]{1,20}");

    /** 尾部省略号(引擎截断痕迹) */
    private static final Pattern TRAILING_ELLIPSIS = Pattern.compile("[.．。…]{2,}\\s*$");

    /** 摘要开头的时间前缀: "10 小时之前 · " / "2026年9月15日 · " / "2026-09-15 · " */
    private static final Pattern SNIPPET_TIME_PREFIX = Pattern.compile(
            "^(?:\\d+\\s*(?:秒|分钟|小时|天|周|个?月|年)之前"
                    + "|\\d{4}年\\d{1,2}月\\d{1,2}日"
                    + "|\\d{4}-\\d{1,2}-\\d{1,2}"
                    + "|\\d{1,2}月\\d{1,2}日)\\s*[·・|｜]\\s*");

    /** 列表页堆砌特征: 摘要里出现 "2026-09-15 18:35:02" 这类带时间的条目 */
    private static final Pattern LIST_PAGE_TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?");

    /**
     * 清洗主入口。
     *
     * @param rawResults SearXNG 响应中 {@code results} 数组的元素
     * @param limit      期望返回条数; 因过滤/去重会淘汰部分条目, 故会继续向后扫描补齐
     * @return 清洗后的来源卡片({@code index/title/url/snippet}), {@code index} 从 1 重新编号
     */
    public static List<Map<String, Object>> clean(List<JsonNode> rawResults, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rawResults == null || rawResults.isEmpty() || limit <= 0) {
            return out;
        }
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        int filtered = 0;
        int duplicated = 0;

        for (JsonNode r : rawResults) {
            if (out.size() >= limit) {
                break;
            }
            String rawUrl = r.path("url").asText("").trim();
            String rawTitle = r.path("title").asText("").trim();
            if (rawUrl.isEmpty() || rawTitle.isEmpty()) {
                filtered++;
                continue;
            }
            if (isNonArticle(rawUrl, rawTitle)) {
                filtered++;
                continue;
            }
            String url = cleanUrl(rawUrl);
            String title = cleanTitle(rawTitle);
            if (url.isEmpty() || title.isEmpty()) {
                filtered++;
                continue;
            }
            // 去重: 规范化 URL + 规范化标题(拦截「同一新闻多来源转载」)
            if (!seenUrls.add(urlKey(url)) || !seenTitles.add(titleKey(title))) {
                duplicated++;
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", out.size() + 1);
            source.put("title", title);
            source.put("url", url);
            source.put("snippet", clip(buildSnippet(r), SNIPPET_MAX));
            out.add(source);
        }
        if (filtered > 0 || duplicated > 0) {
            log.info("搜索结果清洗: 原始 {} 条 → 过滤非文章页 {} 条 / 去重 {} 条 / 保留 {} 条",
                    rawResults.size(), filtered, duplicated, out.size());
        }
        return out;
    }

    // ===================== 过滤实现 =====================

    private static boolean isNonArticle(String url, String title) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host != null && BLOCKED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                return true;
            }
            String path = uri.getPath();
            if (path != null
                    && (NON_ARTICLE_PATH.matcher(path).find()
                            || SHALLOW_SECTION_PATH.matcher(path).matches())) {
                return true;
            }
        } catch (Exception ignored) {
            // URL 非法时不据此丢弃, 交由后续清洗环节处理
        }
        return AGGREGATE_TITLE.matcher(title).find();
    }

    // ===================== 清洗实现 =====================

    /** 摘要 = 清洗后的引擎正文摘要, 若引擎给了发布时间则附在后面(供模型判断时效) */
    private static String buildSnippet(JsonNode raw) {
        String snippet = cleanSnippet(raw.path("content").asText(""));
        String published = raw.path("publishedDate").asText("");
        if (published.isBlank() || "null".equalsIgnoreCase(published)) {
            return snippet;
        }
        return snippet.isEmpty() ? "发布于 " + published : snippet + " (发布于 " + published + ")";
    }

    /** 去掉 hash 片段与追踪参数, 并去尾斜杠 */
    public static String cleanUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash > 0) {
            u = u.substring(0, hash);
        }
        int q = u.indexOf('?');
        if (q > 0) {
            String base = u.substring(0, q);
            List<String> kept = new ArrayList<>();
            for (String pair : u.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                // 无键值对的参数(如 "?COM") 属于噪声, 直接丢弃
                if (eq <= 0) {
                    continue;
                }
                String key = pair.substring(0, eq).toLowerCase(Locale.ROOT);
                if (!TRACKING_PARAMS.contains(key)) {
                    kept.add(pair);
                }
            }
            u = kept.isEmpty() ? base : base + "?" + String.join("&", kept);
        }
        if (u.length() > 8 && u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** 去掉话题标签 / @账号 / 尾部省略号 / 站点后缀 */
    public static String cleanTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String t = HASH_TAG.matcher(title).replaceAll("");
        t = AT_MENTION.matcher(t).replaceAll("");
        t = TRAILING_ELLIPSIS.matcher(t).replaceAll("");
        t = TITLE_SUFFIX.matcher(t).replaceAll("");
        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * 清洗摘要: 去时间前缀、去截断省略号、压缩空白。
     * <p>若判定为「列表页堆砌」(出现 2 个以上完整时间戳) 则整体丢弃 —— 这类摘要
     * 是多条新闻标题的堆砌, 对模型纯属噪声。
     */
    public static String cleanSnippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Matcher m = LIST_PAGE_TIMESTAMP.matcher(content);
        int count = 0;
        while (m.find()) {
            if (++count >= 2) {
                return "";
            }
        }
        String s = SNIPPET_TIME_PREFIX.matcher(content.trim()).replaceAll("");
        s = TRAILING_ELLIPSIS.matcher(s).replaceAll("");
        return s.replaceAll("\\s+", " ").trim();
    }

    // ===================== 去重 key =====================

    /** URL 规范化: 去协议与 www 前缀并小写, 使 http/https、www 变体视为同一条 */
    private static String urlKey(String url) {
        String k = url.toLowerCase(Locale.ROOT);
        k = k.replaceFirst("^https?://", "");
        k = k.replaceFirst("^www\\.", "");
        return k;
    }

    /** 标题规范化: 去标点与空白后小写, 用于识别「不同来源的同一新闻」 */
    private static String titleKey(String title) {
        return title.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s，。、！？：；“”‘’（）【】《》]+", "");
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
