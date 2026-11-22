package com.servicedesk.dto.request.ticket;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单状态迁转入参，对应接口文档 3.1。
 *
 * <p>
 * 属于接口入参层，只做格式校验。目标状态是否可达、当前用户能否发起该动作、
 * 工单是否被别人改过，全部由业务层按状态迁移表判定。
 * </p>
 *
 * <p>
 * 目标状态用字符串接收而不是枚举：取值非法时要走业务层的 400 中文提示，
 * 与列表筛选、建单分类的解析口径保持一致，也避免 Jackson 反序列化失败的提示不可控。
 * </p>
 */
@Data
public class TicketStatusTransitionRequest {

  /** 目标状态，必填，取值见开发文档 12.1；服务端按当前状态查状态迁移表。 */
  @NotBlank(message = "目标状态不能为空")
  @Size(max = 32, message = "目标状态不能超过 32 位")
  private String targetStatus;

  /** 迁移备注，可选，≤500；写入时间线正文，便于解释「为什么这样流转」。 */
  @Size(max = 500, message = "备注不能超过 500 位")
  private String comment;

  /**
   * 乐观锁版本号，必填，取详情响应里的 {@code version}。
   *
   * <p>
   * 状态迁移必须防「两个人同时点」：版本不一致直接 409，不覆盖别人的操作。
   * </p>
   */
  @NotNull(message = "版本号不能为空")
  @Min(value = 0, message = "版本号不能为负数")
  private Integer version;
}
