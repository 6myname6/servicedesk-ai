package com.servicedesk.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户名密码登录请求。
 *
 * <p>
 * 属于接口入参层，对应 {@code POST /api/auth/login}。只做格式校验，
 * 账号是否存在、密码是否正确、账号是否停用统一由 Service 判断。
 * </p>
 */
@Data
public class LoginRequest {

  /** 登录名，3-32 位。 */
  @NotBlank(message = "用户名不能为空")
  @Size(min = 3, max = 32, message = "用户名长度需在 3-32 位")
  private String username;

  /** 密码，8-32 位，明文只在本请求内出现一次。 */
  @NotBlank(message = "密码不能为空")
  @Size(min = 8, max = 32, message = "密码长度需在 8-32 位")
  private String password;
}
