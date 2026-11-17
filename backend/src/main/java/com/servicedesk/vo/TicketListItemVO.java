package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 工单列表项视图，对应接口文档附录 A.7。
 *
 * <p>
 * 属于接口出参层。只放列表需要的字段，不含描述、时间线、AI 摘要；
 * 客户名、设备型号、工程师名、团队名由 Service 按当前页批量回填，避免 N+1。
 * </p>
 */
@Getter
@Builder
public class TicketListItemVO {

  /** 工单 ID。 */
  private final Long id;

  /** 工单号，{@code TKyyyyMMdd000001}。 */
  private final String ticketNo;

  /** 标题。 */
  private final String title;

  /** 故障分类。 */
  private final String category;

  /** 紧急度。 */
  private final String urgency;

  /** 状态。 */
  private final String status;

  /** 客户名，客户档案缺失时为 null。 */
  private final String customerName;

  /** 设备型号，未关联设备或档案缺失时为 null。 */
  private final String deviceModel;

  /** 处理工程师显示名，未派单时为 null。 */
  private final String assigneeName;

  /** 归属团队名，未派单时为 null。 */
  private final String teamName;

  /** 解决剩余分钟，负数表示已超时；未设置 deadline 的历史数据为 null。 */
  private final Long slaRemainingMinutes;

  /** 解决 SLA 是否已超时。 */
  private final boolean slaBreached;

  /** 创建时间，列表默认按它倒序。 */
  private final LocalDateTime createdAt;
}
