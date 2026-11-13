package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

/**
 * 登录结果视图对象，对应接口文档 1.1.3。
 *
 * <p>
 * 属于接口出参层。注册成功后也返回同一结构，前端可直接复用登录态处理逻辑。
 * </p>
 */
@Getter
@Builder
public class LoginVO {

  /** 签发的 JWT。 */
  private final String token;

  /** token 有效期（秒），与 jwt.expire-seconds 一致。 */
  private final long expiresIn;

  /** 当前登录用户信息。 */
  private final UserVO user;
}
