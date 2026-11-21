package com.servicedesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单回访评价实体，对应表 {@code ticket_feedback}。
 *
 * <p>
 * 属于持久化层。字段与 {@code docs/ER图.md} 4.14 一一对应，{@code ticket_id} 是逻辑外键，
 * 但唯一索引 {@code uk_ticket} 是「一单一评」的最终兜底。
 * </p>
 *
 * <p>
 * 只有报修人本人、且工单处于 {@code CLOSED} 时才允许写入；评价写入后不可修改、不可删除。
 * </p>
 */
@Data
@TableName("ticket_feedback")
public class TicketFeedback {

  /** 主键，数据库自增。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 工单 ID，逻辑外键 ticket.id，唯一。 */
  private Long ticketId;

  /** 评分，1-5，数据库为 TINYINT。 */
  private Integer score;

  /** 评价内容，可空，长度不超过 500。 */
  private String comment;

  /** 评价时间，由业务层用注入的 {@code Clock} 显式写入。 */
  private LocalDateTime createdAt;
}
