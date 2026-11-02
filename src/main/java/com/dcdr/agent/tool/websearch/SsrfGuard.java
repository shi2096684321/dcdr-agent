package com.dcdr.agent.tool.websearch;

import java.net.URI;

/**
 * 抓取目标地址的 SSRF 防护(纯静态, 无状态)。
 *
 * <p>原先这段逻辑是 {@code WebExtractTool} 的私有实例方法。抽出来的原因不是"更优雅",
 * 而是<b>现在有两个抓取源都要过同一道闸</b>: 本地抓取直接请求目标地址;
 * DeepSeek Fetch 则是把目标地址发给外部服务去抓 —— 后者虽然读不到内网内容,
 * 但把 {@code 10.0.0.5:8080} 这类内网拓扑发给第三方本身也是信息泄露。
 * 两处各写一份必然漂移, 故收敛到一处。
 *
 * <p><b>能力边界(继承原注释, 别误以为够用)</b>: 这里只校验 <b>IP 字面量</b>
 * 与明显的内网域名, 不做 DNS 解析 —— 解析会引入网络开销, 且无法根治 DNS Rebinding。
 * 如需更强防护, 应在出口代理层做统一管控。
 */
public final class SsrfGuard {

    private SsrfGuard() {
    }

    /**
     * 判断该 URL 是否允许被抓取。
     *
     * @param url     目标地址
     * @param enabled 是否启用防护; 关闭时一律放行(仅在需要抓内网站点时关闭)
     */
    public static boolean isSafe(String url, boolean enabled) {
        if (!enabled) {
            return true;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String h = host.toLowerCase();
            if ("localhost".equals(h) || h.endsWith(".localhost")
                    || h.endsWith(".local") || h.endsWith(".internal")) {
                return false;
            }
            return !isBlockedIpLiteral(h);
        } catch (Exception e) {
            return false;
        }
    }

    /** 判断是否为应被拦截的内网/环回 IP 字面量(IPv4 与常见 IPv6) */
    private static boolean isBlockedIpLiteral(String host) {
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] parts = host.split("\\.");
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 0 || a == 127 || a == 10
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 169 && b == 254);
        }
        if (host.contains(":")) {
            return "::1".equals(host) || host.startsWith("fe80:")
                    || host.startsWith("fc") || host.startsWith("fd");
        }
        return false;
    }
}
