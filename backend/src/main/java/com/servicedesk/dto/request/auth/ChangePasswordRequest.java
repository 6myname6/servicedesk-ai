package com.servicedesk.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码入参，对应接口文档 1.7。
 *
 * <p>
 * 属于接口入参层，只做格式校验。原密码是否正确、新密码是否与原密码相同，
 * 统一由 {@code AuthService} 判定并返回 400。
 * </p>
 *
 * <p>
 * 原密码不限制长度上限：历史账号可能存在不满足当前密码规则的旧密码，
 * 若在入参层拦下，用户将永远无法自助改密。新密码按注册规则要求 8-32 位且含字母与数字。
 * 两个字段都禁止写入日志。
 * </p>
 */
@Data
public class ChangePasswordRequest {

  /** 原密码，必填，仅用于与库中 BCrypt 哈希比对。 */
  @NotBlank(message = "原密码不能为空")
  private String oldPassword;

  /** 新密码，8-32 位，需同时包含字母与数字。 */
  @NotBlank(message = "新密码不能为空")
  @Size(min = 8, max = 32, message = "新密码长度需在 8-32 位")
  @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "新密码需同时包含字母与数字")
  private String newPassword;
}
