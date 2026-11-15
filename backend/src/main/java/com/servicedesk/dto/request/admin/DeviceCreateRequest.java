package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 新增设备的入参，对应接口文档 9.12。
 *
 * <p>
 * 属于接口入参层，只做格式校验；客户是否存在、序列号是否重复、日期先后关系
 * 由 Service 判断并返回 400 / 409。
 * </p>
 */
@Data
public class DeviceCreateRequest {

  /** 所属客户 ID，必填；业务上必须存在于 {@code customer} 表。 */
  @NotNull(message = "客户不能为空")
  private Long customerId;

  /** 设备型号，可为空。 */
  @Size(max = 64, message = "型号不能超过 64 位")
  private String model;

  /** 序列号，必填且全局唯一。 */
  @NotBlank(message = "序列号不能为空")
  @Size(max = 64, message = "序列号不能超过 64 位")
  private String serialNo;

  /** 购买日期，格式 {@code yyyy-MM-dd}，可为空。 */
  private LocalDate purchaseDate;

  /** 保修到期日，格式 {@code yyyy-MM-dd}，可为空表示未登记保修。 */
  private LocalDate warrantyEnd;

  /** 安装位置，可为空。 */
  @Size(max = 255, message = "位置不能超过 255 位")
  private String location;

  /** 设备状态：ACTIVE / SCRAPPED，可为空，默认 ACTIVE。 */
  @Size(max = 16, message = "设备状态不能超过 16 位")
  private String status;
}
