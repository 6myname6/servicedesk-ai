package com.servicedesk.security;

import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录鉴权拦截器，负责解析 token 并写入用户上下文。
 *
 * <p>
 * 属于鉴权基础设施层。执行顺序：取请求头 → 校验 Bearer 格式 → 查 Redis 黑名单 →
 * 验签解析 → 写入 {@link UserContext}。任一步失败抛 401，由全局异常处理器统一输出。
 * </p>
 *
 * <p>
 * {@link #afterCompletion} 必须清理 ThreadLocal，否则 Tomcat 线程复用时
 * 上一个请求的身份会串到下一个请求。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

  /** 存放 token 的请求头名。 */
  private static final String HEADER_AUTHORIZATION = "Authorization";

  /** token 前缀，注意尾部有空格。 */
  private static final String BEARER_PREFIX = "Bearer ";

  /** JWT 工具，负责验签与解析。 */
  private final JwtUtil jwtUtil;

  /** 登出黑名单，命中说明 token 已失效。 */
  private final TokenBlackList tokenBlackList;

  /**
   * 请求进入前校验身份并填充上下文。
   *
   * @param request  当前请求
   * @param response 当前响应
   * @param handler  处理器
   * @return true 放行
   * @throws BusinessException 未携带、已登出、过期或伪造 token 时抛 401
   */
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      return true;
    }

    String token = resolveToken(request);
    if (token == null) {
      log.debug("请求未携带 token, uri={}", request.getRequestURI());
      throw new BusinessException(401, "未登录");
    }
    if (tokenBlackList.isInBlackList(token)) {
      log.warn("请求携带已登出的 token, uri={}", request.getRequestURI());
      throw new BusinessException(401, "登录已失效，请重新登录");
    }

    LoginUser loginUser;
    try {
      loginUser = jwtUtil.parse(token);
    } catch (BusinessException e) {
      log.warn("token 校验失败, uri={}, reason={}", request.getRequestURI(), e.getMessage());
      throw e;
    }
    UserContext.set(loginUser);
    return true;
  }

  /**
   * 请求结束后清理线程上下文，防止身份串号。
   *
   * @param request  当前请求
   * @param response 当前响应
   * @param handler  处理器
   * @param ex       处理过程中的异常，正常结束时为 null
   */
  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    UserContext.clear();
  }

  /**
   * 从请求头解析 Bearer token。
   *
   * @param request 当前请求
   * @return token 原文，缺失或格式不正确时返回 null
   */
  private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader(HEADER_AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return null;
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? null : token;
  }
}
