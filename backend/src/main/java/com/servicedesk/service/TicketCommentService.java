package com.servicedesk.service;

import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.ticket.TicketCommentRequest;
import com.servicedesk.vo.TicketEventVO;

import java.util.List;

/**
 * 工单处理记录与时间线服务（F-03、F-04），对应接口文档 3.3 / 3.4。
 *
 * <p>
 * 属于业务层。单独成服务而不是塞进 {@link TicketService}：处理记录写入涉及
 * 「可见性 → 状态 → 角色 → 写事件 / 补首响」，时间线读取又要在数据范围之后按角色过滤
 * 内部备注，两者共用同一套内部备注语义，但不与建单 / 编辑主链路共享逻辑。
 * </p>
 *
 * <p>
 * 与 {@link TicketTransitionService} 的分工：状态迁移写入的是状态类事件（ACCEPT / START 等），
 * 本服务只写 {@code COMMENT}，不改工单状态，也不提供编辑或删除（时间线只追加不修改）。
 * </p>
 */
public interface TicketCommentService {

  /**
   * 追加一条处理记录或内部备注。
   *
   * <p>
   * 事务边界：事件插入与「首次响应时间补写」在同一事务内，任一步失败整体回滚，
   * 不允许出现「写了处理记录却没有补首响」或「补了首响却没有处理记录」。
   * 幂等性：不幂等，每次调用都追加一条新事件；重复提交会产生多条记录。
   * </p>
   *
   * <p>
   * 判定顺序固定：数据范围 404 → 终态 409 → 内部备注越权 400 → 内容 400。
   * 先 404 再 409，避免通过状态码差异确认工单是否存在。
   * </p>
   *
   * @param id      工单 ID
   * @param request 处理记录入参，正文必填，{@code internal} 默认 false
   * @return 新写入的事件，结构见接口文档附录 A.10
   * @throws BusinessException 不存在或越权抛 404；工单已到终态抛 409；
   *                           报修人写内部备注或正文去空白后为空 / 超长抛 400
   */
  TicketEventVO addComment(Long id, TicketCommentRequest request);

  /**
   * 查询工单时间线，一次返回全部可见事件。
   *
   * <p>
   * 事务边界：只读，不加事务。服务端按 {@code created_at} 正序、同一时刻按 {@code id} 正序返回；
   * 单工单事件硬上限 500 条，超出时保留最近 500 条，再按正序输出。
   * 报修人看不到内部备注，过滤在 SQL 中完成后再取上限，避免内部备注挤占可见记录。
   * </p>
   *
   * <p>
   * 不分页（RC-004）：接口无参数，返回事件数组，不再返回 {@code total} 与分页控件所需字段。
   * </p>
   *
   * @param id 工单 ID
   * @return 按时间正序的事件列表，可能为空，不会为 null
   * @throws BusinessException 工单不存在或不在数据范围内时抛出 404
   */
  List<TicketEventVO> listEvents(Long id);
}
