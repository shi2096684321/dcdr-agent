package com.dcdr.agent.skill.market;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.common.util.WebClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SkillHub 只读客户端 (V3.1 技能市场)。
 * <p>
 * <b>只做取数, 不做映射</b>: 返回原始 {@link JsonNode} 交给
 * {@link SkillMarketService} 解释。理由是上游同一含义在不同接口里字段名会变
 * (列表 {@code iconUrl} / 搜索 {@code icon_url}), 把"容错"集中在一处比散在客户端更清楚。
 * <p>
 * <b>端点都是实测确认过的</b>(2026-10):
 * <ul>
 *   <li>{@code GET /api/v1/categories} —— 13 个一级分类</li>
 *   <li>{@code GET /api/skills?page&pageSize&keyword&category} —— 分页列表(带 total)</li>
 *   <li>{@code GET /api/v1/search?q&limit} —— 搜索(无 total)</li>
 *   <li>{@code GET /api/v1/skills/{slug}?namespace=} —— 详情(含 securityReports)</li>
 *   <li>{@code GET /api/v1/skills/{slug}/versions?namespace=} —— 版本历史(每版带 securityReports)</li>
 * </ul>
 * 均<b>无需鉴权</b>。反过来说, 这是上游<b>未公开承诺</b>的接口 —— 故所有失败路径都被
 * 收敛成可读的 502/504, 绝不把上游异常原样抛给前端。
 * <p>
 * <b>为什么 WebClient 是惰性构建的</b>: {@link WebClientFactory#build} 内部会创建连接池
 * (含后台淘汰任务)。市场默认关闭, 若在 {@code @PostConstruct} 里无条件构建, 等于
 * "关着也在为它分配资源"。惰性构建让"关闭"真正等于零开销。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillHubClient {

    private final SkillHubProperties props;
    private final ObjectMapper objectMapper;

    /** 惰性构建: 未启用/未使用时为 null, 不留任何连接池 */
    private volatile WebClient client;

    // ===== 端点 =====

    /** 一级分类 */
    public JsonNode categories() {
        return get("/api/v1/categories", null);
    }

    /**
     * 列表(带 total, 支持关键词/分类筛选与排序)。
     *
     * @param sortBy 上游 sortBy 字段名; <b>必须是上游白名单内的值</b>(未知值上游直接 400),
     *               传 null 表示用上游默认顺序。调用方应经 {@link SkillMarketSort} 映射,
     *               不要把用户输入直接传进来
     * @param order  上游 order; 同样只接受 asc/desc, 传 null 表示不传
     */
    public JsonNode list(int page, int pageSize, String keyword, String category,
                         String sortBy, String order) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("page", upstreamPage(page));
        q.put("pageSize", String.valueOf(pageSize));
        q.put("keyword", keyword);
        q.put("category", category);
        q.put("sortBy", sortBy);
        q.put("order", order);
        return get("/api/skills", q);
    }

    /**
     * 页码基准转换: <b>本项目对外用 0 起</b>(与 {@code /api/market/templates} 同口径),
     * <b>但上游要求从 1 开始</b> —— 实测传 {@code page=0} 直接返回 400, 且响应体为空、
     * 没有任何错误说明, 表现为"市场第一页永远打不开"。
     * <p>
     * 顺手兜住负数: 上游对非法值一律 400, 不如在这里就夹进合法区间。
     * <p>
     * 单独抽成方法是为了能被单测钉住 —— 这个差异只存在于上游一侧, 本地任何"看起来对"的
     * 测试都发现不了, 一旦被重构掉就会静默复发。
     */
    static String upstreamPage(int page) {
        // 用 long 中转: page = Integer.MIN_VALUE 时 page + 1 会溢出成 Integer.MAX_VALUE,
        // 那样"夹到合法区间"反而会放行一个荒谬的超大页码
        long p = (long) page + 1L;
        return String.valueOf(p < 1L ? 1L : p);
    }

    /** 关键词搜索(不返回 total) */
    public JsonNode search(String query, int limit) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("q", query);
        q.put("limit", String.valueOf(limit));
        return get("/api/v1/search", q);
    }

    /** 详情; namespace 可空(slug 全局唯一时可不带) */
    public JsonNode detail(String slug, String namespace) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("namespace", namespace);
        return get("/api/v1/skills/" + slug, q);
    }

    /** 版本历史 */
    public JsonNode versions(String slug, String namespace) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("namespace", namespace);
        return get("/api/v1/skills/" + slug + "/versions", q);
    }

    // ===== 内部 =====

    private JsonNode get(String path, Map<String, String> query) {
        Duration timeout = Duration.ofSeconds(props.getTimeoutSeconds());
        String body;
        try {
            body = client().get()
                    .uri(b -> {
                        b.path(path);
                        if (query != null) {
                            query.forEach((k, v) -> {
                                if (v != null && !v.isBlank()) b.queryParam(k, v);
                            });
                        }
                        return b.build();
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    // 稍大于 responseTimeout, 让 reactor 的超时先触发以给出更准的原因
                    .block(timeout.plusSeconds(2));
        } catch (WebClientResponseException e) {
            // 上游明确回了错误码(4xx/5xx): 是"接口变了/参数不对/被限流"的信号, 值得 warn。
            // 刻意带上完整 URI 与响应体片段 —— 上游是<b>无契约的私有接口</b>, 没有这两个信息时
            // 一个裸的 "HTTP 400" 完全无法定位(是页码? 分页上限? 还是接口改了?)。
            // 这不是过度日志: 本次"page=0 返回 400"就是靠它才能一眼看出参数长什么样。
            String bodySnippet = snippet(e.getResponseBodyAsString());
            log.warn("技能市场上游返回异常: uri={}, status={}, body={}",
                    e.getRequest().getURI(), e.getStatusCode().value(),
                    bodySnippet.isEmpty() ? "(空)" : bodySnippet);
            throw new BusinessException(502,
                    "技能市场响应异常 (HTTP " + e.getStatusCode().value() + ")"
                            + (bodySnippet.isEmpty() ? "" : ": " + bodySnippet),
                    "SKILL_HUB_UPSTREAM");
        } catch (WebClientRequestException e) {
            // 连不上/超时/DNS: 外部依赖不可用, 降级为可读提示而不是 500
            log.warn("技能市场不可达: path={}, cause={}", path, e.getMostSpecificCause().getClass().getSimpleName());
            throw new BusinessException(504,
                    "技能市场暂不可用(网络不可达或超时), 请稍后重试", "SKILL_HUB_UNREACHABLE");
        } catch (IllegalStateException e) {
            // block 超时(reactor 抛的 IllegalStateException)
            log.warn("技能市场请求超时: path={}, timeout={}s", path, props.getTimeoutSeconds());
            throw new BusinessException(504,
                    "技能市场响应超时(超过 " + props.getTimeoutSeconds() + "s)", "SKILL_HUB_TIMEOUT");
        }

        if (body == null || body.isBlank()) {
            throw new BusinessException(502, "技能市场返回空响应", "SKILL_HUB_EMPTY");
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            // 解析失败通常意味着上游返回了 HTML(被网关拦截/跳登录页)而不是 JSON
            log.warn("技能市场响应无法解析为 JSON: path={}, 前 120 字符={}",
                    path, body.substring(0, Math.min(120, body.length())));
            throw new BusinessException(502, "技能市场返回了非预期格式", "SKILL_HUB_BAD_PAYLOAD");
        }
    }

    private WebClient client() {
        WebClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = WebClientFactory.build(stripTrailingSlash(props.getBaseUrl()),
                            Duration.ofSeconds(props.getTimeoutSeconds()),
                            Map.of("User-Agent", props.getUserAgent(), "Accept", "application/json"));
                    client = c;
                    log.info("技能市场客户端已就绪: baseUrl={}", props.getBaseUrl());
                }
            }
        }
        return c;
    }

    private static String stripTrailingSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 日志用的响应体片段: 压掉换行并截断, 避免把一整页 HTML 灌进日志 */
    private static String snippet(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= 160 ? s : s.substring(0, 160) + "…";
    }
}
