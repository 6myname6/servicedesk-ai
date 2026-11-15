package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改客户档案的入参，对应接口文档 9.10。
 *
 * <p>
 * 属于接口入参层。语义是整体覆盖：未传的可选字段会被清空，
 * 前端编辑弹窗应回填完整字段后一次性提交，避免「只改电话却把地址冲掉」的意外。
 * </p>
 */
@Data
public class CustomerUpdateRequest {

  /** 客户名，不超过 64 位。 */
  @NotBlank(message = "客户名不能为空")
  @Size(max = 64, message = "客户名不能超过 64 位")
  private String name;

  /** 联系人，可为空表示清空。 */
  @Size(max = 32, message = "联系人不能超过 32 位")
  private String contact;

  /** 联系电话，可为空表示清空。 */
  @Size(max = 20, message = "电话不能超过 20 位")
  private String phone;

  /** 地址，可为空表示清空。 */
  @Size(max = 255, message = "地址不能超过 255 位")
  private String address;

  /** 客户等级：KEY / NORMAL，可为空时回落到默认等级 NORMAL。 */
  @Size(max = 16, message = "客户等级不能超过 16 位")
  private String level;

  /** 备注，可为空表示清空。 */
  @Size(max = 255, message = "备注不能超过 255 位")
  private String remark;
}
