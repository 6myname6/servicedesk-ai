package com.servicedesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单事件实体，对应表 {@code ticket_event}。
 *
 * <p>
 * 属于持久化层。字段与 {@code docs/ER图.md} 4.6 一一对应，{@code ticket_id} 与
 * {@code operator_id} 均为逻辑外键，不建物理约束。
 * </p>
 *
 * <p>
 * 时间线只追加不修改：本表没有 {@code updated_at}，也没有更新入口。
 * {@code operator_id} 为空表示系统事件（如超时升级）。
 * </p>
 */
@Data
@TableName("ticket_event")
public class TicketEvent {

  /** 主键，数据库自增。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 工单 ID，逻辑外键 ticket.id。 */
  private Long ticketId;

  /** 事件类型，取值见 {@code TicketEventType}。 */
  private String type;

  /** 变更前状态，非状态类事件为 null。 */
  private String fromStatus;

  /** 变更后状态，非状态类事件为 null。 */
  private String toStatus;

  /** 操作人 ID，逻辑外键 sys_user.id；系统事件为 null。 */
  private Long operatorId;

  /** 正文：备注、处理记录或取消原因。 */
  private String content;

  /**
   * 扩展信息 JSON 原文。
   *
   * <p>
   * 本层不解析也不改写，内部备注的 {@code internal} 标记、升级类型等都放在这里，
   * 解析与容错统一在上层完成，避免持久化层依赖具体结构。
   * </p>
   */
  private String metadata;

  /** 发生时间，由业务层用注入的 {@code Clock} 显式写入，便于测试与回放。 */
  private LocalDateTime createdAt;
}
