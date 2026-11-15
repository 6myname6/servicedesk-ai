package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 设备视图对象，对应接口文档附录 A.6。
 *
 * <p>
 * 属于接口出参层。{@code customerName} 与 {@code warrantyStatus} 不是 {@code device}
 * 表字段：前者由 Service 批量回填客户名，后者按保修到期日实时计算，均不落库。
 * </p>
 */
@Getter
@Builder
public class DeviceVO {

  /** 设备 ID。 */
  private final Long id;

  /** 所属客户 ID。 */
  private final Long customerId;

  /** 所属客户名，客户档案缺失时为 null。 */
  private final String customerName;

  /** 设备型号，可为 null。 */
  private final String model;

  /** 序列号。 */
  private final String serialNo;

  /** 购买日期，可为 null；序列化为 {@code yyyy-MM-dd}。 */
  private final LocalDate purchaseDate;

  /** 保修到期日，可为 null；序列化为 {@code yyyy-MM-dd}。 */
  private final LocalDate warrantyEnd;

  /** 保修状态：{@code IN_WARRANTY / EXPIRED / UNKNOWN}，实时计算。 */
  private final String warrantyStatus;

  /** 安装位置，可为 null。 */
  private final String location;

  /** 设备状态：{@code ACTIVE / SCRAPPED}。 */
  private final String status;
}
