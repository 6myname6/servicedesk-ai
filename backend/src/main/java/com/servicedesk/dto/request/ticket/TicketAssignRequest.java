package com.servicedesk.dto.request.ticket;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 派单入参，对应接口文档 3.2。
 *
 * <p>
 * 属于接口入参层，只做格式校验。派给谁、工单是否处于可派单状态、
 * 工程师是否属于本团队，全部由业务层判定。
 * </p>
 *
 * <p>
 * 不接收 {@code status}：派单的目标状态固定为 {@code ASSIGNED}，由服务端写入，
 * 避免客户端借派单接口做其它状态迁移。也不接收 {@code teamId}：
 * 团队随被派工程师自动带出，防止出现「工程师在 A 组、工单挂在 B 组」的脏数据。
 * </p>
 */
@Data
public class TicketAssignRequest {

  /** 被派工程师的用户 ID，必填，逻辑外键 sys_user.id。 */
  @NotNull(message = "工程师不能为空")
  private Long assigneeId;

  /** 派单备注，可选，≤255；会写入时间线，便于解释「为什么派给他」。 */
  @Size(max = 255, message = "派单备注不能超过 255 位")
  private String comment;

  /**
   * 乐观锁版本号，必填，取详情响应里的 {@code version}。
   *
   * <p>
   * 派单是状态迁移：两个人同时派单时后提交者必须收到 409，
   * 不能悄悄覆盖先派的人。
   * </p>
   */
  @NotNull(message = "版本号不能为空")
  @Min(value = 0, message = "版本号不能为负数")
  private Integer version;
}
