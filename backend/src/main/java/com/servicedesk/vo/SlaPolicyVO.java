package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

/**
 * SLA 策略视图对象，对应接口文档附录 A.12。
 *
 * <p>
 * 属于接口出参层。字段与附录 A.12 一一对应，不额外暴露创建/更新时间：
 * 前端列表只展示名称、优先级、两个时限与启用状态。
 * </p>
 *
 * <p>
 * {@code enabled} 对外是布尔值，数据库用 TINYINT 存 1 / 0，转换在 Service 完成，
 * 不让前端理解 1 / 0 的口径。
 * </p>
 */
@Getter
@Builder
public class SlaPolicyVO {

  /** 策略 ID。 */
  private final Long id;

  /** 策略名，如 {@code P1 标准}。 */
  private final String name;

  /** 适用紧急度：P0 / P1 / P2 / P3。 */
  private final String priority;

  /** 响应时限（分钟）。 */
  private final int responseMinutes;

  /** 解决时限（分钟）。 */
  private final int resolveMinutes;

  /** 是否启用；停用后新单不再匹配本策略。 */
  private final boolean enabled;
}
