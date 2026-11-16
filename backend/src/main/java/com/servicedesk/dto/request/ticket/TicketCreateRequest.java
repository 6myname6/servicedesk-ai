package com.servicedesk.dto.request.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建工单的入参，对应接口文档 2.2。
 *
 * <p>
 * 属于接口入参层，只做格式校验；客户/设备是否存在、设备是否属于该客户、
 * 分类与紧急度取值、SLA 策略是否存在、附件能否关联，均由 Service 判断并返回 400 / 503。
 * </p>
 *
 * <p>
 * 不接收 {@code reporterId} / {@code status} / {@code ticketNo}：报修人取当前登录用户，
 * 状态固定 NEW，工单号由服务端生成，避免客户端伪造归属与状态。
 * </p>
 */
@Data
public class TicketCreateRequest {

  /** 标题，必填，≤128。 */
  @NotBlank(message = "标题不能为空")
  @Size(max = 128, message = "标题不能超过 128 位")
  private String title;

  /** 问题描述，必填，≤5000。 */
  @NotBlank(message = "描述不能为空")
  @Size(max = 5000, message = "描述不能超过 5000 位")
  private String description;

  /** 客户 ID，必填；业务上必须存在于 {@code customer} 表。 */
  @NotNull(message = "客户不能为空")
  private Long customerId;

  /** 设备 ID，可空；非空时必须属于 {@link #customerId}。 */
  private Long deviceId;

  /** 故障分类，可空，缺省 OTHER；取值见 {@code TicketCategory}。 */
  @Size(max = 16, message = "故障分类不能超过 16 位")
  private String category;

  /** 紧急度，可空，缺省 P2；取值见 {@code Urgency}，并决定匹配哪条 SLA 策略。 */
  @Size(max = 8, message = "紧急度不能超过 8 位")
  private String urgency;

  /**
   * 已上传附件 URL 列表，可空，最多 5 个。
   *
   * <p>
   * 建单成功后把这些附件的 {@code ticket_id} 回填为本工单；URL 必须是当前账号上传且
   * 尚未关联工单的附件，否则返回 400。
   * </p>
   */
  @Size(max = 5, message = "最多关联 5 个附件")
  private List<@Size(max = 512, message = "附件地址不能超过 512 位") String> imageUrls;
}
