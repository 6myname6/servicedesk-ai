package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增用户的入参，对应接口文档 9.2。
 *
 * <p>
 * 属于接口入参层，只做格式校验；用户名/邮箱是否重复、角色是否合法、团队是否存在
 * 统一由 Service 判断并返回 409 / 400。
 * </p>
 */
@Data
public class UserCreateRequest {

  /** 登录名，3-32 位，全局唯一。 */
  @NotBlank(message = "用户名不能为空")
  @Size(min = 3, max = 32, message = "用户名长度需在 3-32 位")
  private String username;

  /**
   * 初始密码，8-32 位明文。
   *
   * <p>
   * 明文只在本次请求内出现一次，落库前必须 BCrypt 编码，禁止写入日志。
   * </p>
   */
  @NotBlank(message = "密码不能为空")
  @Size(min = 8, max = 32, message = "密码长度需在 8-32 位")
  private String password;

  /** 显示名，不超过 32 位。 */
  @NotBlank(message = "姓名不能为空")
  @Size(max = 32, message = "姓名不能超过 32 位")
  private String displayName;

  /** 角色，取值见 {@code com.servicedesk.entity.enums.Role}。 */
  @NotBlank(message = "角色不能为空")
  private String role;

  /** 所属团队，可为空；不为空时必须存在。 */
  private Long teamId;

  /** 手机号，可为空。 */
  @Size(max = 20, message = "手机号不能超过 20 位")
  private String phone;

  /** 邮箱，可为空；填写时全局唯一。 */
  @Email(message = "邮箱格式不正确")
  @Size(max = 64, message = "邮箱不能超过 64 位")
  private String email;
}
