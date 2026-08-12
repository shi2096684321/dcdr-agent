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
        SpringApplication.run(JavaAgentApplication.class, args);
    }
}
