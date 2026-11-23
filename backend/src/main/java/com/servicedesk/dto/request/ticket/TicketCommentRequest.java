package com.servicedesk.dto.request.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 处理记录 / 内部备注入参，对应接口文档 3.3.2。
 *
 * <p>
 * 属于接口入参层，只做格式校验。能否写、能不能写内部备注、工单是否已结束，
 * 全部由业务层按数据范围、角色与状态判定（开发文档 7.14）。
 * </p>
 */
@Data
public class TicketCommentRequest {

  /** 处理记录正文，必填，去空白后 1-2000 字；保留正文中的换行，前端按纯文本渲染。 */
  @NotBlank(message = "处理记录内容不能为空")
  @Size(max = 2000, message = "处理记录不能超过 2000 字")
  private String content;

  /** 是否内部备注，默认 false；仅工程师 / 主管 / 管理员可写，报修人传 true 返回 400。 */
  private Boolean internal;
}
