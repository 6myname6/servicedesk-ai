package com.servicedesk.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 客户端 IP 解析配置，对应 {@code application.yml} 中的 {@code app.client-ip} 前缀。
 *
 * <p>
 * 属于鉴权基础设施层的配置项。通过启动类的 {@code @ConfigurationPropertiesScan} 注册。
 * </p>
 *
 * @param trustProxy 是否信任 {@code X-Forwarded-For} 请求头。仅当应用部署在可信反向代理
 *                   （Nginx 等）之后才可开启；直连公网时开启会让攻击者伪造请求头绕过 IP 维度限流
 */
@ConfigurationProperties(prefix = "app.client-ip")
public record ClientIpProperties(boolean trustProxy) {
}
