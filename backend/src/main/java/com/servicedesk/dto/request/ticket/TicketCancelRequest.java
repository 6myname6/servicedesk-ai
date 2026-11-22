package com.servicedesk.dto.request.ticket;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 取消工单的入参，对应接口文档 2.5。
 *
 * <p>
 * 属于接口入参层，只做格式校验。取消原因由前端表单提供「预置选项 + 自定义」，
 * 但后端只接收最终的文本：选项本身是展示层的事，不新增字典接口，
 * 预置文案见开发文档 7.11。
 * </p>
 *
 * <p>
 * 不接收 {@code status}：取消是唯一目标状态，由服务端固定写 {@code CANCELLED}，
 * 避免客户端借此接口做其它状态迁移。
 * </p>
 */
@Data
public class TicketCancelRequest {

  /** 取消原因，必填，≤255；前端下拉选择或自定义输入都最终落成这段文本。 */
  @NotBlank(message = "取消原因不能为空")
  @Size(max = 255, message = "取消原因不能超过 255 位")
  private String reason;

  /**
   * 乐观锁版本号，必填，取详情响应里的 {@code version}。
   *
   * <p>
   * 取消是状态迁移，必须防「两个人同时点」：版本不一致直接 409，不覆盖别人的操作。
   * </p>
   */
  @NotNull(message = "版本号不能为空")
  @Min(value = 0, message = "版本号不能为负数")
  private Integer version;
}
