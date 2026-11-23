package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工单事件视图，对应接口文档附录 A.10。
 *
 * <p>
 * 属于接口出参层。追加处理记录（3.3）与时间线（3.4）共用同一结构：
 * 状态类事件带 {@code fromStatus} / {@code toStatus}，处理记录正文在 {@code content}，
 * 内部备注由 {@code internal} 标记。{@code metadata} 为事件扩展字段原样回显，
 * 无扩展信息或解析失败时为 null，单条脏数据不影响整个时间线。
 * </p>
 */
@Getter
@Builder
public class TicketEventVO {

  /** 事件 ID。 */
  private final Long id;

  /** 事件类型，取值见开发文档 12.1。 */
  private final String type;

  /** 变更前状态，非状态类事件为 null。 */
  private final String fromStatus;

  /** 变更后状态，非状态类事件为 null。 */
  private final String toStatus;

  /** 操作人 ID，系统事件为 null。 */
  private final Long operatorId;

  /** 操作人显示名，账号已删除时为 null。 */
  private final String operatorName;

  /** 操作人角色：REPORTER / ENGINEER / SUPERVISOR / ADMIN；系统事件为 null。 */
  private final String operatorRole;

  /** 正文：备注、处理记录或取消原因；无正文的事件为 null。 */
  private final String content;

  /** 是否内部备注；报修人的时间线不会返回 true 的记录。 */
  private final boolean internal;

  /** 事件扩展字段，无扩展信息或解析失败时为 null。 */
  private final Map<String, Object> metadata;

  /** 发生时间。 */
  private final LocalDateTime createdAt;
}
