package com.servicedesk.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单详情视图，对应接口文档附录 A.8。
 *
 * <p>
 * 属于接口出参层。建单接口直接返回本对象，列表接口使用更轻的 {@code TicketListItem}。
 * 客户、设备、报修人、SLA、附件都是组装出来的快照，不对应单表字段。
 * </p>
 *
 * <p>
 * 建单阶段 {@code assignee} / {@code team} / {@code ai} 恒为 null：
 * 派单（F-01）与 AI 分类（AI-01）尚未接入，字段保留以稳定契约。
 * </p>
 */
@Data
@Builder
public class TicketDetailVO {

  /** 工单 ID。 */
  private final Long id;

  /** 工单号，{@code TKyyyyMMdd000001}。 */
  private final String ticketNo;

  /** 标题。 */
  private final String title;

  /** 问题描述。 */
  private final String description;

  /** 故障分类：HARDWARE / SOFTWARE / CONSUMABLE / INSTALL / COMPLAINT / OTHER。 */
  private final String category;

  /** 紧急度：P0 / P1 / P2 / P3。 */
  private final String urgency;

  /** 状态：建单为 NEW。 */
  private final String status;

  /** 客户快照。 */
  private final CustomerBrief customer;

  /** 设备快照，未选择设备时为 null。 */
  private final DeviceBrief device;

  /** 报修人快照。 */
  private final UserBrief reporter;

  /** 处理工程师快照，未派单时为 null。 */
  private final UserBrief assignee;

  /** 归属团队快照，未派单时为 null。 */
  private final TeamBrief team;

  /** SLA 状态，含两个 deadline 与剩余分钟。 */
  private final SlaStatusVO sla;

  /** AI 分类建议，本期未接入时为 null。 */
  private final AiBrief ai;

  /** 已关联附件，按上传顺序返回；无附件时为空数组。 */
  private final List<AttachmentVO> attachments;

  /** 创建时间。 */
  private final LocalDateTime createdAt;

  /** 更新时间。 */
  private final LocalDateTime updatedAt;

  /** 乐观锁版本号。 */
  private final Integer version;

  /**
   * 客户快照。
   *
   * @param id    客户 ID
   * @param name  客户名
   * @param phone 联系电话
   */
  public record CustomerBrief(Long id, String name, String phone) {
  }

  /**
   * 设备快照。
   *
   * @param id          设备 ID
   * @param model       设备型号
   * @param serialNo    序列号
   * @param warrantyEnd 保修到期日，可为 null
   */
  public record DeviceBrief(Long id, String model, String serialNo, LocalDate warrantyEnd) {
  }

  /**
   * 用户快照（报修人 / 处理人）。
   *
   * @param id          用户 ID
   * @param displayName 显示名
   */
  public record UserBrief(Long id, String displayName) {
  }

  /**
   * 团队快照。
   *
   * @param id   团队 ID
   * @param name 团队名
   */
  public record TeamBrief(Long id, String name) {
  }

  /**
   * AI 分类建议快照，对应附录 A.8 的 {@code ai} 对象。
   *
   * @param confidence 置信度，0.000-1.000
   * @param needReview 是否需人工确认（置信度 &lt; 0.65）
   * @param summary    一句话摘要
   * @param category   AI 建议分类
   * @param urgency    AI 建议紧急度
   */
  public record AiBrief(BigDecimal confidence, boolean needReview, String summary,
      String category, String urgency) {
  }
}
