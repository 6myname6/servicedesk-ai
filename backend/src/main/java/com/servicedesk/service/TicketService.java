package com.servicedesk.service;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.ticket.TicketCreateRequest;
import com.servicedesk.dto.request.ticket.TicketQueryRequest;
import com.servicedesk.dto.request.ticket.TicketUpdateRequest;
import com.servicedesk.vo.TicketDetailVO;
import com.servicedesk.vo.TicketListItemVO;

import jakarta.validation.Valid;

/**
 * 工单服务，对应接口文档第 2 章。
 *
 * <p>
 * 属于业务层。已落地建单（T-01）、列表（T-02）、详情（T-03）与编辑（T-04）：
 * 建单校验客户/设备、生成工单号、按紧急度写两个 deadline、状态置 NEW、回填附件；
 * 详情按数据范围判定可见性；编辑支持字段可选 + 乐观锁，紧急度变化时重算 SLA。
 * 派单、状态机、时间线在后续切片追加。
 * </p>
 *
 * <p>
 * 数据范围不写在这里：建单只写本人工单，列表、详情、编辑共用 {@code TicketScope}，
 * 越权与不存在统一返回 404。
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

  /**
   * 分页查询工单，自动应用当前用户的数据范围。
   *
   * <p>
   * 事务边界：只读，不加事务。数据范围先下推为 SQL 条件再分页，保证
   * {@code total} 只统计可见行；名称字段按当前页批量回填。
   * </p>
   *
   * @param query 查询条件，关键字匹配标题或工单号
   * @return 分页结果，按创建时间倒序
   * @throws BusinessException 状态/紧急度/分类取值非法时抛出 400
   */
  PageResult<TicketListItemVO> page(TicketQueryRequest query);

  /**
   * 查询工单详情，自动应用当前用户的数据范围。
   *
   * <p>
   * 事务边界：只读，不加事务。越权与不存在统一返回 404，不区分两者；
   * 关联的客户、设备、处理人、SLA 与附件在一次请求内装配完成。
   * </p>
   *
   * @param id 工单 ID
   * @return 工单详情，含客户/设备、SLA 状态与附件
   * @throws BusinessException 工单不存在或不在数据范围内时抛出 404
   */
  TicketDetailVO detail(Long id);

  /**
   * 编辑工单的标题、描述、分类、紧急度与设备。
   *
   * <p>
   * 事务边界：主表更新与紧急度触发的 SLA 重算在同一事务内，任一步失败整体回滚。
   * 幂等性：字段均未变化时不写库，{@code version} 与 {@code updated_at} 保持原值；
   * 有变更时以请求里的 {@code version} 做乐观锁。
   * </p>
   *
   * @param id      工单 ID
   * @param request 编辑入参，五个业务字段均可选，不传表示保留原值
   * @return 编辑后的工单详情
   * @throws BusinessException 越权或不存在抛 404；终态、报修人非 NEW、版本冲突抛 409；
   *                           取值非法或设备不属于该客户抛 400；新紧急度无启用策略抛 503
   */
  TicketDetailVO update(Long id, TicketUpdateRequest request);
}
