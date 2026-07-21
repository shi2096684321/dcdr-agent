package com.example.agent.config;

import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 工具自动装配 - 启动时将所有 Tool 实现注册到 ToolRegistry
 */
@Slf4j
@Configuration
public class ToolAutoConfiguration {

    @Bean
    public CommandLineRunner toolRegistrar(ToolRegistry registry, List<Tool> tools) {
        return args -> {
            tools.forEach(tool -> registry.register(tool));
            log.info("工具注册完成, 共 {} 个工具", registry.getAllTools().size());
        };
    }
}
