package com.servicedesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SLA 策略实体，对应表 {@code sla_policy}。
 *
 * <p>
 * 属于持久化层。字段与 {@code docs/ER图.md} 4.8 一一对应。
 * 一个紧急度（{@code priority}）最多一条策略，由 {@code uk_priority} 唯一索引兜底。
 * </p>
 *
 * <p>
 * 只存时限的「分钟数」，不存具体截止时间：截止时间随建单时刻变化，
 * 由建单逻辑用「当前时间 + 分钟数」算出后写到 {@code ticket.response_deadline} /
 * {@code ticket.resolve_deadline}，因此本表可以随时改时限而不影响已建工单。
 * </p>
 */
@Data
@TableName("sla_policy")
public class SlaPolicy {

  /** 主键，数据库自增。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 策略名，如 {@code P1 标准}。 */
  private String name;

  /** 适用紧急度：P0 / P1 / P2 / P3，与 {@code ticket.urgency} 取值同域且唯一。 */
  private String priority;

  /** 响应时限（分钟），建单时用于计算响应截止时间，必须为正数。 */
  private Integer responseMinutes;

  /** 解决时限（分钟），建单时用于计算解决截止时间，必须为正数。 */
  private Integer resolveMinutes;

  /** 是否启用：1 启用 / 0 停用；停用后新单不再匹配到本策略，已建工单不受影响。 */
  private Integer enabled;

  /** 创建时间，数据库默认值填充。 */
  private LocalDateTime createdAt;

  /** 更新时间，数据库 ON UPDATE 维护。 */
  private LocalDateTime updatedAt;
}
