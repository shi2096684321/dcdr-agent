package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Java AI Agent 应用启动入口
 * <p>
 * 多模态智能对话助手 - 支持对话模式与创作模式(Banana风格)
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JavaAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaAgentApplication.class, args);
    }
}
