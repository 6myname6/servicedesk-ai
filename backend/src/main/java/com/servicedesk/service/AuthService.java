package com.servicedesk.service;

import com.servicedesk.dto.request.auth.ChangePasswordRequest;
import com.servicedesk.dto.request.auth.LoginRequest;
import com.servicedesk.vo.LoginVO;
import com.servicedesk.vo.UserVO;

/**
 * 认证服务，负责登录、登出、当前用户查询与自助改密。
 *
 * <p>
 * 属于业务层。接口不暴露 token 解析细节，登出所需的 token 原文由 Controller 从请求头提取后传入。
 * 改密只处理「本人改本人」，管理员重置密码仍走用户管理（M-01），两条路径不共用。
 * </p>
 */
public interface AuthService {

  /**
   * 用户名密码登录。
   *
   * <p>
   * 客户端 IP 由接口层解析后传入，而不是在服务层从请求上下文读取：
   * 服务层不依赖 Servlet API，便于单测；同时让「限流按哪个 IP 计数」这件事在调用处可见。
   * </p>
   *
   * @param request  登录请求，包含用户名与明文密码
   * @param clientIp 客户端 IP，用于 IP 维度失败限流，不能为空白
   * @return token、有效期与用户信息
   * @throws com.servicedesk.common.exception.BusinessException 用户名或密码错误抛 401，账号停用抛 403，
   *                                                           账号或 IP 失败次数过多抛 429，Redis 不可用抛 503
   */
  LoginVO login(LoginRequest request, String clientIp);

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

  /**
   * 修改当前登录用户的密码。
   *
   * <p>
   * 事务边界：单表更新，只有一次 {@code UPDATE}；旧密码校验全部在写库前完成。
   * 幂等性：不幂等，成功后旧密码立即失效，再用旧密码提交会返回 400。
   * </p>
   *
   * <p>
   * 改密不主动失效当前 token：用户刚完成身份校验，强制重登只会制造额外摩擦；
   * 其他设备的 token 按有效期自然过期。若业务改为强制重登，属于契约变更，需先评审。
   * </p>
   *
   * @param request 改密入参，原密码与新密码均必填
   * @throws com.servicedesk.common.exception.BusinessException 未登录或账号已删除抛 401；
   *                                                           原密码不正确、新密码与原密码相同抛 400
   */
  void changePassword(ChangePasswordRequest request);
}
