package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增客户的入参，对应接口文档 9.9。
 *
 * <p>
 * 属于接口入参层，只做长度格式校验；等级是否合法由 Service 用枚举解析，
 * 非法时返回 400，未传时按默认等级 {@code NORMAL} 落库。
 * </p>
 */
@Data
public class CustomerCreateRequest {

  /** 客户名，不超过 64 位。 */
  @NotBlank(message = "客户名不能为空")
  @Size(max = 64, message = "客户名不能超过 64 位")
  private String name;

  /** 联系人，可为空。 */
  @Size(max = 32, message = "联系人不能超过 32 位")
  private String contact;

  /** 联系电话，可为空。 */
  @Size(max = 20, message = "电话不能超过 20 位")
  private String phone;

  /** 地址，可为空。 */
  @Size(max = 255, message = "地址不能超过 255 位")
  private String address;

  /** 客户等级：KEY / NORMAL，可为空，默认 NORMAL。 */
  @Size(max = 16, message = "客户等级不能超过 16 位")
  private String level;

  /** 备注，可为空。 */
  @Size(max = 255, message = "备注不能超过 255 位")
  private String remark;
}
