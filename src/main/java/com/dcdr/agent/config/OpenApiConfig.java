package com.dcdr.agent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI 配置
 * <p>
 * 访问地址:
 * <ul>
 *   <li>Swagger UI: <a href="http://localhost:8080/swagger-ui.html">/swagger-ui.html</a></li>
 *   <li>OpenAPI JSON: <a href="http://localhost:8080/v3/api-docs">/v3/api-docs</a></li>
 * </ul>
 * 声明 JWT Bearer 认证方案后, Swagger UI 右上角才会出现 Authorize (🔒) 按钮:
 * 点开后直接粘贴 accessToken (无需手写 "Bearer " 前缀, 前缀由 bearerFormat 自动补充)。
 */
@Configuration
public class OpenApiConfig {

    /** 认证方案名 (securitySchemes key, 与全局 security requirement 引用一致) */
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        // JWT Bearer 认证: 与 V2.2 SecurityConfig 的 Authorization: Bearer <token> 对应
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("粘贴 POST /api/auth/login 返回的 accessToken (无需 Bearer 前缀)");

        return new OpenAPI()
                .info(new Info()
                        .title("一人公司多模态 AI 助手工作台 API")
                        .description("一人公司多模态 AI 助手工作台 - 接口文档\n\n" +
                                "支持对话模式（多轮聊天）与创作模式（图片/视频生成），\n" +
                                "提供 SSE 流式输出、工具调用、多模型切换等能力。")
                        .version("3.0.0")
                        .contact(new Contact()
                                .name("一人公司多模态 AI 助手工作台")
                                .url("https://gitee.com/shi-zhun/dcdr-agent"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, bearer))
                // 全局生效: 所有接口都可从 Authorize 弹窗注入 token (免鉴权接口带上也无副作用)
                .security(List.of(new SecurityRequirement().addList(BEARER_SCHEME)));
    }
}
