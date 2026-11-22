package com.servicedesk.service;

import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.ticket.TicketStatusTransitionRequest;
import com.servicedesk.vo.TicketDetailVO;

/**
 * 工单状态迁移服务（F-02），对应接口文档 3.1。
 *
 * <p>
 * 属于业务层。单独成一个服务而不是塞进 {@link TicketService}：状态迁移是接单、开始处理、
 * 等客户、客户回复、解决、验收关闭、重开七个动作的组合，每个动作都有自己的角色、时间戳与
 * SLA 副作用，和建单 / 列表 / 编辑的主链路没有共用逻辑；放在一起只会让工单服务继续膨胀。
 * 迁移规则本身在 {@code domain.ticket.TicketTransition} 里，本服务只负责判定顺序、落库与写事件。
 * </p>
 *
 * <p>
 * 与 {@link TicketService} 的分工：派单（F-01）与取消（T-05）各有独立接口，
 * 也各自留在原服务里，不在本服务范围内；本服务既不查列表也不装配读模型，
 * 只借用 {@code TicketAssembler} 返回详情。
 * </p>
 */
public interface TicketTransitionService {

  /**
   * 状态迁移：按迁移表把工单推进到目标状态。
   *
   * <p>
   * 事务边界：主表的状态与时间戳、{@code paused_total_minutes}、两个 deadline 以及
   * {@code ticket_event} 写入在同一事务内，任一步失败整体回滚，不允许出现「状态变了但时间线没有」。
   * 幂等性：不幂等，同一动作第二次提交会因当前状态已变而返回 409，不产生第二条事件。
   * </p>
   *
   * <p>
   * 判定顺序固定：可见性 404 → 版本 409 → 目标状态字典 400 → 迁移表 409 → 角色 403。
   * 越权与不存在统一 404，避免通过状态码差异确认工单是否存在。
   * </p>
   *
   * @param id      工单 ID
   * @param request 迁转入参，目标状态与版本号必填，备注可选
   * @return 迁移后的工单详情
   * @throws BusinessException 不存在或越权抛 404；迁移组合非法、版本冲突抛 409；
   *                           目标状态取值非法抛 400；当前角色无权发起该动作抛 403；
   *                           重开时工单的 SLA 策略缺失抛 503
   */
  TicketDetailVO transitionStatus(Long id, TicketStatusTransitionRequest request);
}
