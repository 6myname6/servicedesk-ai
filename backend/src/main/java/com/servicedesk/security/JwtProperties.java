package com.servicedesk.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置项，对应 {@code application.yml} 中的 {@code jwt} 前缀。
 *
 * <p>
 * 通过启动类的 {@code @ConfigurationPropertiesScan} 注册，无需额外注解。
 * </p>
 *
 * @param secret        签名密钥，HS256 要求不短于 32 字节，生产环境用环境变量 JWT_SECRET 覆盖
 * @param expireSeconds token 有效期（秒），与接口文档约定的 604800（7 天）保持一致
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, long expireSeconds) {
}
