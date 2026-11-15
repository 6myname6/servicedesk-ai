package com.servicedesk.entity.enums;

import com.servicedesk.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 设备状态（2 种）。
 *
 * <p>
 * 属于枚举字典层。枚举名必须与 {@code device.status} 存储值、接口出入参
 * （接口文档 9.12 / 附录 A.6）保持一致。
 * </p>
 *
 * <p>
 * 报废不删档案：历史工单仍通过 {@code device_id} 引用设备，
 * 因此报废只改状态（{@code SCRAPPED}），设备行永久保留。
 * </p>
 */
@Slf4j
public enum DeviceStatus {

  /** 在用 */
  ACTIVE,

  /** 已报废：不再接新工单，历史工单仍可追溯 */
  SCRAPPED;

  /** 默认状态，新增设备未指定状态时使用，对应 {@code device.status} 的数据库默认值。 */
  public static final DeviceStatus DEFAULT = ACTIVE;

  /**
   * 解析设备状态，空值归为默认状态。
   *
   * <p>
   * 状态是可选字段，不传表示使用默认值；只有传了非法值才返回 400。
   * </p>
   *
   * @param raw 原始状态字符串，可为 null 或空白
   * @return 解析后的状态，null/空白时返回 {@link #DEFAULT}
   * @throws BusinessException 状态取值非法时抛出 400
   */
  public static DeviceStatus fromOrDefault(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    try {
      return DeviceStatus.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      log.warn("设备状态取值非法, raw={}", raw);
      throw new BusinessException(400, "设备状态取值非法");
    }
  }
}
