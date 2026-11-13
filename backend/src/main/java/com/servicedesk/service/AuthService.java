package com.servicedesk.service;

import com.servicedesk.dto.request.auth.LoginRequest;
import com.servicedesk.vo.LoginVO;
import com.servicedesk.vo.UserVO;

/**
 * 认证服务，负责登录、登出与当前用户查询。
 *
 * <p>
 * 属于业务层。接口不暴露 token 解析细节，登出所需的 token 原文由 Controller 从请求头提取后传入。
 * </p>
 */
public interface AuthService {

  /**
   * 用户名密码登录。
   *
   * @param request 登录请求，包含用户名与明文密码
   * @return token、有效期与用户信息
   * @throws com.servicedesk.common.exception.BusinessException 用户名或密码错误抛 401，账号停用抛 403
   */
  LoginVO login(LoginRequest request);

  /**
   * 登出，把当前 token 加入黑名单。
   *
   * @param token 当前请求携带的 JWT 原文
   */
  void logout(String token);

  /**
   * 查询当前登录用户，数据以数据库为准而非 token 载荷。
   *
   * @return 最新的用户信息
   * @throws com.servicedesk.common.exception.BusinessException 未登录或用户已被删除时抛 401
   */
  UserVO currentUser();
}
