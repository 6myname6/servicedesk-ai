package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 工单评价视图，对应接口文档附录 A.20。
 *
 * <p>
 * 属于接口出参层。只暴露评价本身的五个字段，不内联工单信息：评价接口的调用方
 * 已经持有工单 ID，重复内联会让响应随工单字段变化而漂移。
 * </p>
 */
@Getter
@Builder
public class TicketFeedbackVO {

  /** 评价 ID。 */
  private final Long id;

  /** 工单 ID。 */
  private final Long ticketId;

  /** 评分，1-5。 */
  private final Integer score;

  /** 评价内容，未填写时为 null。 */
  private final String comment;

  /** 评价时间。 */
  private final LocalDateTime createdAt;
}
