package com.servicedesk.entity.enums;

import com.servicedesk.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 客户等级（2 种）。
 *
 * <p>
 * 属于枚举字典层。枚举名必须与 {@code customer.level} 存储值、接口出入参
 * （接口文档 9.9 / 附录 A.5）保持一致，统一存字符串而不是魔法数字。
 * </p>
 *
 * <p>
 * 当前只区分重点客户与普通客户，用于工单优先级参考与客户列表筛选，
 * 不参与 SLA 计算——SLA 只由工单紧急度决定。
 * </p>
 */
@Slf4j
public enum CustomerLevel {

  /** 重点客户：年框或高价值客户，报修时人工优先关注 */
  KEY,

  /** 普通客户：新建客户档案时的默认等级 */
  NORMAL;

  /** 默认等级，新建客户未指定等级时使用，对应 {@code customer.level} 的数据库默认值。 */
  public static final CustomerLevel DEFAULT = NORMAL;

  /**
   * 解析客户等级，空值归为默认等级。
   *
   * <p>
   * 与 {@code Role.from} 的差异：这里的空值不是错误。等级是可选字段，
   * 不传表示使用默认值；只有传了非法值才返回 400。
   * </p>
   *
   * @param raw 原始等级字符串，可为 null 或空白
   * @return 解析后的等级，null/空白时返回 {@link #DEFAULT}
   * @throws BusinessException 等级取值非法时抛出 400
   */
  public static CustomerLevel fromOrDefault(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    try {
      return CustomerLevel.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      log.warn("客户等级取值非法, raw={}", raw);
      throw new BusinessException(400, "客户等级取值非法");
    }
  }
}
