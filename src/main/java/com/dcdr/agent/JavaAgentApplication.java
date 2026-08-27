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
        // V3.1 框架化改造: LangChain4j 底层使用 JDK HttpClient 连接池(不经过 WebClientFactory),
        // 限制空闲 keep-alive 连接存活时间, 避免上游网关(DashScope 等)关闭空闲连接后
        // 复用死连接抛 Connection reset(对齐 WebClientFactory 的 maxIdleTime=20s 约束)
        System.setProperty("jdk.httpclient.keepalive.timeout", "20");
        SpringApplication.run(JavaAgentApplication.class, args);
    }
}
