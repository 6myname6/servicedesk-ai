package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 修改设备的入参，对应接口文档 9.13。
 *
 * <p>
 * 属于接口入参层。语义是整体覆盖：型号、日期、位置未传即清空；
 * 但状态例外——未传时保留原状态，避免编辑弹窗漏传把已报废设备改回在用。
 * </p>
 */
@Data
public class DeviceUpdateRequest {

  /** 所属客户 ID，必填；业务上必须存在于 {@code customer} 表。 */
  @NotNull(message = "客户不能为空")
  private Long customerId;

  /** 设备型号，可为空表示清空。 */
  @Size(max = 64, message = "型号不能超过 64 位")
  private String model;

  /** 序列号，必填且全局唯一（除自身外）。 */
  @NotBlank(message = "序列号不能为空")
  @Size(max = 64, message = "序列号不能超过 64 位")
  private String serialNo;

  /** 购买日期，可为空表示清空。 */
  private LocalDate purchaseDate;

  /** 保修到期日，可为空表示清空，清空后保修状态为 UNKNOWN。 */
  private LocalDate warrantyEnd;

  /** 安装位置，可为空表示清空。 */
  @Size(max = 255, message = "位置不能超过 255 位")
  private String location;

  /** 设备状态：ACTIVE / SCRAPPED，可为空表示不修改。 */
  @Size(max = 16, message = "设备状态不能超过 16 位")
  private String status;
}
