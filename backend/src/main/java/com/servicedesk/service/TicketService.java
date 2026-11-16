package com.servicedesk.service;

import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.ticket.TicketCreateRequest;
import com.servicedesk.vo.TicketDetailVO;

/**
 * 工单服务，对应接口文档第 2 章。
 *
 * <p>
 * 属于业务层。当前只落地建单（T-01）骨架：校验客户/设备、生成工单号、
 * 按紧急度匹配 SLA 策略写两个 deadline、状态置 NEW、回填附件；
 * 列表、详情、编辑、派单、状态机在后续切片追加。
 * </p>
 *
 * <p>
 * 数据范围不在这里判断：建单只写本人工单，列表/详情才需要 {@code TicketScope}。
 * </p>
 */
public interface TicketService {

  /**
   * 创建工单。
   *
   * <p>
   * 事务边界：工单插入与附件回填在同一事务内，任一步失败整体回滚。
   * 幂等性：不幂等，重复提交会生成多张工单（工单号各不相同）。
   * </p>
   *
   * @param request 建单入参
   * @return 新工单详情，含工单号、两个 deadline 与已关联附件
   * @throws BusinessException 客户/设备不存在或不属于该客户、分类/紧急度非法、
   *                           附件不可用抛 400；SLA 策略未配置或工单号冲突抛 503
   */
  TicketDetailVO create(TicketCreateRequest request);
}
