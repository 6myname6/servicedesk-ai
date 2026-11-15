package com.servicedesk.common.config;

import com.servicedesk.security.AuthInterceptor;
import com.servicedesk.security.RoleInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置：注册鉴权拦截器与角色校验拦截器，并维护统一白名单。
 *
 * <p>
 * 属于公共配置层。注意路径不含 {@code /api}：{@code server.servlet.context-path}
 * 在进入 DispatcherServlet 前已被剥离，写 {@code /api/auth/login} 会匹配不到。
 * </p>
 *
 * <p>
 * 两个拦截器的顺序是硬约束：{@code order(1)} 鉴权负责解析 token 并写入
 * {@link com.servicedesk.common.context.UserContext}，{@code order(2)} 角色校验依赖它。
 * 顺序颠倒会让所有请求在取当前用户时抛 401，连登录都无法完成，因此必须显式声明，
 * 不能依赖注册先后。
 * </p>
 *
 * <p>
 * 白名单原则：只放行登录接口与文档/健康检查，其余接口一律校验 token；
 * 新增免鉴权接口必须在这里显式登记，避免误放行。两个拦截器共用同一个白名单常量，
 * 防止新增免鉴权接口时只改一处导致角色校验对未登录请求抛 401。
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

  /** 放行白名单，不校验 token。 */
  private static final String[] WHITE_LIST = {
      "/auth/login", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/**", "/error"
  };

  /** 登录鉴权拦截器。 */
  private final AuthInterceptor authInterceptor;
  /** 角色拦截器。 */
  private final RoleInterceptor roleInterceptor;

  /**
   * 注册鉴权拦截器。
   *
   * @param registry 拦截器注册表
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor)
        .order(1)
        .addPathPatterns("/**")
        .excludePathPatterns(WHITE_LIST);

    registry.addInterceptor(roleInterceptor)
        .order(2)
        .addPathPatterns("/**")
        .excludePathPatterns(WHITE_LIST);
  }
}
