package com.dcdr.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Java AI Agent 应用启动入口
 * <p>
 * 多模态智能对话助手 - 支持对话模式与创作模式(Banana风格)
 * <p>
 * 排除 {@link ReactiveUserDetailsServiceAutoConfiguration}: V2.2 使用 JWT 无状态认证,
 * 不需要 Spring Security 默认的 UserDetailsService (会生成随机密码的默认用户)。
 */
@SpringBootApplication(exclude = ReactiveUserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class JavaAgentApplication {

    public static void main(String[] args) {
        // V3.0 框架化改造: LangChain4j 底层使用 JDK HttpClient 连接池(不经过 WebClientFactory),
        // 限制空闲 keep-alive 连接存活时间, 避免上游网关(DashScope 等)关闭空闲连接后
        // 复用死连接抛 Connection reset。
        // keepalive 5s 之后仍高频出现 reset, 根因在 H2: 连接池惰性清理竞态 + 网关关闭一条 H2 连接
        // 会连带其上所有在途流失败 —— 已在各框架 Provider 统一强制 HTTP/1.1(见 LangChain4jHttp),
        // 此处 keepalive 继续管理 H1.1 池(5s 空闲驱逐); .h2 属性已无 H2 连接可管, 保留无害
        System.setProperty("jdk.httpclient.keepalive.timeout", "5");
        System.setProperty("jdk.httpclient.keepalive.timeout.h2", "5");
        SpringApplication.run(JavaAgentApplication.class, args);
    }
}
