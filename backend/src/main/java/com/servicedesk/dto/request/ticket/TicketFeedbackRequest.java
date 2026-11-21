package com.servicedesk.dto.request.ticket;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单评价的入参，对应接口文档 2.6。
 *
 * <p>
 * 属于接口入参层，只做格式校验：分数范围与评价长度在这里拦，工单是否关闭、
 * 是否本人、是否已评价由业务层判断（409 / 404）。
 * </p>
 *
 * <p>
 * 不接收 {@code ticketId} 与 {@code createdAt}：工单来自路径参数，时间由服务端用
 * 注入的 {@code Clock} 写入，避免客户端伪造评价时间。
 * </p>
 */
@Data
public class TicketFeedbackRequest {

  /** 评分，必填，1-5；越界由注解拦成 400，不进入业务层。 */
  @NotNull(message = "评分不能为空")
  @Min(value = 1, message = "评分不能低于 1 分")
  @Max(value = 5, message = "评分不能高于 5 分")
  private Integer score;

  /** 评价内容，可选，≤500；不传或空白视为未填。 */
  @Size(max = 500, message = "评价内容不能超过 500 位")
  private String comment;
}
