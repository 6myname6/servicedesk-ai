package com.servicedesk.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置，为 springdoc 提供文档元信息与 JWT 认证方案。
 *
 * <p>属于公共配置层。访问地址（context-path 为 {@code /api}）：</p>
 * <ul>
 *   <li>Swagger UI：{@code /api/swagger-ui.html}</li>
 *   <li>OpenAPI JSON：{@code /api/v3/api-docs}</li>
 * </ul>
 *
 * <p>JWT 方案声明为全局安全要求，登录接口这类免鉴权接口需用
 * {@code @SecurityRequirements} 单独覆盖。</p>
 */
@Configuration
public class OpenApiConfig {

    /** 文档标题，与产品名保持一致。 */
    private static final String TITLE = "智维工单 ServiceDesk AI";

    /** 文档版本，与 pom 的 artifact version 保持一致。 */
    private static final String VERSION = "1.0.0";

    /** 文档描述，说明响应结构与认证方式。 */
    private static final String DESCRIPTION = """
            企业售后 / 设备报修工单系统 + AI 坐席。

            通用约定：
            - 所有接口返回 {code, message, data}，code 与 HTTP 状态码一致，200 表示成功；
            - 认证使用 JWT，请求头 Authorization: Bearer {token}，token 有效期 7 天；
            - 资源不存在与越权统一返回 404，不区分 403/404。
            """;

    /** 安全方案名称，Swagger UI 的 Authorize 按钮据此识别。 */
    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    /** Bearer 认证方案类型标识。 */
    private static final String BEARER_SCHEME = "bearer";

    /** token 格式标识，仅用于文档展示。 */
    private static final String BEARER_FORMAT = "JWT";

    /**
     * 构建 OpenAPI 文档定义。
     *
     * @return 含基本信息与 JWT 安全方案的 OpenAPI 对象
     */
    @Bean
    public OpenAPI serviceDeskOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title(TITLE)
                        .version(VERSION)
                        .description(DESCRIPTION))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme(BEARER_SCHEME)
                                .bearerFormat(BEARER_FORMAT)
                                .description("登录接口返回的 token，填写时无需加 Bearer 前缀")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
