package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * SLA 状态视图，对应接口文档附录 A.9。
 *
 * <p>
 * 属于接口出参层。剩余分钟按「当前时刻与 deadline 的时间差」实时计算，不落库；
 * 负数表示已超时，前端据此显示红字倒计时。
 * </p>
 */
@Getter
@Builder
public class SlaStatusVO {

  /** 命中的 SLA 策略 ID。 */
  private final Long policyId;

  /** 策略名，例如 {@code P1 标准}。 */
  private final String policyName;

  /** 响应截止时间。 */
  private final LocalDateTime responseDeadline;

  /** 解决截止时间。 */
  private final LocalDateTime resolveDeadline;

  /** 首次响应时间，未响应时为 null。 */
  private final LocalDateTime firstResponseAt;

  /** 是否处于暂停计时；RC-001 已移除暂停机制，恒为 false，字段仅为兼容前端保留。 */
  private final boolean paused;

  /** 累计暂停分钟数；暂停机制已移除，恒为 0。 */
  private final Integer pausedTotalMinutes;

  /** 响应剩余分钟数，负数表示已超时。 */
  private final long responseRemainingMinutes;

  /** 解决剩余分钟数，负数表示已超时。 */
  private final long resolveRemainingMinutes;

  /** 响应是否已超时。 */
  private final boolean responseBreached;

  /** 解决是否已超时。 */
  private final boolean resolveBreached;
}
