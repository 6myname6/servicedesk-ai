package com.servicedesk.controller;

import com.servicedesk.common.api.Result;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.auth.LoginRequest;
import com.servicedesk.security.ClientIpResolver;
import com.servicedesk.service.AuthService;
import com.servicedesk.vo.LoginVO;
import com.servicedesk.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口，对应接口文档第 1 章。
 *
 * <p>
 * 属于接口层，只做参数接收与响应包装，业务规则全部在 {@link AuthService}。
 * 除 {@code /auth/login} 外，本控制器的其余接口都需要通过鉴权拦截器。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

  /** 鉴权请求头中的 Bearer 前缀，尾部带空格。 */
  private static final String BEARER_PREFIX = "Bearer ";

  /** 认证服务。 */
  private final AuthService authService;

  /** 客户端 IP 解析器，为登录失败限流提供 IP 维度。 */
  private final ClientIpResolver clientIpResolver;

  /**
   * 构造认证接口。
   *
   * @param authService      认证服务
   * @param clientIpResolver 客户端 IP 解析器
   */
  public AuthController(AuthService authService, ClientIpResolver clientIpResolver) {
    this.authService = authService;
    this.clientIpResolver = clientIpResolver;
  }

  /**
   * 用户名密码登录。
   *
   * <p>
   * 客户端 IP 由本层解析后显式传给服务层，避免服务层依赖 Servlet API；
   * 解析规则见 {@link ClientIpResolver}，默认不信任 {@code X-Forwarded-For}。
   * </p>
   *
   * @param request     登录请求
   * @param httpRequest 当前 HTTP 请求，用于取客户端 IP
   * @return token、有效期与用户信息
   */
  @PostMapping("/login")
  public Result<LoginVO> login(@RequestBody @Valid LoginRequest request, HttpServletRequest httpRequest) {
    String clientIp = clientIpResolver.resolve(httpRequest);
    log.debug("收到登录请求, username={}, ip={}", request.getUsername(), clientIp);
    return Result.success(authService.login(request, clientIp));
  }

  /**
   * 退出登录，把当前 token 加入黑名单。
   *
   * @param authorization 鉴权请求头，形如 {@code Bearer xxx}
   * @return 无数据的成功响应
   */
  @PostMapping("/logout")
  public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
    log.debug("收到登出请求");
    authService.logout(extractToken(authorization));
    return Result.success();
  }

  /**
   * 查询当前登录用户。
   *
   * @return 当前用户信息
   */
  @GetMapping("/me")
  public Result<UserVO> me() {
    return Result.success(authService.currentUser());
  }

  /**
   * 从鉴权请求头提取 token 原文。
   *
   * @param authorization 鉴权请求头
   * @return JWT 原文
   * @throws BusinessException 请求头缺失或格式不正确时抛 401
   */
  private String extractToken(String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      throw new BusinessException(401, "未登录");
    }
    String token = authorization.substring(BEARER_PREFIX.length()).trim();
    if (token.isEmpty()) {
      throw new BusinessException(401, "未登录");
    }
    return token;
  }
}
