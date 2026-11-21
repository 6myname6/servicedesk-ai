package com.servicedesk.dto.request.ticket;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑工单的入参，对应接口文档 2.4。
 *
 * <p>
 * 属于接口入参层，只做格式校验。五个业务字段全部可选：不传或传 {@code null}
 * 表示保留原值，空白字符串视为未传（{@code title} / {@code description} 例外，
 * 传了空白串由业务层返回 400）。本期不支持清空字段，包括解除设备关联。
 * </p>
 *
 * <p>
 * 不接收 {@code customerId} / {@code reporterId} / {@code status} / {@code ticketNo}：
 * 客户与报修人不可编辑，状态由状态机维护，工单号写入后不可改。
 * </p>
 *
 * <p>
 * 字段可选性带来一个约束：{@code @Size} 对空白串是放行的，所以“标题不能是空白”
 * 必须在业务层判断，不能只靠注解。
 * </p>
 */
@Data
public class TicketUpdateRequest {

  /** 标题，可选，≤128；不传保留原值，传空白串返回 400。 */
  @Size(max = 128, message = "标题不能超过 128 位")
  private String title;

  /** 问题描述，可选，≤5000；不传保留原值，传空白串返回 400。 */
  @Size(max = 5000, message = "描述不能超过 5000 位")
  private String description;

  /** 故障分类，可选，取值见 {@code TicketCategory}；不传或空串保留原值。 */
  @Size(max = 16, message = "故障分类不能超过 16 位")
  private String category;

  /** 紧急度，可选，取值见 {@code Urgency}；不传或空串保留原值，值变化时触发 SLA 重算。 */
  @Size(max = 8, message = "紧急度不能超过 8 位")
  private String urgency;

  /** 设备 ID，可选；不传保留原值，非空时必须属于该工单的客户。 */
  private Long deviceId;

  /**
   * 乐观锁版本号，必填，取详情响应里的 {@code version}。
   *
   * <p>
   * 它是并发控制参数而非业务字段，缺了就无从判断“改的是不是同一份数据”，
   * 因此不参与“不传保留原值”的约定。
   * </p>
   */
  @NotNull(message = "版本号不能为空")
  @Min(value = 0, message = "版本号不能为负数")
  private Integer version;
}
