package com.dcdr.agent.common.util;

import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.Map;

/**
 * 共享 WebClient 构建工厂。
 * <p>
 * 默认 {@link WebClient.Builder} 使用 Reactor Netty 默认连接池, 连接空闲后不会被主动回收。
 * 当上游服务端(如 DashScope)关闭空闲连接后, 池中残留的"死连接"再次被复用时即抛出
 * {@code java.net.SocketException: Connection reset}。
 * <p>
 * 此处通过自定义 {@link ConnectionProvider}:
 * <ul>
 *   <li>{@code maxIdleTime} 在 acquire 时校验, 空闲超时的连接直接淘汰并新建, 杜绝复用死连接;</li>
 *   <li>{@code maxLifeTime} 限制连接总寿命, 避免长连接随时间劣化;</li>
 *   <li>{@code evictInBackground} 后台定时清理, 不依赖下一次请求触发回收。</li>
 * </ul>
 * 取值刻意小于主流网关的空闲超时(通常 30~60s), 确保先于服务端淘汰本地侧连接。
 */
public final class WebClientFactory {

    /** 空闲连接最大保留时长(小于上游网关空闲超时)。 */
    private static final Duration MAX_IDLE_TIME = Duration.ofSeconds(20);
    /** 连接最大存活时长。 */
    private static final Duration MAX_LIFE_TIME = Duration.ofSeconds(60);
    /** 后台清理周期。 */
    private static final Duration EVICT_INTERVAL = Duration.ofSeconds(15);
    /** 建连超时。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 单连接最大占用等待时长。 */
    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(30);

    private WebClientFactory() {
    }

    /**
     * 构建 WebClient, 复用同一个连接池配置。
     *
     * @param baseUrl      基础地址(可为 null, 由调用方在 per-request 指定 uri)
     * @param maxResponseTimeout 响应总超时(用于长连接场景, 传 null 则不设全局响应超时)
     * @param defaultHeaders 默认请求头(可为 null)
     */
    public static WebClient build(String baseUrl, Duration maxResponseTimeout, Map<String, String> defaultHeaders) {
        ConnectionProvider provider = ConnectionProvider.builder("agent-http-pool")
                .maxConnections(50)
                .maxIdleTime(MAX_IDLE_TIME)
                .maxLifeTime(MAX_LIFE_TIME)
                .pendingAcquireTimeout(PENDING_ACQUIRE_TIMEOUT)
                .evictInBackground(EVICT_INTERVAL)
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .compress(true);
        if (maxResponseTimeout != null) {
            httpClient = httpClient.responseTimeout(maxResponseTimeout);
        }

        WebClient.Builder builder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (defaultHeaders != null) {
            defaultHeaders.forEach(builder::defaultHeader);
        }
        return builder.build();
    }
}
