package com.servicedesk.controller;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.api.Result;
import com.servicedesk.dto.request.ticket.TicketCancelRequest;
import com.servicedesk.dto.request.ticket.TicketCreateRequest;
import com.servicedesk.dto.request.ticket.TicketFeedbackRequest;
import com.servicedesk.dto.request.ticket.TicketQueryRequest;
import com.servicedesk.dto.request.ticket.TicketUpdateRequest;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.security.RequiredRole;
import com.servicedesk.service.TicketService;
import com.servicedesk.vo.TicketDetailVO;
import com.servicedesk.vo.TicketFeedbackVO;
import com.servicedesk.vo.TicketListItemVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单接口，对应接口文档第 2 章（建单 2.2、列表 2.1、详情 2.3、编辑 2.4、取消 2.5、评价 2.6）。
 *
 * <p>
 * 属于接口层，只做参数接收、{@code @Valid} 校验与响应包装，不写业务判断：
 * 角色由 {@code RoleInterceptor} 依据方法上的 {@link RequiredRole} 判定，
 * 行级数据范围与状态校验由业务层完成。
 * </p>
 *
 * <p>
 * 建单只要求登录，报修人取当前登录用户（业务层从 {@code UserContext} 读取），
 * 不接收请求参数中的上报人；详情对所有角色开放，看到哪张单由数据范围决定。
 * </p>
 *
 * <p>
 * 路径不含 {@code /api}：{@code server.servlet.context-path} 在进入 DispatcherServlet
 * 前已被剥离。
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/tickets")
public class TicketController {

  /** 工单服务。 */
  private final TicketService ticketService;

  /**
   * 创建工单。
   *
   * @param request 建单入参，见接口文档 2.2
   * @return 新工单详情，含工单号、两个 deadline 与已关联附件
   */
  @PostMapping
  public Result<TicketDetailVO> create(@RequestBody @Valid TicketCreateRequest request) {
    log.debug("收到建单请求, customerId={}, deviceId={}, category={}, urgency={}, attachments={}",
        request.getCustomerId(), request.getDeviceId(), request.getCategory(), request.getUrgency(),
        request.getImageUrls() == null ? 0 : request.getImageUrls().size());
    return Result.success(ticketService.create(request));
  }

  /**
   * 分页查询工单，自动应用当前用户的数据范围。
   *
   * @param query 查询条件，含关键词、状态、紧急度、分类、工程师、团队、我相关与分页
   * @return 分页结果，按创建时间倒序
   */
  @GetMapping
  public Result<PageResult<TicketListItemVO>> page(@Valid TicketQueryRequest query) {
    log.debug("收到工单列表请求, status={}, urgency={}, category={}, assigneeId={}, teamId={}, mine={}, page={}, size={}",
        query.getStatus(), query.getUrgency(), query.getCategory(), query.getAssigneeId(),
        query.getTeamId(), query.getMine(), query.getPage(), query.getSize());
    return Result.success(ticketService.page(query));
  }

  /**
   * 查询工单详情。
   *
   * <p>
   * 不限制角色：报修人、工程师、主管、管理员都可能看到同一张工单，能不能看由业务层按
   * 数据范围判定，越权返回 404。
   * </p>
   *
   * @param id 工单 ID
   * @return 工单详情，见接口文档 2.3
   */
  @GetMapping("/{id}")
  public Result<TicketDetailVO> getTicketDetail(@PathVariable Long id) {
    log.debug("收到工单详情请求, ticketId={}", id);
    return Result.success(ticketService.detail(id));
  }

  /**
   * 编辑工单。
   *
   * <p>
   * 工程师不在允许角色内：权限矩阵中「编辑工单」一栏工程师为 {@code —}，即使他是该单的处理人
   * 也不能改内容。角色在这里由拦截器判定（403），行级范围与状态在业务层判定（404 / 409）。
   * </p>
   *
   * @param id      工单 ID
   * @param request 编辑入参，五个业务字段均可选，{@code version} 必填
   * @return 编辑后的工单详情，见接口文档 2.4
   */
  @PutMapping("/{id}")
  @RequiredRole({ Role.REPORTER, Role.SUPERVISOR, Role.ADMIN })
  public Result<TicketDetailVO> updateTicketDetail(@PathVariable Long id,
      @RequestBody @Valid TicketUpdateRequest request) {
    log.debug("收到编辑工单请求, ticketId={}, version={}", id, request.getVersion());
    return Result.success(ticketService.update(id, request));
  }

  /**
   * 取消工单。
   *
   * <p>
   * 工程师不在允许角色内：权限矩阵中取消只给报修人（本人）、主管与管理员。
   * 状态是否可取消由业务层判定（{@code RESOLVED} 与终态返回 409），Controller 不判。
   * </p>
   *
   * @param id      工单 ID
   * @param request 取消入参，原因与版本号必填
   * @return 取消后的工单详情，见接口文档 2.5
   */
  @PostMapping("/{id}/cancel")
  @RequiredRole({ Role.REPORTER, Role.SUPERVISOR, Role.ADMIN })
  public Result<TicketDetailVO> cancel(@PathVariable Long id,
      @RequestBody @Valid TicketCancelRequest request) {
    log.debug("收到取消工单请求, ticketId={}, version={}", id, request.getVersion());
    return Result.success(ticketService.cancel(id, request));
  }

  /**
   * 评价已关闭的工单。
   *
   * <p>
   * 只允许报修人：评价是对服务的主观反馈，主管与管理员代评会污染统计口径。
   * 报修人的数据范围只放行本人的工单，所以「评价别人的单」在业务层已是 404。
   * </p>
   *
   * @param id      工单 ID
   * @param request 评价入参，评分 1-5，评价内容可选
   * @return 新建的评价，见接口文档 2.6
   */
  @PostMapping("/{id}/feedback")
  @RequiredRole(Role.REPORTER)
  public Result<TicketFeedbackVO> feedback(@PathVariable Long id,
      @RequestBody @Valid TicketFeedbackRequest request) {
    log.debug("收到工单评价请求, ticketId={}, score={}", id, request.getScore());
    return Result.success(ticketService.feedback(id, request));
  }

}
