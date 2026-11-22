package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.mapper.TicketEventMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 状态迁移接口的集成测试（F-02）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器、MySQL 与乐观锁，数据由用例自建并依赖
 * {@code @Transactional} 回滚。重点守住六件事：七个动作的时间戳副作用、SLA 暂停与补时、
 * 非法迁移与终态（409）、角色不符（403）、数据范围（404）、版本冲突（409）与参数校验（400）。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketStatusControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 迁移备注样例。 */
  private static final String COMMENT = "已联系客户，约 14:00 上门";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 序列化工具。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用于直接签发指定角色的 token。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 工单 Mapper。 */
  @Autowired
  private TicketMapper ticketMapper;

  /** 工单事件 Mapper，用于断言时间线。 */
  @Autowired
  private TicketEventMapper ticketEventMapper;

  /** 客户 Mapper。 */
  @Autowired
  private CustomerMapper customerMapper;

  /** 设备 Mapper。 */
  @Autowired
  private DeviceMapper deviceMapper;

  /** 团队 Mapper。 */
  @Autowired
  private TeamMapper teamMapper;

  /** 用户 Mapper。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** SLA 策略 Mapper。 */
  @Autowired
  private SlaPolicyMapper slaPolicyMapper;

  /** 密码编码器。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 报修人，本人持有待流转的工单。 */
  private SysUser reporter;

  /** 另一个报修人，用于验证报修人的数据范围。 */
  private SysUser otherReporter;

  /** 工程师 A，团队 A 的处理人。 */
  private SysUser engineerA;

  /** 工程师 B，团队 A 的另一个工程师，用于验证「只能操作指派给自己的单」。 */
  private SysUser engineerB;

  /** 主管 A，管理团队 A。 */
  private SysUser supervisorA;

  /** 管理员。 */
  private SysUser admin;

  /** 团队 A。 */
  private Team teamA;

  /** 团队 B，无人管理，用于验证主管跨团队 404。 */
  private Team teamB;

  /** 客户。 */
  private Customer customer;

  /** 设备。 */
  private Device device;

  /** 解决时限 240 分钟的 SLA 策略。 */
  private SlaPolicy policy;

  /**
   * 每个用例前准备两个团队、五类账号与基础档案。
   */
  @BeforeEach
  void setUp() {
    ticketMapper.delete(null);
    ticketEventMapper.delete(null);

    supervisorA = insertUser(Role.SUPERVISOR, null, 1);
    admin = insertUser(Role.ADMIN, null, 1);
    reporter = insertUser(Role.REPORTER, null, 1);
    otherReporter = insertUser(Role.REPORTER, null, 1);

    teamA = insertTeam("状态流转测试一组", supervisorA.getId());
    teamB = insertTeam("状态流转测试二组", null);

    engineerA = insertUser(Role.ENGINEER, teamA.getId(), 1);
    engineerB = insertUser(Role.ENGINEER, teamA.getId(), 1);

    customer = insertCustomer("九江精工机械");
    device = insertDevice(customer.getId(), "SN-STATUS-" + System.nanoTime());
    policy = ensurePolicy("P1");
  }

  /**
   * 接单：ASSIGNED → ACCEPTED，写首次响应时间与 ACCEPT 事件。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("接单：被派工程师接单写 first_response_at 与 ACCEPT 事件")
  void shouldAcceptAssignedTicket() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporter.getId(), engineerA.getId(), teamA.getId());

    patchStatus(ticket.getId(), "ACCEPTED", 0, COMMENT, engineerA, Role.ENGINEER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.version").value(1));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getFirstResponseAt()).isNotNull();
    assertThat(saved.getResolvedAt()).isNull();
    assertThat(saved.getClosedAt()).isNull();

    TicketEvent event = singleEvent(ticket.getId());
    assertThat(event.getType()).isEqualTo("ACCEPT");
    assertThat(event.getFromStatus()).isEqualTo("ASSIGNED");
    assertThat(event.getToStatus()).isEqualTo("ACCEPTED");
    assertThat(event.getOperatorId()).isEqualTo(engineerA.getId());
    assertThat(event.getContent()).isEqualTo(COMMENT);
  }

  /**
   * 接单不覆盖已存在的首次响应时间：处理记录先写入时以最早者为准。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("接单：已有 first_response_at 时保持原值")
  void shouldKeepEarlierFirstResponseWhenAccepting() throws Exception {
    LocalDateTime respondedAt = LocalDateTime.now().withNano(0).minusHours(1);
    Ticket ticket = insertTicket("ASSIGNED", reporter.getId(), engineerA.getId(), teamA.getId(),
        null, respondedAt, null);

    patchStatus(ticket.getId(), "ACCEPTED", 0, null, engineerA, Role.ENGINEER)
        .andExpect(status().isOk());

    assertThat(ticketMapper.selectById(ticket.getId()).getFirstResponseAt()).isEqualTo(respondedAt);
  }

  /**
   * 开始处理：ACCEPTED → IN_PROGRESS，只改状态不写时间戳。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("开始处理：ACCEPTED 转 IN_PROGRESS 写 START 事件")
  void shouldStartProcessingFromAccepted() throws Exception {
    Ticket ticket = insertTicket("ACCEPTED", reporter.getId(), engineerA.getId(), teamA.getId());

    patchStatus(ticket.getId(), "IN_PROGRESS", 0, null, engineerA, Role.ENGINEER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getFirstResponseAt()).isNull();
    assertThat(saved.getPausedAt()).isNull();

    TicketEvent event = singleEvent(ticket.getId());
    assertThat(event.getType()).isEqualTo("START");
    assertThat(event.getFromStatus()).isEqualTo("ACCEPTED");
    assertThat(event.getContent()).isNull();
  }

  /**
   * 开始处理也可以从 REOPENED 进入。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("开始处理：REOPENED 转 IN_PROGRESS")
  void shouldStartProcessingFromReopened() throws Exception {
    Ticket ticket = insertTicket("REOPENED", reporter.getId(), engineerA.getId(), teamA.getId());

    patchStatus(ticket.getId(), "IN_PROGRESS", 0, null, engineerA, Role.ENGINEER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

    assertThat(singleEvent(ticket.getId()).getFromStatus()).isEqualTo("REOPENED");
  }

  /**
   * 等客户：IN_PROGRESS → WAITING_CUSTOMER，写暂停起点但不移动 deadline。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("等客户：写 paused_at 且两个 deadline 不变")
  void shouldPauseSlaWhenWaitingCustomer() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());

    patchStatus(ticket.getId(), "WAITING_CUSTOMER", 0, null, engineerA, Role.ENGINEER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WAITING_CUSTOMER"));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getPausedAt()).isNotNull();
    assertThat(saved.getPausedTotalMinutes()).isZero();
    assertThat(saved.getResponseDeadline()).isEqualTo(ticket.getResponseDeadline());
    assertThat(saved.getResolveDeadline()).isEqualTo(ticket.getResolveDeadline());
    assertThat(singleEvent(ticket.getId()).getType()).isEqualTo("WAIT_CUSTOMER");
  }

  /**
   * 客户回复：暂停 2 小时后恢复，累计暂停与两个 deadline 同步补回 2 小时。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("客户回复：补回 2 小时暂停时长并清空 paused_at")
  void shouldShiftDeadlinesWhenResuming() throws Exception {
    LocalDateTime pausedAt = LocalDateTime.now().withNano(0).minusHours(2);
    Ticket ticket = insertTicket("WAITING_CUSTOMER", reporter.getId(), engineerA.getId(),
        teamA.getId(), pausedAt, null, null);

    patchStatus(ticket.getId(), "IN_PROGRESS", 0, null, engineerA, Role.ENGINEER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getPausedAt()).isNull();
    assertThat(saved.getPausedTotalMinutes()).isEqualTo(120);
    assertThat(saved.getResponseDeadline()).isEqualTo(ticket.getResponseDeadline().plusMinutes(120));
    assertThat(saved.getResolveDeadline()).isEqualTo(ticket.getResolveDeadline().plusMinutes(120));
    assertThat(singleEvent(ticket.getId()).getType()).isEqualTo("RESUME");
  }

  /**
   * 解决：IN_PROGRESS → RESOLVED，写解决时间。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("解决：写 resolved_at 与 RESOLVE 事件")
  void shouldResolveTicket() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());

    patchStatus(ticket.getId(), "RESOLVED", 0, COMMENT, engineerA, Role.ENGINEER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("RESOLVED"));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getResolvedAt()).isNotNull();
    assertThat(saved.getClosedAt()).isNull();
    assertThat(singleEvent(ticket.getId()).getType()).isEqualTo("RESOLVE");
  }

  /**
   * 验收关闭：RESOLVED → CLOSED，报修人本人可关闭并写关闭时间。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("验收关闭：报修人关闭工单并写 closed_at")
  void shouldCloseTicketByReporter() throws Exception {
    Ticket ticket = insertTicket("RESOLVED", reporter.getId(), engineerA.getId(), teamA.getId());

    patchStatus(ticket.getId(), "CLOSED", 0, "问题已解决", reporter, Role.REPORTER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CLOSED"));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getClosedAt()).isNotNull();
    assertThat(saved.getResolvedAt()).isNull();
    assertThat(singleEvent(ticket.getId()).getType()).isEqualTo("CLOSE");
  }

  /**
   * 验收关闭：管理员不受团队限制，可关闭任意工单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("验收关闭：管理员可关闭其它团队的工单")
  void shouldAllowAdminToClose() throws Exception {
    Ticket ticket = insertTicket("RESOLVED", otherReporter.getId(), engineerA.getId(), teamB.getId());

    patchStatus(ticket.getId(), "CLOSED", 0, null, admin, Role.ADMIN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CLOSED"));
  }

  /**
   * 重开：主管重开本团队的已解决工单，清空解决时间与升级标记并重算解决 deadline。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("重开：清空 resolved_at / 升级标记并重算 resolve_deadline")
  void shouldReopenResolvedTicket() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("RESOLVED", reporter.getId(), engineerA.getId(), teamA.getId(),
        null, null, now.minusHours(1));
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getResolvedAt, now.minusHours(2)));

    LocalDateTime before = LocalDateTime.now().withNano(0);
    patchStatus(ticket.getId(), "REOPENED", 0, "问题复现", supervisorA, Role.SUPERVISOR)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REOPENED"));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getResolvedAt()).isNull();
    assertThat(saved.getResolveEscalatedAt()).isNull();
    assertThat(Duration.between(before, saved.getResolveDeadline()).toMinutes())
        .isBetween(240L, 241L);

    TicketEvent event = singleEvent(ticket.getId());
    assertThat(event.getType()).isEqualTo("REOPEN");
    assertThat(event.getFromStatus()).isEqualTo("RESOLVED");
    assertThat(event.getToStatus()).isEqualTo("REOPENED");
    assertThat(event.getOperatorId()).isEqualTo(supervisorA.getId());
  }

  /**
   * 状态机不允许的迁移返回 409，且不写时间线。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("状态机：NEW 直接关闭返回 409")
  void shouldRejectIllegalTransition() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), null, null);

    patchStatus(ticket.getId(), "CLOSED", 0, null, reporter, Role.REPORTER)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("当前状态 NEW 不可迁移到 CLOSED"));

    assertThat(ticketMapper.selectById(ticket.getId()).getStatus()).isEqualTo("NEW");
    assertThat(eventCount(ticket.getId())).isZero();
  }

  /**
   * 终态没有任何出边，任何目标状态都返回 409。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("状态机：CLOSED 与 CANCELLED 不可再迁移")
  void shouldRejectTransitionFromTerminalStatus() throws Exception {
    Ticket closed = insertTicket("CLOSED", reporter.getId(), engineerA.getId(), teamA.getId());
    patchStatus(closed.getId(), "REOPENED", 0, null, reporter, Role.REPORTER)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("当前状态 CLOSED 不可迁移到 REOPENED"));

    Ticket cancelled = insertTicket("CANCELLED", reporter.getId(), null, null);
    patchStatus(cancelled.getId(), "IN_PROGRESS", 0, null, admin, Role.ADMIN)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("当前状态 CANCELLED 不可迁移到 IN_PROGRESS"));
  }

  /**
   * 角色不符返回 403：报修人不能接单，主管不能接单，工程师不能关闭。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：角色不符的迁移返回 403")
  void shouldForbidRoleMismatch() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporter.getId(), engineerA.getId(), teamA.getId());

    patchStatus(ticket.getId(), "ACCEPTED", 0, null, reporter, Role.REPORTER)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("当前角色无权执行该状态迁移"));

    patchStatus(ticket.getId(), "ACCEPTED", 0, null, supervisorA, Role.SUPERVISOR)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("当前角色无权执行该状态迁移"));

    Ticket resolved = insertTicket("RESOLVED", reporter.getId(), engineerA.getId(), teamA.getId());
    patchStatus(resolved.getId(), "CLOSED", 0, null, engineerA, Role.ENGINEER)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("当前角色无权执行该状态迁移"));
  }

  /**
   * 数据范围外统一 404：工程师看他人单、报修人看他人单、主管看其他团队。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：越权与不存在统一返回 404")
  void shouldReturn404OutsideScope() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporter.getId(), engineerA.getId(), teamA.getId());

    patchStatus(ticket.getId(), "ACCEPTED", 0, null, engineerB, Role.ENGINEER)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));

    patchStatus(ticket.getId(), "ACCEPTED", 0, null, otherReporter, Role.REPORTER)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));

    Ticket otherTeam = insertTicket("RESOLVED", reporter.getId(), engineerA.getId(), teamB.getId());
    patchStatus(otherTeam.getId(), "CLOSED", 0, null, supervisorA, Role.SUPERVISOR)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));
  }

  /**
   * 旧版本号提交返回 409，工单不被修改。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("并发：旧 version 迁移返回 409")
  void shouldRejectStaleVersion() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporter.getId(), engineerA.getId(), teamA.getId());
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getVersion, 3));

    patchStatus(ticket.getId(), "ACCEPTED", 0, null, engineerA, Role.ENGINEER)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("工单已被他人修改，请刷新后重试"));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getStatus()).isEqualTo("ASSIGNED");
    assertThat(saved.getFirstResponseAt()).isNull();
    assertThat(eventCount(ticket.getId())).isZero();
  }

  /**
   * 缺目标状态、缺版本号、目标状态不在字典内都返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：缺参或目标状态非法返回 400")
  void shouldRejectInvalidRequest() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporter.getId(), engineerA.getId(), teamA.getId());

    Map<String, Object> noTarget = new LinkedHashMap<>();
    noTarget.put("version", 0);
    mockMvc.perform(patch("/tickets/{id}/status", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(noTarget))
            .header("Authorization", bearerOf(engineerA, Role.ENGINEER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    Map<String, Object> noVersion = new LinkedHashMap<>();
    noVersion.put("targetStatus", "ACCEPTED");
    mockMvc.perform(patch("/tickets/{id}/status", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(noVersion))
            .header("Authorization", bearerOf(engineerA, Role.ENGINEER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    patchStatus(ticket.getId(), "DONE", 0, null, engineerA, Role.ENGINEER)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("工单状态不合法: DONE"));

    assertThat(ticketMapper.selectById(ticket.getId()).getStatus()).isEqualTo("ASSIGNED");
  }

  /**
   * 发起一次状态迁移请求。
   *
   * @param ticketId   工单 ID
   * @param target     目标状态
   * @param version    版本号
   * @param comment    备注，可为 null
   * @param user       操作人
   * @param role       操作人角色
   * @return MockMvc 结果，供断言链继续使用
   * @throws Exception MockMvc 调用异常
   */
  private ResultActions patchStatus(Long ticketId, String target, int version, String comment,
      SysUser user, Role role) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetStatus", target);
    body.put("comment", comment);
    body.put("version", version);
    return mockMvc.perform(patch("/tickets/{id}/status", ticketId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body))
        .header("Authorization", bearerOf(user, role)));
  }

  /**
   * 取工单唯一的一条时间线事件。
   *
   * @param ticketId 工单 ID
   * @return 事件实体
   */
  private TicketEvent singleEvent(Long ticketId) {
    List<TicketEvent> events = ticketEventMapper.selectList(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticketId));
    assertThat(events).hasSize(1);
    return events.get(0);
  }

  /**
   * 统计工单的时间线事件条数。
   *
   * @param ticketId 工单 ID
   * @return 事件条数
   */
  private long eventCount(Long ticketId) {
    return ticketEventMapper.selectCount(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticketId));
  }

  /**
   * 为指定用户与角色签发鉴权请求头。
   *
   * @param user 账号
   * @param role 角色
   * @return 形如 {@code Bearer xxx} 的请求头取值
   */
  private String bearerOf(SysUser user, Role role) {
    LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(),
        role.name(), user.getTeamId());
    return "Bearer " + jwtUtil.generate(loginUser);
  }

  /**
   * 直接插入一个账号，绕过接口，用于造数。
   *
   * @param role   角色
   * @param teamId 团队 ID，可为 null
   * @param status 状态：1 启用 / 0 停用
   * @return 插入后的账号实体
   */
  private SysUser insertUser(Role role, Long teamId, int status) {
    SysUser user = new SysUser();
    user.setUsername("ut_status_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "流转测试账号");
    user.setRole(role.name());
    user.setTeamId(teamId);
    user.setStatus(status);
    sysUserMapper.insert(user);
    return user;
  }

  /**
   * 直接插入一个团队。
   *
   * @param name         团队名
   * @param supervisorId 主管 ID，可为 null
   * @return 插入后的团队实体
   */
  private Team insertTeam(String name, Long supervisorId) {
    Team team = new Team();
    team.setName(name + "-" + System.nanoTime());
    team.setSupervisorId(supervisorId);
    teamMapper.insert(team);
    return team;
  }

  /**
   * 直接插入一个客户。
   *
   * @param name 客户名
   * @return 插入后的客户实体
   */
  private Customer insertCustomer(String name) {
    Customer entity = new Customer();
    entity.setName(name + "-" + System.nanoTime());
    entity.setPhone("0792-0000000");
    entity.setLevel("NORMAL");
    customerMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一台设备。
   *
   * @param customerId 所属客户 ID
   * @param serialNo   序列号
   * @return 插入后的设备实体
   */
  private Device insertDevice(Long customerId, String serialNo) {
    Device entity = new Device();
    entity.setCustomerId(customerId);
    entity.setModel("AC-2000");
    entity.setSerialNo(serialNo);
    entity.setStatus(DeviceStatus.ACTIVE.name());
    deviceMapper.insert(entity);
    return entity;
  }

  /**
   * 确保指定优先级存在一条启用策略，已存在则复用。
   *
   * @param priority 优先级
   * @return 策略实体
   */
  private SlaPolicy ensurePolicy(String priority) {
    SlaPolicy existing = slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
        .eq(SlaPolicy::getPriority, priority)
        .eq(SlaPolicy::getEnabled, 1)
        .last("LIMIT 1"));
    if (existing != null) {
      return existing;
    }
    SlaPolicy entity = new SlaPolicy();
    entity.setName(priority + " 流转测试策略-" + System.nanoTime());
    entity.setPriority(priority);
    entity.setResponseMinutes(30);
    entity.setResolveMinutes(240);
    entity.setEnabled(1);
    slaPolicyMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一张工单，不设置暂停与时间戳。
   *
   * @param status     状态
   * @param reporterId 报修人 ID
   * @param assigneeId 处理人 ID，可为 null
   * @param teamId     团队 ID，可为 null
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, Long reporterId, Long assigneeId, Long teamId) {
    return insertTicket(status, reporterId, assigneeId, teamId, null, null, null);
  }

  /**
   * 直接插入一张工单，并指定暂停起点、首次响应与解决升级标记。
   *
   * @param status             状态
   * @param reporterId         报修人 ID
   * @param assigneeId         处理人 ID，可为 null
   * @param teamId             团队 ID，可为 null
   * @param pausedAt           本次暂停起点，可为 null
   * @param firstResponseAt    首次响应时间，可为 null
   * @param resolveEscalatedAt 解决超时升级时间，可为 null
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, Long reporterId, Long assigneeId, Long teamId,
      LocalDateTime pausedAt, LocalDateTime firstResponseAt, LocalDateTime resolveEscalatedAt) {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = new Ticket();
    ticket.setTicketNo("TK" + System.nanoTime());
    ticket.setTitle("空压机运行异响");
    ticket.setDescription("开机 10 分钟后出现金属摩擦声，压力表波动。");
    ticket.setCategory("HARDWARE");
    ticket.setUrgency("P1");
    ticket.setStatus(status);
    ticket.setCustomerId(customer.getId());
    ticket.setDeviceId(device.getId());
    ticket.setReporterId(reporterId);
    ticket.setAssigneeId(assigneeId);
    ticket.setTeamId(teamId);
    ticket.setSlaPolicyId(policy.getId());
    ticket.setResponseDeadline(now.plusMinutes(30));
    ticket.setResolveDeadline(now.plusMinutes(240));
    ticket.setFirstResponseAt(firstResponseAt);
    ticket.setPausedAt(pausedAt);
    ticket.setPausedTotalMinutes(0);
    ticket.setResolveEscalatedAt(resolveEscalatedAt);
    ticket.setAiCorrected(0);
    ticketMapper.insert(ticket);
    return ticket;
  }
}
