package com.example.agent.tool;

import com.example.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * 天气查询工具 - 调用高德天气API
 */
@Slf4j
@Component
public class WeatherTool extends AbstractTool {

    private final String apiKey;
    private final String baseUrl;
    private final WebClient webClient;
    private final boolean enabled;

    public WeatherTool(com.example.agent.config.ToolsProperties toolsProperties) {
        var weatherConfig = toolsProperties.getWeather();
        this.apiKey = weatherConfig.getApiKey();
        this.baseUrl = weatherConfig.getBaseUrl();
        this.enabled = weatherConfig.isEnabled();
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getDescription() {
        return "天气查询工具, 可以查询指定城市的天气情况。当用户询问天气时使用此工具。";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "city": {
                            "type": "string",
                            "description": "城市名称, 如 '北京' 或 '上海'"
                        }
                    },
                    "required": ["city"]
                }""";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String city = getString(params, "city");
        if (city == null || city.isBlank()) {
            return "请提供城市名称";
        }
        if (!enabled || "demo-key".equals(apiKey)) {
            // 演示模式: 返回模拟数据
            return simulateWeather(city);
        }
        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("key", apiKey)
                            .queryParam("city", city)
                            .queryParam("extensions", "base")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return "天气查询结果(" + city + "): " + response;
        } catch (Exception e) {
            log.error("天气查询失败: {}", e.getMessage());
            return simulateWeather(city);
        }
    }

    /** 演示模式模拟天气数据 */
    private String simulateWeather(String city) {
        int temp = 15 + city.hashCode() % 20;
        String[] weathers = {"晴", "多云", "阴", "小雨"};
        String weather = weathers[Math.abs(city.hashCode()) % weathers.length];
        int humidity = 40 + Math.abs(city.hashCode()) % 40;
        return String.format("%s今天天气: %s, 气温 %d°C, 湿度 %d%%", city, weather, temp, humidity);
    }
}
