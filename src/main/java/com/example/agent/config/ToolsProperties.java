package com.example.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具配置属性
 */
@Data
@ConfigurationProperties(prefix = "agent-tools")
public class ToolsProperties {

    private WeatherConfig weather = new WeatherConfig();
    private ToolConfig calculator = new ToolConfig();
    private ToolConfig time = new ToolConfig();

    @Data
    public static class WeatherConfig {
        private boolean enabled = true;
        private String apiKey = "demo-key";
        private String baseUrl = "https://restapi.amap.com/v3/weather/weatherInfo";
    }

    @Data
    public static class ToolConfig {
        private boolean enabled = true;
    }
}
