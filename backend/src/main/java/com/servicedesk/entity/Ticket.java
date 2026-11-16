package com.servicedesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工单实体，对应表 {@code ticket}。
 *
 * <p>
 * 属于持久化层。字段与 {@code docs/ER图.md} 4.5 一一对应，
 * {@code customer_id} / {@code device_id} / {@code reporter_id} / {@code assignee_id} /
 * {@code team_id} / {@code sla_policy_id} 均为逻辑外键，不建物理 FK，写入前由业务层显式校验。
 * </p>
 *
 * <p>
 * 两个 {@code *_deadline} 是建单时按 SLA 策略算出的「绝对时刻」，不是相对时长：
 * 建单写入后不再跟随策略变化，后续暂停/恢复只做平移（S-02）。
 * 同理 {@code status} 的 {@code NEW} 由服务端显式写入，数据库默认值只作兜底。
 * </p>
 *
 * <p>
 * {@code ai_raw} 声明为 {@link String}：本层不解析也不改写 AI 原始 JSON，
 * 原样存取，解析与容错统一放在 AI 模块，避免持久化层依赖具体模型输出结构。
 * </p>
 */
@Data
@TableName("ticket")
public class Ticket {

  /** 主键，数据库自增。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 工单号，{@code TKyyyyMMdd000001}，唯一；由 {@code TicketNoGenerator} 生成，写入后不可改。 */
  private String ticketNo;

  /** 标题，必填，长度不超过 128。 */
  private String title;

  /** 问题描述，必填，长度不超过 5000。 */
  private String description;

  /** 故障分类：HARDWARE / SOFTWARE / CONSUMABLE / INSTALL / COMPLAINT / OTHER；未指定时由业务层写 OTHER。 */
  private String category;

  /** 紧急度：P0 / P1 / P2 / P3；未指定时由业务层写 P2，并决定匹配哪条 SLA 策略。 */
  private String urgency;

  /** 工单状态：9 状态，建单写 {@code NEW}，取值见 {@code docs/ER图.md} 第 6 节。 */
  private String status;

  /** 客户 ID，必填，逻辑外键 customer.id。 */
  private Long customerId;

  /** 设备 ID，可空，逻辑外键 device.id；非空时必须属于 {@link #customerId}。 */
  private Long deviceId;

  /** 报修人 ID，必填，逻辑外键 sys_user.id；取当前登录用户，不信任请求参数。 */
  private Long reporterId;

  /** 当前处理工程师 ID，可空，逻辑外键 sys_user.id；派单（F-01）时写入。 */
  private Long assigneeId;

  /** 归属团队 ID，可空，逻辑外键 team.id；派单（F-01）时随处理人一起写入。 */
  private Long teamId;

  /** 适用 SLA 策略 ID，可空，逻辑外键 sla_policy.id；建单按紧急度匹配成功后写入。 */
  private Long slaPolicyId;

  /** 响应截止时间 = 建单时刻 + 策略响应时限；SLA 倒计时与超时扫描的判定依据。 */
  private LocalDateTime responseDeadline;

  /** 解决截止时间 = 建单时刻 + 策略解决时限；SLA 倒计时与超时扫描的判定依据。 */
  private LocalDateTime resolveDeadline;

  /** 首次响应时间，可空；首次由非报修人操作时写入。 */
  private LocalDateTime firstResponseAt;

  /** 解决时间，可空；进入 RESOLVED 时写入。 */
  private LocalDateTime resolvedAt;

  /** 关闭时间，可空；进入 CLOSED 时写入。 */
  private LocalDateTime closedAt;

  /** 本次暂停开始时间，可空；进入 WAITING_CUSTOMER 时写入，恢复时清空并累加到 {@link #pausedTotalMinutes}。 */
  private LocalDateTime pausedAt;

  /** 累计暂停分钟数，非空，默认 0；解决截止时间按此顺延。 */
  private Integer pausedTotalMinutes;

  /** 响应超时升级时间，可空；非空表示本类超时已升级过，用于超时扫描幂等。 */
  private LocalDateTime responseEscalatedAt;

  /** 解决超时升级时间，可空；非空表示本类超时已升级过，用于超时扫描幂等。 */
  private LocalDateTime resolveEscalatedAt;

  /** AI 分类置信度，可空，取值 0.000-1.000（DECIMAL(4,3)）；无 AI 结果时为 null，低于 0.65 需人工复核。 */
  private BigDecimal aiConfidence;

  /** AI 原始输出 JSON 原文，可空；本层原样存取，不做解析。 */
  private String aiRaw;

  /** 人工是否修正过 AI 结果：1 是 / 0 否，默认 0。 */
  private Integer aiCorrected;

  /**
   * 版本号，默认 0，当前按普通字段读写。
   *
   * <p>
   * 暂不启用乐观锁：未加 {@code @Version}、未注册乐观锁拦截器，
   * 插入时留空由数据库默认值填 0。等编辑接口（T-04）落地再统一切换，
   * 在此之前业务代码不要手工维护本字段。
   * </p>
   */
  private Integer version;

  /** 创建时间，数据库默认值填充。 */
  private LocalDateTime createdAt;

  /** 更新时间，数据库 ON UPDATE 维护。 */
  private LocalDateTime updatedAt;
}
