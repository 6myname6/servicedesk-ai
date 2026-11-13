package com.servicedesk.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置。
 *
 * <p>
 * 属于鉴权基础设施层。项目只引入 spring-security-crypto，不启用 Spring Security 的过滤器链，
 * 因此这里显式注册一个 {@link BCryptPasswordEncoder} 供注册、登录、重置密码共用。
 * </p>
 *
 * <p>
 * BCrypt 自带随机盐，同一明文每次编码结果不同，校验必须用 {@code matches(raw, hash)}，
 * 不能直接比较两次编码结果。
 * </p>
 */
@Configuration
public class PasswordEncoderConfig {

  /** BCrypt 强度，默认 10；越高越安全但校验越慢。 */
  private static final int BCRYPT_STRENGTH = 10;

  /**
   * 注册密码编码器。
   *
   * @return BCrypt 编码器
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
  }
}
