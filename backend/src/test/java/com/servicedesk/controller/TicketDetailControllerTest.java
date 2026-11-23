package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.mapper.TicketAttachmentMapper;
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

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单详情接口的集成测试（T-03）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器与 MySQL，数据由用例自建并依赖
 * {@code @Transactional} 回滚。重点守住四件事：越权与不存在返回同一个 404、
 * 待派单工单对主管不可见（过渡口径只属于列表）、SLA 的响应/解决两套超时口径、
 * 历史暂停字段不再影响读数。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketDetailControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** 用于直接签发指定角色的 token。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 工单 Mapper。 */
  @Autowired
  private TicketMapper ticketMapper;

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

  /** 附件 Mapper。 */
  @Autowired
  private TicketAttachmentMapper attachmentMapper;

  /** 密码编码器。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 报修人 A。 */
  private SysUser reporterA;

  /** 报修人 B，用于构造他人不可见的工单。 */
  private SysUser reporterB;

  /** 工程师 A，属于团队 A。 */
  private SysUser engineerA;

  /** 工程师 B，属于团队 B。 */
  private SysUser engineerB;

  /** 主管 A，管理团队 A。 */
  private SysUser supervisorA;

  /** 管理员。 */
  private SysUser admin;

  /** 团队 A。 */
  private Team teamA;

  /** 团队 B。 */
  private Team teamB;

  /** 客户。 */
  private Customer customer;

  /** 客户名下的设备。 */
  private Device device;

  /** 用例使用的 SLA 策略。 */
  private SlaPolicy policy;

  /**
   * 每个用例前准备两套团队、客户、设备与五类账号。
   */
  @BeforeEach
  void setUp() {
    // 开发库有种子工单，清空后详情断言不受干扰；事务结束自动回滚
    ticketMapper.delete(null);
    reporterA = insertUser(Role.REPORTER, null);
    reporterB = insertUser(Role.REPORTER, null);
    engineerA = insertUser(Role.ENGINEER, null);
    engineerB = insertUser(Role.ENGINEER, null);
    supervisorA = insertUser(Role.SUPERVISOR, null);
    admin = insertUser(Role.ADMIN, null);

    teamA = insertTeam("华东售后一组", supervisorA.getId());
    teamB = insertTeam("华东售后二组", null);
    engineerA.setTeamId(teamA.getId());
    engineerB.setTeamId(teamB.getId());
    sysUserMapper.updateById(engineerA);
    sysUserMapper.updateById(engineerB);

    customer = insertCustomer("九江精工机械");
    device = insertDevice(customer.getId(), "SN-DETAIL-" + System.nanoTime());
    policy = ensurePolicy("P1");
  }

  /**
   * 报修人查看自己的工单，A.8 要求的嵌套字段与 SLA 都要有值。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("详情：报修人查看自己的工单，字段完整")
  void shouldReturnDetailForOwnTicket() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null,
        now.plusMinutes(30), now.plusHours(4));
    insertAttachment(ticket.getId(), "a.jpg");
    insertAttachment(ticket.getId(), "b.jpg");

    mockMvc.perform(get("/tickets/{id}", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.id").value(ticket.getId()))
        .andExpect(jsonPath("$.data.ticketNo").value(ticket.getTicketNo()))
        .andExpect(jsonPath("$.data.status").value("NEW"))
        .andExpect(jsonPath("$.data.customer.name").value("九江精工机械"))
        .andExpect(jsonPath("$.data.device.model").value("AC-2000"))
        .andExpect(jsonPath("$.data.reporter.displayName").value(reporterA.getDisplayName()))
        .andExpect(jsonPath("$.data.assignee").value(nullValue()))
        .andExpect(jsonPath("$.data.team").value(nullValue()))
        .andExpect(jsonPath("$.data.sla.policyName").value(policy.getName()))
        .andExpect(jsonPath("$.data.attachments.length()").value(2))
        .andExpect(jsonPath("$.data.attachments[0].fileName").value("a.jpg"))
        .andExpect(jsonPath("$.data.attachments[1].fileName").value("b.jpg"))
        .andExpect(jsonPath("$.data.version").value(0));
  }

  /**
   * 报修人查看他人工单：必须 404，且提示与「不存在」完全一致，不能暴露工单存在。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：报修人查看他人工单返回 404")
  void shouldReturn404WhenReporterAccessOthers() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterB.getId(), null, null,
        LocalDateTime.now().plusMinutes(30), LocalDateTime.now().plusHours(4));

    mockMvc.perform(get("/tickets/{id}", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("工单不存在"));
  }

  /**
   * 工程师只能打开指派给自己的工单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：工程师只看指派给自己的工单")
  void shouldLimitDetailToAssigneeForEngineer() throws Exception {
    Ticket assigned = insertTicket("ASSIGNED", reporterB.getId(), engineerA.getId(), teamA.getId(),
        LocalDateTime.now().plusMinutes(30), LocalDateTime.now().plusHours(4));
    Ticket others = insertTicket("ASSIGNED", reporterB.getId(), engineerB.getId(), teamB.getId(),
        LocalDateTime.now().plusMinutes(30), LocalDateTime.now().plusHours(4));

    mockMvc.perform(get("/tickets/{id}", assigned.getId())
            .header("Authorization", bearerOf(engineerA, Role.ENGINEER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.assignee.displayName").value(engineerA.getDisplayName()));

    mockMvc.perform(get("/tickets/{id}", others.getId())
            .header("Authorization", bearerOf(engineerA, Role.ENGINEER)))
        .andExpect(status().isNotFound());
  }

  /**
   * 主管能看本团队工单，但看不到 team_id 为空的待派单工单——过渡口径只放给列表。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：主管看本团队，待派单工单详情 404")
  void shouldLimitDetailToTeamForSupervisor() throws Exception {
    Ticket inTeam = insertTicket("IN_PROGRESS", reporterB.getId(), engineerA.getId(), teamA.getId(),
        LocalDateTime.now().plusMinutes(30), LocalDateTime.now().plusHours(4));
    Ticket pending = insertTicket("NEW", reporterB.getId(), null, null,
        LocalDateTime.now().plusMinutes(30), LocalDateTime.now().plusHours(4));

    mockMvc.perform(get("/tickets/{id}", inTeam.getId())
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.team.name").value(teamA.getName()));

    mockMvc.perform(get("/tickets/{id}", pending.getId())
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isNotFound());
  }

  /**
   * 管理员不受数据范围限制。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：管理员可打开任意工单")
  void shouldAllowAnyTicketForAdmin() throws Exception {
    Ticket ticket = insertTicket("CLOSED", reporterB.getId(), engineerB.getId(), teamB.getId(),
        LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));

    mockMvc.perform(get("/tickets/{id}", ticket.getId())
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CLOSED"));
  }

  /**
   * 工单不存在返回 404，未登录返回 401。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("异常：不存在 404，未登录 401")
  void shouldReturn404WhenAbsentAnd401WhenAnonymous() throws Exception {
    mockMvc.perform(get("/tickets/{id}", 999_999_999L)
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));

    mockMvc.perform(get("/tickets/{id}", 1L).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  /**
   * 响应超时与解决超时分开判定：未响应过的单过点后只标记响应超时。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("SLA：未响应过点只标记响应超时，已响应后不再标记")
  void shouldDistinguishResponseAndResolveBreach() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("ASSIGNED", reporterA.getId(), engineerA.getId(), teamA.getId(),
        now.minusHours(1), now.plusHours(2));

    mockMvc.perform(get("/tickets/{id}", ticket.getId())
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sla.responseBreached").value(true))
        .andExpect(jsonPath("$.data.sla.resolveBreached").value(false))
        .andExpect(jsonPath("$.data.sla.firstResponseAt").value(nullValue()));

    markFirstResponse(ticket.getId(), now.minusMinutes(90));

    mockMvc.perform(get("/tickets/{id}", ticket.getId())
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sla.responseBreached").value(false))
        .andExpect(jsonPath("$.data.sla.responseRemainingMinutes").isNumber());
  }

  /**
   * 终态工单即使早过 deadline 也不算超时。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("SLA：终态工单不计超时")
  void shouldNotMarkBreachForTerminalTicket() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("CANCELLED", reporterA.getId(), null, null,
        now.minusHours(3), now.minusHours(2));

    mockMvc.perform(get("/tickets/{id}", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sla.responseBreached").value(false))
        .andExpect(jsonPath("$.data.sla.resolveBreached").value(false));
  }

  /**
   * 历史暂停字段不再参与读数：paused 恒 false，pausedTotalMinutes 恒 0。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("SLA：历史 paused_at 不影响读数")
  void shouldIgnoreLegacyPausedFields() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("IN_PROGRESS", reporterA.getId(), engineerA.getId(), teamA.getId(),
        now.minusHours(1), now.plusHours(2));
    markLegacyPaused(ticket.getId(), now.minusMinutes(10));

    mockMvc.perform(get("/tickets/{id}", ticket.getId())
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sla.paused").value(false))
        .andExpect(jsonPath("$.data.sla.pausedTotalMinutes").value(0));
  }

  /**
   * 策略与两个 deadline 都没有的历史数据，SLA 整块返回 null，不能 500。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("容错：无 SLA 数据时 sla 为 null")
  void shouldReturnNullSlaWhenNoDeadline() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, null, null);
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getSlaPolicyId, null));

    mockMvc.perform(get("/tickets/{id}", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sla").value(nullValue()));
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
   * 标记首次响应时间。
   *
   * @param ticketId 工单 ID
   * @param at       响应时刻
   */
  private void markFirstResponse(Long ticketId, LocalDateTime at) {
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticketId)
        .set(Ticket::getFirstResponseAt, at));
  }

  /**
   * 写入历史暂停字段，模拟 RC-001 之前的存量数据。
   *
   * @param ticketId 工单 ID
   * @param pausedAt 历史暂停起点
   */
  private void markLegacyPaused(Long ticketId, LocalDateTime pausedAt) {
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticketId)
        .set(Ticket::getPausedAt, pausedAt)
        .set(Ticket::getPausedTotalMinutes, 60));
  }

  /**
   * 直接插入一个账号，绕过接口，用于造数。
   *
   * @param role   角色
   * @param teamId 团队 ID，可为 null
   * @return 插入后的账号实体
   */
  private SysUser insertUser(Role role, Long teamId) {
    SysUser user = new SysUser();
    user.setUsername("ut_detail_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "详情测试账号");
    user.setRole(role.name());
    user.setTeamId(teamId);
    user.setStatus(1);
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
    entity.setName(name);
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
    entity.setName(priority + " 测试策略");
    entity.setPriority(priority);
    entity.setResponseMinutes(30);
    entity.setResolveMinutes(240);
    entity.setEnabled(1);
    slaPolicyMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一张工单。
   *
   * @param status           状态
   * @param reporterId       报修人 ID
   * @param assigneeId       处理人 ID，可为 null
   * @param teamId           团队 ID，可为 null
   * @param responseDeadline 响应截止，可为 null
   * @param resolveDeadline  解决截止，可为 null
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, Long reporterId, Long assigneeId, Long teamId,
      LocalDateTime responseDeadline, LocalDateTime resolveDeadline) {
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
    ticket.setResponseDeadline(responseDeadline);
    ticket.setResolveDeadline(resolveDeadline);
    ticket.setPausedTotalMinutes(0);
    ticket.setAiCorrected(0);
    ticketMapper.insert(ticket);
    return ticket;
  }

  /**
   * 直接插入一个附件。
   *
   * @param ticketId 工单 ID
   * @param fileName 文件名
   */
  private void insertAttachment(Long ticketId, String fileName) {
    TicketAttachment attachment = new TicketAttachment();
    attachment.setTicketId(ticketId);
    attachment.setUploaderId(reporterA.getId());
    attachment.setFileName(fileName);
    attachment.setFileUrl("https://oss.example.com/ticket/" + fileName);
    attachment.setFileSize(1024L);
    attachment.setMimeType("image/jpeg");
    attachmentMapper.insert(attachment);
  }
}
