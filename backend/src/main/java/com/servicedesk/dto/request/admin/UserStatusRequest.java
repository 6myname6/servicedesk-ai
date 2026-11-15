package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用 / 停用用户的入参，对应接口文档 9.4。
 *
 * <p>
 * 属于接口入参层。状态只接受 0 与 1，用 {@code @Min/@Max} 而非布尔值，
 * 是为了与数据库 {@code sys_user.status} 的取值口径保持一字不差。
 * </p>
 */
@Data
public class UserStatusRequest {

  /** 目标状态：1 启用 / 0 停用。 */
  @NotNull(message = "状态不能为空")
  @Min(value = 0, message = "状态只能为 0 或 1")
  @Max(value = 1, message = "状态只能为 0 或 1")
  private Integer status;
}
