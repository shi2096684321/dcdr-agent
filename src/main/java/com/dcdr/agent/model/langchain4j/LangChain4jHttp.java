package com.dcdr.agent.model.langchain4j;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;

import java.net.http.HttpClient;

/**
 * LangChain4j 框架 Provider 共用的 HTTP 客户端工厂。
 * <p>
 * 强制 HTTP/1.1(而非 JDK HttpClient 默认的 H2 优先), 消除高频 {@code Connection reset}:
 * <ul>
 *   <li>H2 多路复用: 上游网关(DashScope 等)关闭一条 H2 连接会连带其上<b>所有在途流</b>一起失败
 *       (Http2Connection.shutdown → Stream.cancelImpl → Connection reset), 且网关的"空闲"判定
 *       与客户端不一致(SSE 长流在网关侧可能被判定为空闲)</li>
 *   <li>JDK H2 连接池的过期驱逐是惰性清理, 存在复用竞态 —— 即使 keepalive 收紧到 5s
 *       仍无法消除窗口期</li>
 *   <li>H1.1 下一条连接同一时刻仅一个在途请求, 池按空闲时间驱逐(5s), 行为可预测</li>
 * </ul>
 * LLM 调用是低 QPS 长时延场景, H2 多路复用无收益, 强制 H1.1 无代价。
 * <p>
 * 注意: JdkHttpClient 构造时会将 connectTimeout 应用到传入的 builder 后再 build,
 * 模型层 {@code .timeout()} 作为请求级超时不受影响。
 */
public final class LangChain4jHttp {

    private LangChain4jHttp() {
    }

    /** 强制 HTTP/1.1 的 JdkHttpClientBuilder, 各框架 Provider 的模型 builder 统一注入 */
    public static JdkHttpClientBuilder forcedHttp11() {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1));
    }
}
