package com.servicedesk.controller;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.api.Result;
import com.servicedesk.dto.request.ticket.TicketAssignRequest;
import com.servicedesk.dto.request.ticket.TicketCancelRequest;
import com.servicedesk.dto.request.ticket.TicketCommentRequest;
import com.servicedesk.dto.request.ticket.TicketCreateRequest;
import com.servicedesk.dto.request.ticket.TicketFeedbackRequest;
import com.servicedesk.dto.request.ticket.TicketQueryRequest;
import com.servicedesk.dto.request.ticket.TicketStatusTransitionRequest;
import com.servicedesk.dto.request.ticket.TicketUpdateRequest;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.security.RequiredRole;
import com.servicedesk.service.TicketCommentService;
import com.servicedesk.service.TicketService;
import com.servicedesk.service.TicketTransitionService;
import com.servicedesk.vo.SlaStatusVO;
import com.servicedesk.vo.TicketDetailVO;
import com.servicedesk.vo.TicketEventVO;
import com.servicedesk.vo.TicketFeedbackVO;
import com.servicedesk.vo.TicketListItemVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单接口，对应接口文档第 2 章（建单 2.2、列表 2.1、详情 2.3、编辑 2.4、取消 2.5、评价 2.6）
 * 与第 3 章工单流转（3.1 状态迁移、3.2 派单、3.3 处理记录、3.4 时间线）。
 *
 * <p>
 * 属于接口层，只做参数接收、{@code @Valid} 校验与响应包装，不写业务判断：
 * 角色一般由 {@code RoleInterceptor} 依据方法上的 {@link RequiredRole} 判定，
 * 行级数据范围与状态校验由业务层完成。例外是状态迁移：同一接口对不同流转有不同角色要求，
 * 接口层不拦角色（登录即可），由业务层的迁移表返回 403。
 * </p>
 *
 * <p>
 * 建单只要求登录，报修人取当前登录用户（业务层从 {@code UserContext} 读取），
 * 不接收请求参数中的上报人；详情对所有角色开放，看到哪张单由数据范围决定。
 * 状态迁移走独立的 {@link TicketTransitionService}，处理记录与时间线走
 * {@link TicketCommentService}，其余接口走 {@link TicketService}。
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

  /** 工单服务：建单、列表、详情、编辑、取消、评价、派单。 */
  private final TicketService ticketService;

  /** 状态迁移服务：F-02 的五个流转动作，独立于工单主链路。 */
  private final TicketTransitionService ticketTransitionService;

  /** 处理记录与时间线服务：F-03 写入 COMMENT 事件，F-04 不分页读出时间线。 */
  private final TicketCommentService ticketCommentService;

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

  /**
   * 派单：把 NEW 工单指派给工程师。
   *
   * <p>
   * 只有主管与管理员可调用。被派人必须是启用中的 {@code ENGINEER}，且属于主管管理的团队；
   * 管理员不受团队限制。状态、团队归属与版本冲突全部由业务层判定（F-01）。
   * </p>
   *
   * @param id      工单 ID
   * @param request 派单入参，工程师与版本号必填，备注可选
   * @return 派单后的工单详情，{@code status=ASSIGNED}
   */
  @PostMapping("/{id}/assign")
  @RequiredRole({ Role.SUPERVISOR, Role.ADMIN })
  public Result<TicketDetailVO> assign(@PathVariable Long id,
      @RequestBody @Valid TicketAssignRequest request) {
    log.debug("收到派单请求, ticketId={}, assigneeId={}, version={}",
        id, request.getAssigneeId(), request.getVersion());
    return Result.success(ticketService.assign(id, request));
  }

  /**
   * 状态迁移：按状态机把工单推进到目标状态。
   *
   * <p>
   * 不标 {@code @RequiredRole}：同一接口对不同迁移有不同角色要求（接单 / 开始处理 / 解决
   * 仅被派工程师，验收通过 / 验收不通过为报修人本人、主管、管理员），
   * 单一注解无法表达。登录即可访问，角色、状态可达性与数据范围统一由业务层的
   * {@code TicketTransition} 表 + 访问守卫判定（F-02）。
   * </p>
   *
   * @param id      工单 ID
   * @param request 迁转入参，目标状态与版本号必填，备注可选
   * @return 迁移后的工单详情，见接口文档 3.1
   */
  @PatchMapping("/{id}/status")
  public Result<TicketDetailVO> transitionStatus(@PathVariable Long id,
      @RequestBody @Valid TicketStatusTransitionRequest request) {
    log.debug("收到工单状态迁移请求, ticketId={}, targetStatus={}, version={}",
        id, request.getTargetStatus(), request.getVersion());
    return Result.success(ticketTransitionService.transitionStatus(id, request));
  }

  /**
   * 追加处理记录或内部备注。
   *
   * <p>
   * 不标 {@code @RequiredRole}：数据范围内的四类角色都能写对客记录，只有内部备注限
   * 工程师 / 主管 / 管理员。角色、工单状态与内容是否合法统一由业务层判定（400 / 404 / 409），
   * Controller 不承担业务判断。
   * </p>
   *
   * @param id      工单 ID
   * @param request 处理记录入参，正文必填，{@code internal} 默认 false
   * @return 新写入的事件，结构见接口文档附录 A.10
   */
  @PostMapping("/{id}/comments")
  public Result<TicketEventVO> addComment(@PathVariable Long id,
      @RequestBody @Valid TicketCommentRequest request) {
    log.debug("收到追加处理记录请求, ticketId={}, internal={}, contentLength={}",
        id, request.getInternal(), request.getContent() == null ? 0 : request.getContent().length());
    return Result.success(ticketCommentService.addComment(id, request));
  }

  /**
   * 查询工单时间线。
   *
   * <p>
   * 不分页，也无筛选参数：一次返回全部可见事件，服务端硬上限 500 条。
   * 报修人看不到内部备注，过滤由业务层在 SQL 中完成。
   * </p>
   *
   * @param id 工单 ID
   * @return 按时间正序的事件数组
   */
  @GetMapping("/{id}/events")
  public Result<List<TicketEventVO>> listEvents(@PathVariable Long id) {
    log.debug("收到工单时间线请求, ticketId={}", id);
    return Result.success(ticketCommentService.listEvents(id));
  }

  /**
   * 查询工单 SLA 倒计时状态（S-02）。
   *
   * <p>
   * 数据范围内用户可读，不限制角色：能不能看由业务层按数据范围判定，越权返回 404。
   * 剩余分钟按请求时刻实时计算，终态照常返回剩余值但两个超时标记恒为 false。
   * 响应结构见接口文档 5.5 与附录 A.9。
   * </p>
   *
   * @param id 工单 ID
   * @return SLA 状态；历史数据无策略与 deadline 时 {@code data} 为 null
   */
  @GetMapping("/{id}/sla")
  public Result<SlaStatusVO> getTicketSlaStatus(@PathVariable Long id) {
    log.debug("收到工单 SLA 状态请求, ticketId={}", id);
    return Result.success(ticketService.getTicketSlaStatus(id));
  }

}
