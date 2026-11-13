package com.servicedesk.common.config;

import com.servicedesk.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置，注册鉴权拦截器与放行白名单。
 *
 * <p>
 * 属于公共配置层。注意路径不含 {@code /api}：{@code server.servlet.context-path}
 * 在进入 DispatcherServlet 前已被剥离，写 {@code /api/auth/login} 会匹配不到。
 * </p>
 *
 * <p>
 * 白名单原则：只放行登录接口与文档/健康检查，其余接口一律校验 token；
 * 新增免鉴权接口必须在这里显式登记，避免误放行。
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

  /** 登录鉴权拦截器。 */
  private final AuthInterceptor authInterceptor;

  /**
   * 注册鉴权拦截器。
   *
   * @param registry 拦截器注册表
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor)
        .addPathPatterns("/**")
        .excludePathPatterns(
            "/auth/login",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/**",
            "/error");
  }
}
