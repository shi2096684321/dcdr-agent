package com.dcdr.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 配置
 * <p>
 * 访问地址:
 * <ul>
 *   <li>Swagger UI: <a href="http://localhost:8080/swagger-ui.html">/swagger-ui.html</a></li>
 *   <li>OpenAPI JSON: <a href="http://localhost:8080/v3/api-docs">/v3/api-docs</a></li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Java AI Agent API")
                        .description("多模态智能对话助手 - 接口文档\n\n" +
                                "支持对话模式（多轮聊天）与创作模式（图片/视频生成），\n" +
                                "提供 SSE 流式输出、工具调用、多模型切换等能力。")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("JavaAgent")
                                .url("https://github.com/example/dcdr-agent"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
