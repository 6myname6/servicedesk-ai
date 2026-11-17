package com.servicedesk.controller;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.api.Result;
import com.servicedesk.dto.request.ticket.TicketCreateRequest;
import com.servicedesk.dto.request.ticket.TicketQueryRequest;
import com.servicedesk.service.TicketService;
import com.servicedesk.vo.TicketDetailVO;
import com.servicedesk.vo.TicketListItemVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单接口，对应接口文档第 2 章。
 *
 * <p>
 * 属于接口层，只做参数接收、{@code @Valid} 校验与响应包装。建单只要求登录，
 * 不限制角色；报修人取当前登录用户，由业务层从 {@code UserContext} 读取，
 * 不接收请求参数中的上报人。
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
}
