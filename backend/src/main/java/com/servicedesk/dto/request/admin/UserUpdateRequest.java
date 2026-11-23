package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改用户的入参，对应接口文档 9.3。
 *
 * <p>
 * 属于接口入参层。登录名与密码不在此处修改：登录名是登录凭证与唯一键，
 * 改密码属于重置密码流程，与资料维护分开，避免误操作。
 * </p>
 */
@Data
public class UserUpdateRequest {

  /** 显示名，不超过 32 位。 */
  @NotBlank(message = "姓名不能为空")
  @Size(max = 32, message = "姓名不能超过 32 位")
  private String displayName;

  /** 角色，取值见 {@code com.servicedesk.entity.enums.Role}。 */
  @NotBlank(message = "角色不能为空")
  private String role;

  /**
   * 所属团队，可为空表示不分配团队；不为空时必须存在。
   *
   * <p>
   * 与 {@link #customerId} 互斥：传了客户就必须把团队置空，反之亦然，
   * 两个都传返回 400。本接口按「整体覆盖」处理归属，不做增量合并。
   * </p>
   */
  private Long teamId;

  /** 所属客户，可为空表示不关联客户；不为空时必须存在，且与 {@link #teamId} 互斥。 */
  private Long customerId;

  /** 手机号，可为空。 */
  @Size(max = 20, message = "手机号不能超过 20 位")
  private String phone;

  /** 邮箱，可为空；填写时全局唯一。 */
  @Email(message = "邮箱格式不正确")
  @Size(max = 64, message = "邮箱不能超过 64 位")
  private String email;
}
