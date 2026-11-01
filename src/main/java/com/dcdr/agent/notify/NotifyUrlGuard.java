package com.dcdr.agent.notify;

import java.net.URI;
import java.util.Locale;

/**
 * Webhook 地址校验（SSRF 防护）。
 * <p>
 * 为什么抽成公共类：同一个校验要在<b>两处</b>执行 —— 保存用户配置时（拒绝脏数据进库）、
 * 以及真正发请求前（纵深防御，防绕过写入路径）。两处各写一份必然会漂移，
 * 一旦漂移就是"配的时候能过、发的时候被拦"或反过来。
 * <p>
 * 校验口径一律基于 {@link URI#getHost()}：{@code https://qyapi.weixin.qq.com@evil.com/x}
 * 的 host 是 evil.com，用字符串 {@code contains} 判断等于没判断。
 */
public final class NotifyUrlGuard {

    /** 地址长度上限，防超长垃圾数据 */
    private static final int MAX_URL_LENGTH = 2000;

    private NotifyUrlGuard() {}

    /**
     * 校验 Webhook 地址是否允许被服务端请求。
     *
     * @return {@code null} 表示通过；否则为可直接展示给用户的拒绝原因
     */
    public static String check(NotifyChannel channel, String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "Webhook 地址不能为空";
        if (rawUrl.length() > MAX_URL_LENGTH) return "Webhook 地址过长";
        if (channel == null) return "无法识别的通知渠道";

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (Exception e) {
            return "Webhook 地址格式不正确";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "Webhook 地址必须是 https";
        }
        if (uri.getUserInfo() != null) {
            return "Webhook 地址不允许包含用户信息 (@ 前的内容)";
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            return "Webhook 地址端口只能是 443";
        }
        String allowed = channel.allowedHost();
        String host = uri.getHost();
        if (host == null || !allowed.equals(host.toLowerCase(Locale.ROOT))) {
            return "Webhook 域名必须是 " + allowed + ", 收到: " + host;
        }
        return null;
    }
}
