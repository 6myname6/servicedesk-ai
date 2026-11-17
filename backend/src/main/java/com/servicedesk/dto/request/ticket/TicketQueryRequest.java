package com.servicedesk.dto.request.ticket;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 工单列表的查询条件，对应接口文档 2.1。
 *
 * <p>
 * 属于接口入参层，通过 queryString 绑定。所有过滤条件之间是「与」关系；
 * {@code mine} 与 {@code escalated} 是开关，不传等价于 false。
 * </p>
 *
 * <p>
 * 数据范围不在这里传：由鉴权拦截器写入的登录身份决定，调用方无法通过参数扩大可见范围。
 * </p>
 */
@Data
public class TicketQueryRequest {

  /** 关键字，模糊匹配标题或工单号，可为空。 */
  private String keyword;

  /** 工单状态，可为空；非空时必须是字典内的值，否则 400。 */
  private String status;

  /** 紧急度 P0-P3，可为空；非空时必须是字典内的值，否则 400。 */
  private String urgency;

  /** 故障分类，可为空；非空时必须是字典内的值，否则 400。 */
  private String category;

  /** 处理工程师 ID，可为空。 */
  private Long assigneeId;

  /** 归属团队 ID，可为空。 */
  private Long teamId;

  /** 是否只看「与我相关」（我报修或我处理），可为空，默认 false。 */
  private Boolean mine;

  /** 是否只看已升级（响应或解决任一超时升级过），可为空，默认 false。 */
  private Boolean escalated;

  /** 页码，从 1 开始，默认 1。 */
  @Min(value = 1, message = "页码不能小于 1")
  private Integer page = 1;

  /** 每页条数，默认 10，最大 100，超出直接 400 而不是静默截断。 */
  @Min(value = 1, message = "每页条数不能小于 1")
  @Max(value = 100, message = "每页条数不能超过 100")
  private Integer size = 10;
}
